package io.legado.app.help.readaloud.server

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import io.legado.app.help.readaloud.novel.NovelAudioBudgetLedger
import java.util.concurrent.CopyOnWriteArrayList

/** 真实 loopback HTTP；不访问用户服务器，不要求额外测试库。 */
class NovelAudioServerClientTest {
    @get:Rule val budgetFiles = TemporaryFolder()
    private lateinit var server: NanoHTTPD
    private var beforeResponse: () -> Unit = {}
    private val requests = CopyOnWriteArrayList<Triple<String, String?, String>>()
    private var status = 200
    private var mime = "application/json"
    private var payload = """{"status":"ok","apiVersion":"1","directorReady":true,"ttsReady":true}"""
    private var profile: String? = null
    private val leaseHeaders = CopyOnWriteArrayList<String?>()
    private var retryAfter: String? = null
    private var rawPayload: ByteArray? = null
    private var chunked = false
    private lateinit var client: NovelAudioServerClient

    @Before fun setup() {
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                requests += Triple(session.uri, session.headers["authorization"], files["postData"].orEmpty())
                leaseHeaders += session.headers["x-novelaudio-lease"]
                beforeResponse()
                val bytes = rawPayload ?: payload.toByteArray(Charsets.UTF_8)
                val response = if (chunked) {
                    newChunkedResponse(Response.Status.lookup(status), mime, bytes.inputStream())
                } else {
                    newFixedLengthResponse(
                        Response.Status.lookup(status), mime, bytes.inputStream(), bytes.size.toLong()
                    )
                }
                return response.apply {
                    profile?.let { addHeader("X-TTS-Profile", it) }
                    retryAfter?.let { addHeader("Retry-After", it) }
                    if (this@NovelAudioServerClientTest.status == 302) addHeader("Location", "/stolen")
                }
            }
        }
        server.start()
        client = NovelAudioServerClient(
            "http://127.0.0.1:${server.listeningPort}/prefix",
            { "test-secret" },
            budgetLedger = NovelAudioBudgetLedger(
                budgetFiles.newFolder().resolve("budget.json")
            )
        )
    }

    @After fun teardown() {
        server.stop()
    }

    @Test fun `health uses versioned path bearer and readiness`() = runBlocking {
        val result = client.health()
        assertTrue(result.directorReady && result.ttsReady)
        assertEquals("/prefix/v1/health", requests.single().first)
        assertEquals("Bearer test-secret", requests.single().second)
        assertEquals("", requests.single().third)
    }

    @Test fun `runtime lease endpoints use strict wire shapes and lease header`() = runBlocking {
        payload = """{"leaseId":"lease-1","runtimeProfile":"local-qwen-v1"}"""
        val lease = client.acquireRuntime("session-1", "auto_prefetch", 3)
        assertEquals("lease-1", lease.leaseId)
        assertEquals("local-qwen-v1", lease.runtimeProfile)
        assertEquals("/prefix/v1/runtime/acquire", requests[0].first)
        assertTrue(requests[0].third.contains("session-1"))

        payload = """{"state":"ready","activeLease":true}"""
        val runtime = client.runtimeStatus()
        assertEquals("ready", runtime.state)
        assertTrue(runtime.activeLease)

        payload = """{"state":"idle"}"""
        client.releaseRuntime("lease-1")
        assertEquals("/prefix/v1/runtime/release", requests.last().first)
        assertEquals("lease-1", leaseHeaders.last())
        assertEquals("", requests.last().third)
    }

    @Test fun `lease is attached to generation requests`() = runBlocking {
        payload = """{"assignments":[{"unitId":"u1","speakerId":"narrator"},{"unitId":"u2","speakerId":"narrator"}],
            "newCharacters":[],"aliasUpdates":[]}"""
        client.analyze(request(), "lease-1")
        assertEquals("lease-1", leaseHeaders.last())
    }

    @Test fun `runtime lease responses reject missing mistyped and unknown fields`() = runBlocking {
        for (body in listOf(
            "{}",
            """{"leaseId":123,"runtimeProfile":"local-qwen-v1"}""",
            """{"leaseId":"lease-1","runtimeProfile":""}"""
        )) {
            payload = body
            expectError("PROTOCOL") { client.acquireRuntime("session-1", "auto_prefetch", 3) }
        }

        for (body in listOf(
            "{}",
            """{"state":"unknown","activeLease":false}""",
            """{"state":"ready","activeLease":"true"}"""
        )) {
            payload = body
            expectError("PROTOCOL") { client.runtimeStatus() }
        }

        payload = """{"state":"ready"}"""
        expectError("PROTOCOL") { client.releaseRuntime("lease-1") }
        Unit
    }

    @Test fun `runtime lease validates request bounds before network`() = runBlocking {
        for ((sessionId, purpose, chapterCount) in listOf(
            Triple("", "auto_prefetch", 3),
            Triple("session-1", "", 3),
            Triple("session-1", "auto_prefetch", 0),
            Triple("session-1", "auto_prefetch", 10_001)
        )) {
            expectError("CONFIG") {
                client.acquireRuntime(sessionId, purpose, chapterCount)
            }
        }
        expectError("CONFIG") { client.releaseRuntime("bad lease") }
        assertTrue(requests.isEmpty())
    }

    @Test fun `unready health stays unready`() = runBlocking {
        payload = payload.replace("\"ttsReady\":true", "\"ttsReady\":false")
        assertFalse(client.health().ttsReady)
    }

    @Test fun `missing inference routes are not runtime compatibility signals`() = runBlocking {
        status = 404
        payload = """{"error":{"code":"not_found"}}"""
        expectError("HTTP") { client.health() }
        expectError("HTTP") { client.analyze(request()) }
        expectError("HTTP") { client.synthesize(SynthesisRequest("原文", "M017")) }
        Unit
    }

    @Test fun `release sends a real empty POST body`() = runBlocking {
        payload = """{"state":"idle"}"""
        client.releaseRuntime("lease-1")
        assertEquals("/prefix/v1/runtime/release", requests.single().first)
        assertEquals("", requests.single().third)
        assertEquals(listOf("lease-1"), leaseHeaders)
    }

    @Test fun `lease carries optional segment limit and self hosted flag`() = runBlocking {
        payload = """{"leaseId":"lease-1","runtimeProfile":"local-qwen-v1"}"""
        val cloud = client.acquireRuntime("session-1", "auto_prefetch", 3)
        assertEquals(0, cloud.maxSegmentChars)
        assertFalse(cloud.selfHosted)

        payload = """{"leaseId":"lease-2","runtimeProfile":"local-qwen-v1",
            "maxSegmentChars":50,"selfHosted":true}"""
        val local = client.acquireRuntime("session-1", "auto_prefetch", 3)
        assertEquals(50, local.maxSegmentChars)
        assertTrue(local.selfHosted)

        for (extra in listOf(
            "\"maxSegmentChars\":\"50\"",
            "\"maxSegmentChars\":50.5",
            "\"maxSegmentChars\":0",
            "\"maxSegmentChars\":1201",
            "\"selfHosted\":\"true\"",
            "\"selfHosted\":1"
        )) {
            payload = """{"leaseId":"lease-3","runtimeProfile":"local-qwen-v1",$extra}"""
            expectError("PROTOCOL") { client.acquireRuntime("session-1", "auto_prefetch", 3) }
        }
    }

    @Test fun `self hosted lease skips cloud budget only while held`() = runBlocking {
        val ledger = testLedger(ttsRequests = 1)
        val budgeted = budgetedClient(ledger)
        payload = """{"leaseId":"lease-local","runtimeProfile":"local-qwen-v1","selfHosted":true}"""
        budgeted.acquireRuntime("session-1", "auto_prefetch", 3)

        mime = "audio/ogg"
        profile = "mock-v1"
        payload = "OggS-test-fixture"
        // 超过云端额度上限的次数也能生成：家庭电脑不消耗云端额度。
        repeat(3) { budgeted.synthesize(SynthesisRequest("原文", "M017"), "lease-local") }
        budgeted.preview(SynthesisRequest("试听", "M017"), "lease-local")
        assertEquals(0, ledger.snapshot().ttsVendorRequests)
        assertEquals(0, ledger.snapshot().ttsUtf16Characters)

        // 无租约请求仍计额度。
        budgeted.synthesize(SynthesisRequest("原文", "M017"))
        assertEquals(1, ledger.snapshot().ttsVendorRequests)

        mime = "application/json"
        payload = """{"state":"idle"}"""
        budgeted.releaseRuntime("lease-local")
        mime = "audio/ogg"
        payload = "OggS-test-fixture"
        // 释放后同一 ID 不再享受豁免，额度已满即在发请求前拒绝。
        val sent = requests.size
        expectError("LOCAL_BUDGET_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"), "lease-local")
        }
        assertEquals(sent, requests.size)
    }

    @Test fun `ordinary lease still consumes cloud budget`() = runBlocking {
        val ledger = testLedger()
        val budgeted = budgetedClient(ledger)
        payload = """{"leaseId":"lease-cloud","runtimeProfile":"cloud-v1"}"""
        budgeted.acquireRuntime("session-1", "auto_prefetch", 3)
        mime = "audio/ogg"
        profile = "mock-v1"
        payload = "OggS-test-fixture"
        budgeted.synthesize(SynthesisRequest("原文", "M017"), "lease-cloud")
        assertEquals(1, ledger.snapshot().ttsVendorRequests)
    }

    @Test fun `home pc resource shortage maps to a non transient kind`() = runBlocking {
        status = 503
        for (code in listOf("insufficient_resources", "resource_check_failed")) {
            payload = """{"error":{"code":"$code"}}"""
            val error = expectError("INSUFFICIENT_RESOURCES") {
                client.acquireRuntime("session-1", "auto_prefetch", 3)
            }
            assertTrue(error.message!!.contains("显卡"))
        }
    }

    @Test fun `invalid lease header does not consume generation budget`() = runBlocking {
        val ledger = testLedger()
        val budgeted = budgetedClient(ledger)
        expectError("CONFIG") { budgeted.analyze(request(), "bad\nlease") }
        expectError("CONFIG") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"), "bad\nlease")
        }
        assertEquals(0, ledger.snapshot().analysisRequests)
        assertEquals(0, ledger.snapshot().ttsVendorRequests)
        assertTrue(requests.isEmpty())
    }

    @Test fun `saved snapshots never mix new tokens with old server addresses`() = runBlocking {
        val blob = object : NovelAudioCredentialBlob {
            private var value: ByteArray? = null
            override fun read() = value
            override fun write(value: ByteArray) { this.value = value }
        }
        val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = NovelAudioServerConfigStore(blob, NovelAudioServerCredentialCipher { key })
        val base = "http://127.0.0.1:${server.listeningPort}"
        store.replace("$base/old", "old-token", allowInsecureHttp = true)
        val old = store.load()!!.newClient()
        store.replace("$base/new", "new-token", allowInsecureHttp = true)
        val fresh = store.load()!!.newClient()
        old.health()
        fresh.health()
        assertEquals("/old/v1/health", requests[0].first)
        assertEquals("Bearer old-token", requests[0].second)
        assertEquals("/new/v1/health", requests[1].first)
        assertEquals("Bearer new-token", requests[1].second)
    }

    @Test fun `health rejects incompatible missing null and mistyped fields`() = runBlocking {
        for (body in listOf("{}", "null", payload.replace("\"1\"", "\"2\""),
            payload.replace("\"status\":\"ok\"", "\"status\":\"down\""),
            payload.replace("true", "\"true\""))) {
            payload = body
            expectError("PROTOCOL") { client.health() }
        }
    }

    @Test fun `http failure is bounded and does not echo secret response`() = runBlocking {
        for ((code, kind) in listOf(401 to "AUTH", 403 to "AUTH", 429 to "RATE_LIMIT", 500 to "UNAVAILABLE")) {
            status = code
            payload = "test-secret 私密正文"
            val before = requests.size
            val error = expectError(kind) { client.health() }
            assertEquals(before + 1, requests.size)
            assertFalse(error.toString().contains("test-secret"))
            assertFalse(error.toString().contains("私密正文"))
        }
    }

    @Test fun `generation requests consume persistent budget before network`() = runBlocking {
        val directory = budgetFiles.newFolder()
        val ledger = NovelAudioBudgetLedger(directory.resolve("budget.json"))
        val budgeted = NovelAudioServerClient(
            "http://127.0.0.1:${server.listeningPort}/prefix",
            { "test-secret" },
            budgetLedger = ledger
        )
        mime = "audio/ogg"
        profile = "mock-v1"
        payload = "OggS-test-fixture"

        expectError("PROTOCOL") { budgeted.analyze(request()) }
        budgeted.synthesize(SynthesisRequest("😀".repeat(600), "M017"))

        assertEquals(1, ledger.snapshot().analysisRequests)
        assertEquals(request().units.sumOf { it.text.length }, ledger.snapshot().analysisUtf16Characters)
        assertEquals(1, ledger.snapshot().ttsVendorRequests)
        assertEquals(1200, ledger.snapshot().ttsUtf16Characters)
    }

    @Test fun `free quota response is durable and never retried`() = runBlocking {
        val directory = budgetFiles.newFolder()
        val ledger = NovelAudioBudgetLedger(directory.resolve("budget.json"))
        val budgeted = NovelAudioServerClient(
            "http://127.0.0.1:${server.listeningPort}/prefix",
            { "test-secret" },
            budgetLedger = ledger
        )
        status = 429
        payload = """{"error":{"code":"free_quota_only"}}"""
        mime = "application/json"

        expectError("FREE_QUOTA_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"))
        }
        assertTrue(ledger.snapshot().cloudQuotaBlocked)
        assertEquals(1, requests.size)

        expectError("FREE_QUOTA_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"))
        }
        assertEquals(1, requests.size)
    }

    @Test fun `generation without ledger is denied before network but health works`() = runBlocking {
        val unbudgeted = NovelAudioServerClient(
            "http://127.0.0.1:${server.listeningPort}", { "test-secret" }
        )
        expectError("LOCAL_BUDGET_UNAVAILABLE") { unbudgeted.analyze(request()) }
        expectError("LOCAL_BUDGET_UNAVAILABLE") {
            unbudgeted.synthesize(SynthesisRequest("原文", "M017"))
        }
        expectError("LOCAL_BUDGET_UNAVAILABLE") {
            unbudgeted.preview(SynthesisRequest("试听", "M017"))
        }
        assertTrue(requests.isEmpty())
        assertTrue(unbudgeted.health().ttsReady)
    }

    @Test fun `each transient retry reserves again and exhaustion sends nothing`() = runBlocking {
        val ledger = testLedger(ttsRequests = 2)
        val budgeted = budgetedClient(ledger)
        status = 503
        payload = """{"error":{"code":"cloud_unavailable"}}"""
        repeat(2) {
            expectError("UNAVAILABLE") { budgeted.synthesize(SynthesisRequest("原文", "M017")) }
        }
        expectError("LOCAL_BUDGET_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"))
        }
        assertEquals(2, requests.size)
        assertEquals(2, ledger.snapshot().ttsVendorRequests)
        assertEquals(4, ledger.snapshot().ttsUtf16Characters)
    }

    @Test fun `invalid credentials and oversized payload never reserve`() = runBlocking {
        val ledger = testLedger()
        val missing = NovelAudioServerClient(
            "http://127.0.0.1:${server.listeningPort}", { "" }, budgetLedger = ledger
        )
        expectError("CONFIG") { missing.synthesize(SynthesisRequest("原文", "M017")) }
        val valid = budgetedClient(ledger)
        expectError("CONFIG") {
            valid.analyze(request().copy(units = listOf(AnalysisUnit("u1", "字".repeat(750_000)))))
        }
        assertEquals(0, ledger.snapshot().analysisRequests)
        assertEquals(0, ledger.snapshot().ttsVendorRequests)
        assertTrue(requests.isEmpty())
    }

    @Test fun `bridge local budget rejection stops subsequent generation`() = runBlocking {
        val ledger = testLedger()
        val budgeted = budgetedClient(ledger)
        status = 429
        payload = """{"error":{"code":"local_trial_limit"}}"""
        expectError("LOCAL_BUDGET_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("原文", "M017"))
        }
        expectError("LOCAL_BUDGET_EXHAUSTED") { budgeted.analyze(request()) }
        assertEquals(1, requests.size)
    }

    @Test fun `ordinary rate limit remains transient and not cloud quota blocked`() = runBlocking {
        val ledger = testLedger()
        val budgeted = budgetedClient(ledger)
        status = 429
        payload = """{"error":{"code":"cloud_rate_limit"}}"""
        repeat(2) {
            expectError("RATE_LIMIT") { budgeted.synthesize(SynthesisRequest("原文", "M017")) }
        }
        assertEquals(2, requests.size)
        assertFalse(ledger.snapshot().cloudQuotaBlocked)
    }

    @Test fun `second client is rejected while a generation is in flight`() = runBlocking {
        val ledger = testLedger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = {
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        mime = "audio/ogg"
        profile = "mock-v1"
        payload = "OggS-test-fixture"
        val first = async { budgetedClient(ledger).synthesize(SynthesisRequest("原文", "M017")) }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            expectError("CONCURRENCY_LIMIT") { budgetedClient(ledger).analyze(request()) }
            assertEquals(1, requests.size)
        } finally {
            release.countDown()
        }
        first.await()
        Unit
    }

    @Test fun `cancelling generation never refunds its persistent reservation`() = runBlocking {
        val ledger = testLedger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = {
            started.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val call = async { budgetedClient(ledger).synthesize(SynthesisRequest("原文", "M017")) }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            call.cancel()
            call.join()
            assertEquals(1, ledger.snapshot().ttsVendorRequests)
            assertEquals(2, ledger.snapshot().ttsUtf16Characters)
        } finally {
            release.countDown()
        }
    }

    @Test fun `tts vendor request split uses code points and preview shares its budget`() = runBlocking {
        val ledger = testLedger(ttsRequests = 2)
        val budgeted = budgetedClient(ledger)
        mime = "audio/ogg"
        profile = "mock-v1"
        payload = "OggS-test-fixture"
        budgeted.preview(SynthesisRequest("字".repeat(601), "M017"))
        assertEquals(2, ledger.snapshot().ttsVendorRequests)
        expectError("LOCAL_BUDGET_EXHAUSTED") {
            budgeted.synthesize(SynthesisRequest("字", "M017"))
        }
        assertEquals(1, requests.size)
    }

    private fun testLedger(ttsRequests: Int = 100) = NovelAudioBudgetLedger(
        budgetFiles.newFolder().resolve("budget.json"),
        NovelAudioBudgetLedger.Limits(ttsVendorRequests = ttsRequests)
    )

    private fun budgetedClient(ledger: NovelAudioBudgetLedger) = NovelAudioServerClient(
        "http://127.0.0.1:${server.listeningPort}/prefix",
        { "test-secret" },
        budgetLedger = ledger
    )

    @Test fun `redirect never forwards credentials`() = runBlocking {
        status = 302
        expectError("HTTP") { client.health() }
        assertEquals(1, requests.size)
    }

    @Test fun `missing runtime endpoint is reported as unsupported`() = runBlocking {
        status = 404
        expectError("UNSUPPORTED") {
            client.acquireRuntime("session-1", "auto_prefetch", 3)
        }
        assertEquals(1, requests.size)
    }

    @Test fun `503 retry after zero never replays GET or synthesis`() = runBlocking {
        status = 503
        retryAfter = "0"
        expectError("UNAVAILABLE") { client.health() }
        assertEquals(1, requests.size)
        expectError("UNAVAILABLE") { client.synthesize(SynthesisRequest("原文", "M017")) }
        assertEquals(2, requests.size)
    }

    @Test fun `oversized retry after cannot escape on worker thread`() = runBlocking {
        status = 503
        retryAfter = "2147483648"
        val uncaught = java.util.concurrent.CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> uncaught.countDown() }
        try {
            expectError("UNAVAILABLE") { client.health() }
            assertFalse("worker must not throw", uncaught.await(500, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(1, requests.size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun `invalid server urls are rejected without revealing them`() {
        for (url in listOf("file:///tmp", "https://user:secret@host", "https://host?q=secret",
            "https://host/#secret", "http://host/v1")) {
            try {
                NovelAudioServerClient(url, { "test-secret" })
                fail("invalid configuration accepted")
            } catch (e: NovelAudioServerException) {
                assertEquals("CONFIG", e.kind)
                assertFalse(e.toString().contains("secret"))
            }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `analyze preserves text and accepts known narrator and temporary speakers`() = runBlocking {
        payload = """{"assignments":[{"unitId":"u1","speakerId":"narrator"},{"unitId":"u2","speakerId":"tmp_1"}],
            "newCharacters":[{"temporaryId":"tmp_1","displayName":"萧炎","gender":"male","ageRange":"young",
            "voicePersona":{"traits":["清朗"]}}],"aliasUpdates":[{"characterId":"tmp_1","stableAliases":["炎儿"]}]}"""
        val result = client.analyze(request())
        assertEquals("tmp_1", result.assignments[1].speakerId)
        val json = JsonParser.parseString(requests.single().third).asJsonObject
        assertEquals("  原文😀\n", json["units"].asJsonArray[0].asJsonObject["text"].asString)
        assertEquals("hash", json["textHash"].asString)
        assertEquals("/prefix/v1/chapter/analyze", requests.single().first)
    }

    @Test fun `analyze rejects unknown duplicate or missing units and foreign speakers`() = runBlocking {
        for (assignments in listOf(
            """[{"unitId":"u1","speakerId":"narrator"}]""",
            """[{"unitId":"u1","speakerId":"narrator"},{"unitId":"u1","speakerId":"narrator"}]""",
            """[{"unitId":"u1","speakerId":"narrator"},{"unitId":"unknown","speakerId":"narrator"}]""",
            """[{"unitId":"u1","speakerId":"narrator"},{"unitId":"u2","speakerId":"foreign"}]"""
        )) {
            payload = """{"assignments":$assignments,"newCharacters":[],"aliasUpdates":[]}"""
            expectError("PROTOCOL") { client.analyze(request()) }
        }
    }

    @Test fun `voices and match use typed metadata envelopes`() = runBlocking {
        val voice = """{"voiceAssetId":"M017","displayName":"青年","gender":"male","ageRange":"young",
            "traits":["清朗"],"previewAvailable":true}"""
        payload = """{"voices":[$voice]}"""
        assertEquals("M017", client.voices().single().voiceAssetId)
        payload = """{"candidates":[$voice]}"""
        assertEquals("M017", client.match(VoiceMatchRequest(alreadyUsedVoiceIds = listOf("M041")))
            .single().voiceAssetId)
        assertEquals("/prefix/v1/voices/match", requests.last().first)
        assertTrue(requests.last().third.contains("M041"))
    }

    @Test fun `audio requires compressed media nonempty bytes and actual profile`() = runBlocking {
        mime = "audio/ogg"
        payload = "OggS-test-fixture"
        profile = "mock-v1"
        val audio = client.synthesize(SynthesisRequest("原文", "M017"))
        assertEquals("mock-v1", audio.ttsProfile)
        assertEquals("audio/ogg", audio.contentType)
        assertArrayEquals(payload.toByteArray(), audio.bytes)
        assertEquals("/prefix/v1/tts/synthesize", requests.single().first)
        client.preview(SynthesisRequest("试听", "M017"))
        assertEquals("/prefix/v1/voices/preview", requests.last().first)
        profile = null
        expectError("PROTOCOL") { client.synthesize(SynthesisRequest("原文", "M017")) }
        profile = "mock-v1"
        mime = "application/json"
        expectError("PROTOCOL") { client.synthesize(SynthesisRequest("原文", "M017")) }
        mime = "audio/ogg"
        payload = ""
        expectError("PROTOCOL") { client.synthesize(SynthesisRequest("原文", "M017")) }
        Unit
    }

    @Test fun `invalid synthesis and empty token do not reach network`() = runBlocking {
        expectError("CONFIG") { client.synthesize(SynthesisRequest("原文", "M017", speed = Double.NaN)) }
        expectError("CONFIG") { client.synthesize(SynthesisRequest("原文", "")) }
        expectError("CONFIG") { client.synthesize(SynthesisRequest("字".repeat(1201), "M017")) }
        val missing = NovelAudioServerClient("http://127.0.0.1:${server.listeningPort}", { "" })
        expectError("CONFIG") { missing.health() }
        assertTrue(requests.isEmpty())
    }

    @Test fun `malformed and oversized JSON are bounded failures`() = runBlocking {
        payload = "{broken"
        expectError("PROTOCOL") { client.health() }
        payload = " ".repeat(2 * 1024 * 1024 + 1)
        expectError("PROTOCOL") { client.health() }
        Unit
    }

    @Test fun `oversized audio is rejected with fixed and chunked lengths`() = runBlocking {
        mime = "audio/ogg"
        profile = "mock-v1"
        rawPayload = ByteArray(16 * 1024 * 1024 + 1) { 1 }
        for (streamed in listOf(false, true)) {
            chunked = streamed
            expectError("PROTOCOL") { client.synthesize(SynthesisRequest("原文", "M017")) }
        }
    }

    @Test fun `oversized analysis request is rejected before network`() = runBlocking {
        val oversized = request().copy(units = listOf(AnalysisUnit("u1", "字".repeat(750_000))))
        expectError("CONFIG") { client.analyze(oversized) }
        assertTrue(requests.isEmpty())
    }

    @Test fun `voice metadata requires unique IDs and typed arrays`() = runBlocking {
        val voice = """{"voiceAssetId":"M017","displayName":"青年","gender":"male","ageRange":"young",
            "traits":[],"previewAvailable":true}"""
        for (body in listOf("""{"voices":[$voice,$voice]}""", """{"voices":[null]}""",
            """{"voices":[$voice]}""".replace("\"traits\":[]", "\"traits\":true"))) {
            payload = body
            expectError("PROTOCOL") { client.voices() }
        }
    }

    @Test fun `analyze accepts a character already scoped to this book`() = runBlocking {
        payload = """{"assignments":[{"unitId":"u1","speakerId":"narrator"},{"unitId":"u2","speakerId":"char-1"}],
            "newCharacters":[],"aliasUpdates":[{"characterId":"char-1","stableAliases":["小明"]}]}"""
        val known = request().copy(characters = listOf(AnalysisCharacter("char-1", "王明")))
        assertEquals("char-1", client.analyze(known).assignments[1].speakerId)
    }

    @Test fun `invalid UTF8 is rejected before identity decoding`() = runBlocking {
        rawPayload = """{"voices":[{"voiceAssetId":"""".toByteArray() +
            byteArrayOf(0xff.toByte()) +
            """","displayName":"青年","gender":"male","ageRange":"young","traits":[],"previewAvailable":true}]}"""
                .toByteArray()
        expectError("PROTOCOL") { client.voices() }
        Unit
    }

    @Test fun `duplicate JSON fields at root or nested identity are rejected`() = runBlocking {
        payload = """{"status":"ok","apiVersion":"2","apiVersion":"1","directorReady":true,"ttsReady":true}"""
        expectError("PROTOCOL") { client.health() }
        payload = """{"assignments":[{"unitId":"u1","speakerId":"foreign","speakerId":"narrator"},
            {"unitId":"u2","speakerId":"narrator"}],"newCharacters":[],"aliasUpdates":[]}"""
        expectError("PROTOCOL") { client.analyze(request()) }
        Unit
    }

    @Test fun `token provider cancellation is not a configuration error`() = runBlocking {
        val cancelled = NovelAudioServerClient("http://127.0.0.1:${server.listeningPort}", {
            throw CancellationException("cancel-token-read")
        })
        try {
            cancelled.health()
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(requests.isEmpty())
        }
    }

    @Test fun `caller deadline is not converted into server timeout`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = { release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            // 外层超时只能被外层识别；若客户端误转 TIMEOUT，这里会抛业务异常。
            assertNull(withTimeoutOrNull(100) { client.health() })
        } finally {
            release.countDown()
        }
    }

    @Test fun `total timeout includes dispatcher queue and never sends expired work`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(5)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = {
            started.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val blockers = List(5) { async { client.health() } }
        try {
            withTimeout(3000) {
                while (started.count > 0) kotlinx.coroutines.delay(10)
            }
            val impatient = NovelAudioServerClient(
                "http://127.0.0.1:${server.listeningPort}", { "test-secret" }, timeoutLimitMillis = 100
            )
            val timedOut = withTimeoutOrNull(1000) {
                expectError("TIMEOUT") { impatient.health() }
            }
            assertNotNull("queued call must meet its own total deadline", timedOut)
            assertEquals(5, requests.size)
        } finally {
            release.countDown()
            blockers.forEach { it.cancel() }
            blockers.forEach { it.join() }
        }
    }

    @Test fun `analysis budget outlasts bridge worker deadline`() = runBlocking {
        // 百炼非流式分析真机实测 15～40 秒以上，桥接 worker 硬期限 90 秒；
        // 手机必须更晚超时，才能收到桥接返回的固定错误码而不是自己先报 TIMEOUT。
        assertTrue(NovelAudioServerClient.ANALYSIS_TIMEOUT_MILLIS > 90_000)
        assertTrue(
            NovelAudioServerClient.ANALYSIS_TIMEOUT_MILLIS <= NovelAudioServerClient.MAX_TIMEOUT_MILLIS
        )
        val url = "http://127.0.0.1:${server.listeningPort}"
        NovelAudioServerClient(url, { "test-secret" }, NovelAudioServerClient.ANALYSIS_TIMEOUT_MILLIS)
        expectError("CONFIG") {
            NovelAudioServerClient(url, { "test-secret" }, NovelAudioServerClient.MAX_TIMEOUT_MILLIS + 1)
        }
        Unit
    }

    @Test fun `cancellation propagates instead of becoming unavailable`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        beforeResponse = {
            started.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        val call = async { client.health() }
        try {
            withTimeout(3000) {
                while (started.count > 0) kotlinx.coroutines.delay(10)
            }
            call.cancel()
            try {
                call.await()
                fail("cancellation expected")
            } catch (_: CancellationException) {
                assertTrue(call.isCancelled)
            }
        } finally {
            release.countDown()
        }
    }

    private fun request() = ChapterAnalysisRequest(
        bookId = "book", chapterId = "chapter", textHash = "hash",
        units = listOf(AnalysisUnit("u1", "  原文😀\n"), AnalysisUnit("u2", "“你好。”"))
    )

    private suspend fun expectError(kind: String, action: suspend () -> Any): NovelAudioServerException {
        try {
            action()
        } catch (e: NovelAudioServerException) {
            assertEquals(kind, e.kind)
            return e
        }
        throw AssertionError("expected $kind")
    }
}
