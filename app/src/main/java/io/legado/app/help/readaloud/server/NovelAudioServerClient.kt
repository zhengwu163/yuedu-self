package io.legado.app.help.readaloud.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.Strictness
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.readaloud.novel.NovelAudioBudgetLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 只保留固定错误类型，不携带远端 body、URL、Token 或底层异常消息。 */
class NovelAudioServerException(val kind: String) : NoStackTraceException(
    when (kind) {
        "CONFIG" -> "请检查 AI 音频服务器地址、访问令牌及请求参数"
        "AUTH" -> "家庭 AI 服务鉴权失败，请检查访问令牌"
        "RATE_LIMIT" -> "家庭 AI 服务繁忙，请稍后重试"
        "CONCURRENCY_LIMIT" -> "家庭 AI 服务正在生成其他内容，请稍后重试"
        "INSUFFICIENT_RESOURCES" -> "家庭 AI 电脑显卡或内存不足，请关闭占用显卡的程序后重试"
        "INVALID_LEASE" -> "家庭 AI 服务会话已失效，请重新开始生成"
        "UNSUPPORTED" -> "当前 AI 服务不支持运行时模型会话"
        "LOCAL_BUDGET_EXHAUSTED" -> "AI 听书本地试用额度已用尽"
        "LOCAL_BUDGET_UNAVAILABLE" -> "AI 听书本地额度状态不可用，已停止生成"
        "FREE_QUOTA_EXHAUSTED" -> "AI 听书免费云额度已用尽，已停止生成"
        "TIMEOUT" -> "家庭 AI 服务请求超时，可重试或切回普通朗读"
        "UNAVAILABLE" -> "家庭 AI 服务不可用，可重试或切回普通朗读"
        "PROTOCOL" -> "家庭 AI 服务响应不符合 v1 协议"
        "STORAGE" -> "无法读取或保存 AI 音频服务配置，请重新配置后再试"
        else -> "家庭 AI 服务请求失败"
    }
)

