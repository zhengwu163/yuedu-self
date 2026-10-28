package io.legado.app.help.readaloud.server

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** 整本生成接口的 wire 契约；用真实 loopback HTTP，对照电脑端 book_jobs_api.py 的字段。 */
class NovelAudioBookJobClientTest {
    private data class Reply(val status: Int, val body: String, val mime: String = "application/json",
                             val profile: String? = null, val raw: ByteArray? = null)

    private lateinit var server: NanoHTTPD
    private lateinit var client: NovelAudioServerClient
    private val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
    private var reply = Reply(200, """{"state":"running"}""")

    @Before fun setup() {
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val length = session.headers["content-length"]?.toIntOrNull() ?: 0
                val body = ByteArray(length).also { var read = 0
                    while (read < length) read += session.inputStream.read(it, read, length - read) }
                requests += Triple(session.method.name, session.uri, String(body, Charsets.UTF_8))
                val bytes = reply.raw ?: reply.body.toByteArray(Charsets.UTF_8)
                return newFixedLengthResponse(Response.Status.lookup(reply.status), reply.mime,
                    bytes.inputStream(), bytes.size.toLong()).apply {
                    reply.profile?.let { addHeader("X-TTS-Profile", it) }
                }
            }
        }
        server.start()
        client = NovelAudioServerClient("http://127.0.0.1:${server.listeningPort}", { "test-secret" })
    }

    @After fun teardown() = server.stop()

    @Test fun createSendsThePhoneTable() = runBlocking {
        reply = Reply(200, """{"jobId":"$JOB","maxSegmentChars":50,"state":"uploading"}""")
        val created = client.createBookJob(BookJobCreateRequest(
            bookId = "work", title = "哑钟", expectedChapterCount = 3,
            characters = listOf(BookJobCharacter("1", "单牧", "male")),
            aliases = listOf(BookJobAlias("1", "老单", "老单")),
            merges = emptyList(),
            bindings = listOf(BookJobBinding("0", "N1", 1))))
        assertEquals(BookJobCreated(JOB, 50, "uploading"), created)
        val (method, uri, body) = requests.single()
        assertEquals("POST" to "/v1/book-jobs", method to uri)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals("单牧", json["characters"].asJsonArray[0].asJsonObject["displayName"].asString)
        assertEquals(1, json["bindings"].asJsonArray[0].asJsonObject["revision"].asInt)
    }

    @Test fun chapterUploadUsesPutAndReportsUnchanged() = runBlocking {
        reply = Reply(200, """{"state":"unchanged"}""")
        val changed = client.uploadBookJobChapter(JOB, CHAPTER, BookJobChapterUpload(
            0, "hash", listOf(BookJobUnit("u1", "天黑了。", "narrator", listOf("天黑了。")))))
        assertFalse(changed)
        assertEquals("PUT" to "/v1/book-jobs/$JOB/chapters/$CHAPTER",
            requests.single().first to requests.single().second)
    }

    @Test fun controlAndDeleteHitFixedRoutes() = runBlocking {
        reply = Reply(200, """{"state":"paused"}""")
        assertEquals("paused", client.controlBookJob(JOB, "pause"))
        assertEquals("paused", client.markBookJobUploaded(JOB))
        reply = Reply(200, """{"state":"deleted"}""")
        client.deleteBookJob(JOB)
        assertEquals(
            listOf("POST /v1/book-jobs/$JOB/pause", "POST /v1/book-jobs/$JOB/uploaded",
                "DELETE /v1/book-jobs/$JOB"),
            requests.map { "${it.first} ${it.second}" })
        expectKind("CONFIG") { client.controlBookJob(JOB, "drop") }
        expectKind("CONFIG") { client.bookJob("../runtime/acquire") }
        expectKind("CONFIG") { client.bookJobChapter(JOB, "a/b") }
    }

    @Test fun detailParsesNullableFields() = runBlocking {
        reply = Reply(200, """{"jobId":"$JOB","bookId":"work","title":"t","state":"running",
            "uploaded":true,"expectedChapterCount":2,"maxSegmentChars":50,
            "counts":{"pending":1,"analyzed":0,"done":1,"failed":0,"purged":0},
            "recentChapterSeconds":null,"chapters":[
            {"chapterId":"$CHAPTER","chapterIndex":0,"snapshotHash":"h","state":"done","failure":null,"downloaded":false},
            {"chapterId":"c2","chapterIndex":1,"snapshotHash":"h2","state":"failed","failure":"analysis_failed","downloaded":false}]}""")
        val detail = client.bookJob(JOB)
        assertNull(detail.recentChapterSeconds)
        assertEquals(listOf(null, "analysis_failed"), detail.chapters.map { it.failure })
    }

    @Test fun chapterResultRequiresContiguousPiecesAndHashes() = runBlocking {
        reply = Reply(200, chapterResult(pieceIndex = 0))
        val result = client.bookJobChapter(JOB, CHAPTER)
        assertEquals(listOf(0L, 2L), result.units.map { it.speakerId })
        assertEquals("", result.characters.single().gender)
        assertFalse(result.characters.single().phoneKnown)
        reply = Reply(200, chapterResult(pieceIndex = 1))
        expectKind("PROTOCOL") { client.bookJobChapter(JOB, CHAPTER) }
    }

    @Test fun audioRequiresProfileAndAudioType() = runBlocking {
        reply = Reply(200, "", mime = "audio/ogg", profile = "local-qwen", raw = byteArrayOf(1, 2, 3))
        val audio = client.bookJobAudio(JOB, AUDIO)
        assertEquals("local-qwen" to 3, audio.ttsProfile to audio.bytes.size)
        assertEquals("GET /v1/book-jobs/$JOB/audio/$AUDIO", requests.last().let { "${it.first} ${it.second}" })
        reply = Reply(200, "", mime = "audio/ogg", raw = byteArrayOf(1))
        expectKind("PROTOCOL") { client.bookJobAudio(JOB, AUDIO) }
    }

    @Test fun serverErrorCodesMapToFixedKinds() = runBlocking {
        for ((status, code, kind) in listOf(
            Triple(429, "book_job_running", "BOOK_JOB_RUNNING"),
            Triple(409, "chapter_not_ready", "NOT_READY"),
            Triple(409, "job_closed", "JOB_CLOSED"),
            Triple(404, "unknown_job", "UNKNOWN_JOB"),
        )) {
            reply = Reply(status, """{"error":{"code":"$code"}}""")
            expectKind(kind) { client.markBookJobChapterDownloaded(JOB, CHAPTER) }
        }
    }

    private suspend fun expectKind(kind: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $kind")
        } catch (error: NovelAudioServerException) {
            assertEquals(kind, error.kind)
        }
    }

    private fun chapterResult(pieceIndex: Int) = """{"chapterId":"$CHAPTER","chapterIndex":0,
        "snapshotHash":"h","units":[
        {"unitId":"n0","speakerId":"0","pieces":[]},
        {"unitId":"d1","speakerId":"2","pieces":[{"index":$pieceIndex,"textSha256":"${"a".repeat(64)}",
         "audioId":"$AUDIO","sha256":"${"b".repeat(64)}","size":3,"contentType":"audio/ogg",
         "ttsProfile":"local-qwen"}]}],
        "characters":[{"characterId":"2","displayName":"曾晚","gender":"","phoneKnown":false,
         "stableAliases":["曾晚"]}],
        "bindings":[{"characterId":"0","voiceAssetId":"N1","revision":1},
         {"characterId":"2","voiceAssetId":"F1","revision":1}]}"""

    private companion object {
        const val JOB = "job-0123456789abcdef01234567"
        const val CHAPTER = "c0123"
        const val AUDIO = "0123456789abcdef0123456789abcdef"
    }
}