/** 仅传输与契约校验；不写角色/缓存，不重试，不选择备用模型。 */
class NovelAudioServerClient(
    baseUrl: String,
    private val tokenProvider: () -> String,
    private val timeoutLimitMillis: Long = MAX_TIMEOUT_MILLIS,
    private val budgetLedger: NovelAudioBudgetLedger? = null
) {
    private val base = NovelAudioServerCredentials.parseBaseUrl(baseUrl)
    private val selfHostedLeases: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        if (timeoutLimitMillis !in 1..MAX_TIMEOUT_MILLIS) throw NovelAudioServerException("CONFIG")
    }

    suspend fun health(): ServerHealth = withContext(Dispatchers.IO) {
        NovelAudioJson.health(json("health", null, 10_000, null, "GET"))
    }

    suspend fun analyze(request: ChapterAnalysisRequest): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        analyzeInternal(request, null)
    }

    /** 同一批次的分析请求共享一个服务端 runtime lease。 */
    suspend fun analyze(
        request: ChapterAnalysisRequest,
        leaseId: String
    ): ChapterAnalysisResponse = withContext(Dispatchers.IO) {
        analyzeInternal(request, leaseId)
    }

    private suspend fun analyzeInternal(
        request: ChapterAnalysisRequest,
        leaseId: String?
    ): ChapterAnalysisResponse {
        checkConfig(listOf(request.bookId, request.chapterId, request.textHash, request.analysisVersion)
            .all { it.isNotBlank() })
        checkConfig(request.units.isNotEmpty() &&
            request.units.all { it.unitId.isNotBlank() && it.text.isNotBlank() } &&
            request.units.map { it.unitId }.distinct().size == request.units.size)
        checkConfig(request.characters.all { it.characterId.isNotBlank() &&
            it.characterId != "narrator" && it.displayName.isNotBlank() } &&
            request.characters.map { it.characterId }.distinct().size == request.characters.size)
        // 先做纯本地校验：非法凭据或超限负载不得消耗不可退款的本地额度。
        val body = encodePayload(request)
        leaseId?.let(::checkLease)
        peekToken()
        if (isSelfHosted(leaseId)) {
            val slot = singleFlightSlot()
            return try {
                NovelAudioJson.analysis(
                    json("chapter/analyze", body, ANALYSIS_TIMEOUT_MILLIS, leaseId, "POST"),
                    request
                )
            } finally {
                slot.close()
            }
        }
        val ledger = budgetLedger ?: throw NovelAudioServerException("LOCAL_BUDGET_UNAVAILABLE")
        val reservation = ledger.reserve(
            kind = NovelAudioBudgetLedger.Kind.ANALYSIS,
            utf16Characters = request.units.sumOf { it.text.length }
        )
        return try {
            NovelAudioJson.analysis(
                json("chapter/analyze", body, ANALYSIS_TIMEOUT_MILLIS, leaseId, "POST"),
                request
            )
        } finally {
            reservation.close()
        }
    }

    suspend fun voices(): List<VoiceAsset> = withContext(Dispatchers.IO) {
        NovelAudioJson.voices(json("voices", null, 15_000, null, "GET"), "voices")
    }

    suspend fun match(request: VoiceMatchRequest): List<VoiceAsset> = withContext(Dispatchers.IO) {
        NovelAudioJson.voices(
            json("voices/match", encodePayload(request), 15_000, null, "POST"),
            "candidates"
        )
    }

    suspend fun preview(request: SynthesisRequest): SynthesizedAudio =
        audio("voices/preview", request, null)

    suspend fun preview(request: SynthesisRequest, leaseId: String): SynthesizedAudio =
        audio("voices/preview", request, leaseId)

    suspend fun synthesize(request: SynthesisRequest): SynthesizedAudio =
        audio("tts/synthesize", request, null)

    suspend fun synthesize(request: SynthesisRequest, leaseId: String): SynthesizedAudio =
        audio("tts/synthesize", request, leaseId)

    suspend fun acquireRuntime(
        sessionId: String,
        purpose: String,
        expectedChapterCount: Int
    ): RuntimeLease = withContext(Dispatchers.IO) {
        checkConfig(
            sessionId.isNotBlank() &&
                purpose.isNotBlank() &&
                expectedChapterCount in 1..10_000
        )
        NovelAudioJson.runtimeLease(
            json(
                "runtime/acquire",
                encodePayload(RuntimeAcquireRequest(sessionId, purpose, expectedChapterCount)),
                45_000,
                null,
                "POST"
            )
        ).also { lease ->
            if (lease.selfHosted) selfHostedLeases.add(lease.leaseId)
        }
    }

    // A cancelled preparation must still release the server lease; the HTTP deadline remains 15s.
    suspend fun releaseRuntime(leaseId: String) = withContext(NonCancellable + Dispatchers.IO) {
        checkLease(leaseId)
        try {
            NovelAudioJson.runtimeRelease(
                json("runtime/release", ByteArray(0), 15_000, leaseId, "POST")
            )
        } finally {
            // 释放失败时租约也不再被本端使用，豁免标记不能残留到后续请求。
            selfHostedLeases.remove(leaseId)
        }
        Unit
    }

    /**
     * 自托管服务（用户自己的电脑）声明的租约不消耗云端试用额度；
     * 无租约或云端桥接的请求仍按额度预占。
     */
    private fun isSelfHosted(leaseId: String?): Boolean =
        leaseId != null && leaseId in selfHostedLeases

    /** 豁免额度但不豁免单飞：家庭电脑一次只生成一段，并发会被服务端拒为 busy。 */
    private fun singleFlightSlot(): NovelAudioBudgetLedger.Reservation =
        (budgetLedger ?: throw NovelAudioServerException("LOCAL_BUDGET_UNAVAILABLE")).acquireSlot()

    suspend fun runtimeStatus(): RuntimeStatus = withContext(Dispatchers.IO) {
        NovelAudioJson.runtimeStatus(
            json("runtime/status", null, 15_000, null, "GET")
        )
    }

    private suspend fun audio(
        path: String,
        request: SynthesisRequest,
        leaseId: String?
    ): SynthesizedAudio =
        withContext(Dispatchers.IO) {
            checkConfig(request.text.isNotBlank() && request.text.length <= 1200 &&
                request.voiceAssetId.isNotBlank() && request.language.isNotBlank() &&
                request.speed.isFinite() && request.speed > 0)
            // 先做纯本地校验：非法凭据或超限负载不得消耗不可退款的本地额度。
            val body = encodePayload(request)
            leaseId?.let(::checkLease)
            peekToken()
            if (isSelfHosted(leaseId)) {
                val slot = singleFlightSlot()
                return@withContext try {
                    val result = exchange(path, body, TTS_TIMEOUT_MILLIS, true, leaseId, "POST")
                    SynthesizedAudio(result.bytes, result.type, result.profile)
                } finally {
                    slot.close()
                }
            }
            val ledger = budgetLedger ?: throw NovelAudioServerException("LOCAL_BUDGET_UNAVAILABLE")
            val reservation = ledger.reserve(
                kind = NovelAudioBudgetLedger.Kind.TTS,
                utf16Characters = request.text.length,
                // 桥接按 Unicode code point 每 600 拆一次供应商请求，不能用 UTF-16 长度。
                vendorRequests = (request.text.codePointCount(0, request.text.length) + 599) / 600
            )
            try {
                val result = exchange(path, body, TTS_TIMEOUT_MILLIS, true, leaseId, "POST")
                SynthesizedAudio(result.bytes, result.type, result.profile)
            } finally {
                reservation.close()
            }
        }

    /** 序列化并在预占之前执行请求体上限校验。 */
    private fun encodePayload(payload: Any?): ByteArray? {
        if (payload == null) return null
        val bytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
        checkConfig(bytes.size <= JSON_LIMIT)
        return bytes
    }

    /** 预占之前先确认令牌可用；取消原样传播，不当作配置错误。 */
    private fun peekToken() {
        val token = kotlin.runCatching(tokenProvider).getOrElse {
            if (it is CancellationException) throw it
            throw NovelAudioServerException("CONFIG")
        }
        NovelAudioServerCredentials.validateToken(token)
    }

    private suspend fun json(
        path: String,
        body: ByteArray?,
        timeout: Long,
        leaseId: String?,
        method: String
    ): String {
        val bytes = exchange(path, body, timeout, false, leaseId, method).bytes
        return try {
            // 身份字段不允许由替换字符“修好”：编码损坏应拒绝整份响应。
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            throw NovelAudioServerException("PROTOCOL")
        }
    }

    private class Body(val bytes: ByteArray, val type: String, val profile: String)
    private class HttpStatusFailure(val kind: String) : IOException()

    private suspend fun exchange(
        path: String,
        body: ByteArray?,
        timeout: Long,
        audio: Boolean,
        leaseId: String?,
        method: String
    ): Body {
        return try {
            // OkHttp 的异步 callTimeout 从出队才计时，额外覆盖排队，过期即取消 call。
            // withTimeoutOrNull 只转换自己的超时；调用方的取消/更短 deadline 原样向外传播。
            withTimeoutOrNull(minOf(timeout, timeoutLimitMillis)) {
                exchangeWithinDeadline(path, body, timeout, audio, leaseId, method)
            } ?: throw NovelAudioServerException("TIMEOUT")
        } catch (error: NovelAudioServerException) {
            // 额度耗尽是确定性失败：持久熔断，避免后续请求再触网。
            when (error.kind) {
                "FREE_QUOTA_EXHAUSTED" -> budgetLedger?.blockCloudQuota()
                "LOCAL_BUDGET_EXHAUSTED" -> budgetLedger?.blockLocalBudget()
            }
            throw error
        }
    }

    private suspend fun exchangeWithinDeadline(
        path: String,
        body: ByteArray?,
        timeout: Long,
        audio: Boolean,
        leaseId: String?,
        method: String
    ): Body {
        currentCoroutineContext().ensureActive()
        val token = kotlin.runCatching(tokenProvider).getOrElse {
            if (it is CancellationException) throw it
            throw NovelAudioServerException("CONFIG")
        }
        currentCoroutineContext().ensureActive()
        NovelAudioServerCredentials.validateToken(token)
        val url = base.newBuilder()
            .encodedPath(base.encodedPath.trimEnd('/') + "/v1/" + path)
            .build()
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", if (audio) "audio/ogg, audio/mp4, audio/aac" else "application/json")
            // 显式 identity：网络拦截器位于 OkHttp 透明 gzip 解码之下，
            // 若允许 gzip 则错误 body 只能读到压缩字节，额度错误码无法识别。
            .header("Accept-Encoding", "identity")
        leaseId?.let {
            checkLease(it)
            builder.header("X-NovelAudio-Lease", it)
        }
        if (body != null) {
            checkConfig(body.size <= JSON_LIMIT)
            builder.method(
                method,
                body.toRequestBody("application/json; charset=utf-8".toMediaType())
            )
        } else {
            builder.method(method, null)
        }
        val call = http.newCall(builder.build())
        call.timeout().timeout(minOf(timeout, timeoutLimitMillis), TimeUnit.MILLISECONDS)
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(NovelAudioServerException(
                            when (e) {
                                is HttpStatusFailure -> e.kind
                                is InterruptedIOException -> "TIMEOUT"
                                else -> "UNAVAILABLE"
                            }))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // 在 OkHttp 工作线程内读完并关闭 body；取消后也不会泄漏 Response。
                        val result = kotlin.runCatching {
                            response.use {
                                if (!continuation.isActive) throw CancellationException()
                                val type = it.body.contentType()?.let { media -> "${media.type}/${media.subtype}" }.orEmpty()
                                val profile = it.header("X-TTS-Profile").orEmpty()
                                if (audio) {
                                    if (type !in setOf("audio/ogg", "audio/mp4", "audio/aac") ||
                                        profile.isBlank() || profile.length > 256) {
                                        throw NovelAudioServerException("PROTOCOL")
                                    }
                                } else if (type != "application/json") {
                                    throw NovelAudioServerException("PROTOCOL")
                                }
                                val limit = if (audio) AUDIO_LIMIT else JSON_LIMIT
                                if (it.body.contentLength() > limit) throw NovelAudioServerException("PROTOCOL")
                                val buffer = Buffer()
                                val source = it.body.source()
                                while (buffer.size <= limit) {
                                    if (!continuation.isActive) throw CancellationException()
                                    if (source.read(buffer, minOf(8192L, limit + 1L - buffer.size)) == -1L) break
                                }
                                if (buffer.size == 0L || buffer.size > limit) throw NovelAudioServerException("PROTOCOL")
                                Body(buffer.readByteArray(), type, profile)
                            }
                        }
                        result.fold(
                            onSuccess = { continuation.resume(it) },
                            onFailure = {
                                continuation.resumeWithException(when (it) {
                                    is CancellationException, is NovelAudioServerException -> it
                                    is InterruptedIOException -> NovelAudioServerException("TIMEOUT")
                                    is IOException -> NovelAudioServerException("UNAVAILABLE")
                                    else -> NovelAudioServerException("PROTOCOL")
                                })
                            }
                        )
                    }
                })
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private fun checkConfig(valid: Boolean) {
        if (!valid) throw NovelAudioServerException("CONFIG")
    }

    private fun checkLease(leaseId: String) {
        checkConfig(
            leaseId.isNotBlank() &&
                leaseId.length <= 256 &&
                leaseId.all { it.code in 33..126 }
        )
    }

    companion object {
        /**
         * 分析是非流式补全，整份结果生成完才返回首字节；真机 64 单元批次实测 15～40 秒以上。
         * 必须长于桥接 worker 的 90 秒硬期限，手机才能收到桥接的固定错误码。
         */
        internal const val ANALYSIS_TIMEOUT_MILLIS = 100_000L
        /** 单次调用总时长上限；其余接口仍按各自更短的期限调用。 */
        internal const val MAX_TIMEOUT_MILLIS = ANALYSIS_TIMEOUT_MILLIS
        /**
         * 单次合成/试听。家庭电脑本地 TTS 约 1.6 倍实时，冷启动的 50 字段实测近 30 秒，
         * 留出余量避免把正在生成的请求判超时后重发。
         */
        internal const val TTS_TIMEOUT_MILLIS = 60_000L
        private const val JSON_LIMIT = 2 * 1024 * 1024
        private const val AUDIO_LIMIT = 16 * 1024 * 1024
        private const val ERROR_BODY_LIMIT = 4096L
        private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
        // 凭据通道不继承书源的宽松 TLS、诊断拦截器、cookie 或重定向逻辑。
        // 读/总超时放到分析上限，逐次调用再用 call.timeout() 收紧到各自期限。
        private val http by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(MAX_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .callTimeout(MAX_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    // retryOnConnectionFailure(false) 不阻止 503/Retry-After:0 的 HTTP 重发。
                    // 在 follow-up 拦截器之前关闭错误响应，也避免其解析畸形 Retry-After。
                    if (response.code != 200) {
                        // 只读有界前缀用于识别固定额度错误码，不回显远端文本。
                        val body = kotlin.runCatching {
                            response.body?.source()?.let { source ->
                                source.request(ERROR_BODY_LIMIT + 1L)
                                source.buffer.snapshot(
                                    minOf(source.buffer.size, ERROR_BODY_LIMIT).toInt()
                                ).utf8()
                            }.orEmpty()
                        }.getOrDefault("")
                        val bridgeCode = kotlin.runCatching {
                            JsonParser.parseString(body).asJsonObject["error"]
                                ?.asJsonObject?.get("code")?.asString
                        }.getOrNull()
                        val kind = when {
                            bridgeCode == "free_quota_only" -> "FREE_QUOTA_EXHAUSTED"
                            bridgeCode == "local_trial_limit" -> "LOCAL_BUDGET_EXHAUSTED"
                            bridgeCode == "busy" -> "CONCURRENCY_LIMIT"
                            bridgeCode == "insufficient_resources" ||
                                bridgeCode == "resource_check_failed" -> "INSUFFICIENT_RESOURCES"
                            bridgeCode == "invalid_lease" || bridgeCode == "lease_expired" ||
                                response.code == 409 -> "INVALID_LEASE"
                            response.code == 404 &&
                                chain.request().url.encodedPath.endsWith("/v1/runtime/acquire") ->
                                "UNSUPPORTED"
                            response.code == 401 || response.code == 403 -> "AUTH"
                            response.code == 429 -> "RATE_LIMIT"
                            response.code in 500..599 -> "UNAVAILABLE"
                            else -> "HTTP"
                        }
                        response.close()
                        throw HttpStatusFailure(kind)
                    }
                    response
                }
                .build()
        }
    }
}
