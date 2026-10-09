package io.legado.app.help.readaloud.novel

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.legado.app.help.config.AtomicTextFileStore
import io.legado.app.help.readaloud.server.NovelAudioServerException
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设备本地、不可退款的模型生成预算账本。
 *
 * 账本文件刻意与服务器凭据分离：更换或清除凭据不得重置累计额度或云额度熔断。
 * 预占在真实 HTTP 发出之前原子落盘；失败、超时、取消和重试都不退款。
 */
class NovelAudioBudgetLedger(
    private val stateFile: File,
    val limits: Limits = Limits()
) {
    enum class Kind {
        ANALYSIS,
        TTS
    }

    data class Limits(
        val analysisRequests: Int = 20,
        val analysisUtf16Characters: Int = 24_000,
        val ttsVendorRequests: Int = 100,
        val ttsUtf16Characters: Int = 5_000,
        // 组合上限默认为两类之和，并对 Int 溢出饱和处理。
        val totalRequests: Int =
            (analysisRequests.toLong() + ttsVendorRequests.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        val totalUtf16Characters: Int =
            (analysisUtf16Characters.toLong() + ttsUtf16Characters.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        init {
            require(analysisRequests > 0)
            require(analysisUtf16Characters > 0)
            require(ttsVendorRequests > 0)
            require(ttsUtf16Characters > 0)
            require(totalRequests > 0)
            require(totalUtf16Characters > 0)
        }
    }

    data class State(
        val analysisRequests: Int = 0,
        val analysisUtf16Characters: Int = 0,
        val ttsVendorRequests: Int = 0,
        val ttsUtf16Characters: Int = 0,
        val cloudQuotaBlocked: Boolean = false,
        val localBudgetBlocked: Boolean = false,
        val inFlight: Boolean = false
    )

    /** 关闭只释放并发许可并清除 inFlight 标记；永不回写扣减。 */
    class Reservation internal constructor(
        private val finish: () -> Unit
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                finish()
            }
        }
    }

    private val canonicalPath = stateFile.canonicalPath
    private val fileLock = synchronized(fileLocks) {
        fileLocks[canonicalPath] ?: Any().also { fileLocks[canonicalPath] = it }
    }
    private val activeLock = synchronized(activeLocks) {
        activeLocks[canonicalPath] ?: Semaphore(1).also { activeLocks[canonicalPath] = it }
    }
    private val localFuse = synchronized(localFuses) {
        localFuses[canonicalPath] ?: AtomicBoolean(false).also { localFuses[canonicalPath] = it }
    }
    private val sentinel = File(stateFile.parentFile, ".${stateFile.name}.init")
    private val store = AtomicTextFileStore(stateFile)

    init {
        if (Files.isSymbolicLink(stateFile.toPath())) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
        stateFile.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) {
                throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
            }
        }
    }

    fun snapshot(): State = synchronized(fileLock) {
        readStateLocked()
    }

    /**
     * 在真实请求发出前原子预占额度。
     *
     * 并发上限为 1：拿不到许可立即拒绝，不排队。预占一旦落盘即视为已消耗。
     */
    fun reserve(
        kind: Kind,
        utf16Characters: Int,
        vendorRequests: Int = 1
    ): Reservation {
        if (utf16Characters <= 0 || vendorRequests <= 0) {
            throw budgetError("LOCAL_BUDGET_EXHAUSTED")
        }
        // 单次物理请求不得超过一期固定口径的绝对上限，计数器因此不可能回绕。
        if (utf16Characters > MAX_RESERVATION_UTF16_CHARACTERS ||
            vendorRequests > MAX_RESERVATION_VENDOR_REQUESTS
        ) {
            throw budgetError("LOCAL_BUDGET_EXHAUSTED")
        }
        if (!activeLock.tryAcquire()) {
            throw budgetError("CONCURRENCY_LIMIT")
        }
        var ownsPermit = true
        try {
            synchronized(fileLock) {
                if (localFuse.get()) {
                    throw budgetError("LOCAL_BUDGET_EXHAUSTED")
                }
                val current = readStateLocked()
                if (current.localBudgetBlocked) {
                    localFuse.set(true)
                    throw budgetError("LOCAL_BUDGET_EXHAUSTED")
                }
                if (current.cloudQuotaBlocked) {
                    throw budgetError("FREE_QUOTA_EXHAUSTED")
                }
                val analysisRequests = current.analysisRequests.toLong() +
                    if (kind == Kind.ANALYSIS) vendorRequests.toLong() else 0L
                val analysisCharacters = current.analysisUtf16Characters.toLong() +
                    if (kind == Kind.ANALYSIS) utf16Characters.toLong() else 0L
                val ttsRequests = current.ttsVendorRequests.toLong() +
                    if (kind == Kind.TTS) vendorRequests.toLong() else 0L
                val ttsCharacters = current.ttsUtf16Characters.toLong() +
                    if (kind == Kind.TTS) utf16Characters.toLong() else 0L
                if (!withinLimits(analysisRequests, analysisCharacters, ttsRequests, ttsCharacters)) {
                    throw budgetError("LOCAL_BUDGET_EXHAUSTED")
                }
                writeStateLocked(
                    current.copy(
                        analysisRequests = analysisRequests.toInt(),
                        analysisUtf16Characters = analysisCharacters.toInt(),
                        ttsVendorRequests = ttsRequests.toInt(),
                        ttsUtf16Characters = ttsCharacters.toInt(),
                        inFlight = true
                    )
                )
            }
            ownsPermit = false
            return Reservation { finishReservation() }
        } finally {
            if (ownsPermit) activeLock.release()
        }
    }

    /**
     * 只占用单飞生成许可，不扣额度、不读写账本；用于自托管服务（家庭电脑）的请求。
     * 与 [reserve] 共用同一许可：本地服务同一时刻只生成一段，并发请求会被它拒为 busy。
     */
    fun acquireSlot(): Reservation {
        if (!activeLock.tryAcquire()) {
            throw budgetError("CONCURRENCY_LIMIT")
        }
        return Reservation { activeLock.release() }
    }

    /** 云端确认免费额度耗尽后持久熔断，后续计费端点立即拒绝。 */
    fun blockCloudQuota() {
        synchronized(fileLock) {
            val current = readRawLocked()
            if (!current.cloudQuotaBlocked) {
                writeStateLocked(current.copy(cloudQuotaBlocked = true))
            }
        }
    }

    /** 桥接侧本地额度耗尽后持久熔断；落盘失败也保留进程内熔断。 */
    fun blockLocalBudget() {
        localFuse.set(true)
        synchronized(fileLock) {
            val current = readRawLocked()
            if (!current.localBudgetBlocked) {
                writeStateLocked(current.copy(localBudgetBlocked = true))
            }
        }
    }

    private fun finishReservation() {
        try {
            synchronized(fileLock) {
                val current = readRawLocked()
                if (current.inFlight) {
                    writeStateLocked(current.copy(inFlight = false))
                }
            }
        } catch (_: Throwable) {
            // 不退款；清标记失败时保留 inFlight，下一次预占 fail closed。
        } finally {
            activeLock.release()
        }
    }

    private fun withinLimits(
        analysisRequests: Long,
        analysisCharacters: Long,
        ttsRequests: Long,
        ttsCharacters: Long
    ): Boolean =
        analysisRequests <= limits.analysisRequests &&
            analysisCharacters <= limits.analysisUtf16Characters &&
            ttsRequests <= limits.ttsVendorRequests &&
            ttsCharacters <= limits.ttsUtf16Characters &&
            analysisRequests + ttsRequests <= limits.totalRequests &&
            analysisCharacters + ttsCharacters <= limits.totalUtf16Characters

    /** 读取并拒绝迟滞的 inFlight 标记：上一次进程在请求中途消失即 fail closed。 */
    private fun readStateLocked(): State {
        val state = readRawLocked()
        if (state.inFlight) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
        return state
    }

    private fun readRawLocked(): State {
        val text = try {
            store.recoverInterruptedCommit()
            when {
                stateFile.exists() && !stateFile.isFile ->
                    throw IllegalStateException("not a regular file")

                !stateFile.exists() -> {
                    // 初始化标记存在却没有账本 = 账本被删除，不能当成新账本。
                    if (sentinel.exists()) throw IllegalStateException("ledger removed")
                    null
                }

                stateFile.length() > MAX_STATE_BYTES ->
                    throw IllegalStateException("oversized state")

                else -> stateFile.readText(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
        if (text == null) {
            return State().also { writeStateLocked(it) }
        }
        val state = try {
            parseState(text)
        } catch (_: Exception) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
        if (!withinLimits(
                state.analysisRequests.toLong(),
                state.analysisUtf16Characters.toLong(),
                state.ttsVendorRequests.toLong(),
                state.ttsUtf16Characters.toLong()
            )
        ) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
        return state
    }

    private fun writeStateLocked(state: State) {
        try {
            store.writeVerified(serialize(state)) { candidate ->
                kotlin.runCatching { parseState(candidate) }.getOrNull() == state
            }
            if (!sentinel.exists()) sentinel.createNewFile()
        } catch (_: Exception) {
            throw budgetError("LOCAL_BUDGET_UNAVAILABLE")
        }
    }

    private fun serialize(state: State): String = buildString {
        append('{')
        append("\"schemaVersion\":").append(SCHEMA_VERSION).append(',')
        append("\"analysisRequests\":").append(state.analysisRequests).append(',')
        append("\"analysisUtf16Characters\":").append(state.analysisUtf16Characters).append(',')
        append("\"ttsVendorRequests\":").append(state.ttsVendorRequests).append(',')
        append("\"ttsUtf16Characters\":").append(state.ttsUtf16Characters).append(',')
        append("\"cloudQuotaBlocked\":").append(state.cloudQuotaBlocked).append(',')
        append("\"localBudgetBlocked\":").append(state.localBudgetBlocked).append(',')
        append("\"inFlight\":").append(state.inFlight)
        append('}')
    }

    /** 严格解析：拒绝重复键、未知键、缺字段、错类型、尾随内容和非法取值。 */
    private fun parseState(text: String): State {
        val counters = HashMap<String, Int>()
        val flags = HashMap<String, Boolean>()
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IllegalArgumentException()
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name !in EXPECTED_KEYS) throw IllegalArgumentException()
                if (counters.containsKey(name) || flags.containsKey(name)) {
                    throw IllegalArgumentException()
                }
                if (name in BOOLEAN_KEYS) {
                    if (reader.peek() != JsonToken.BOOLEAN) throw IllegalArgumentException()
                    flags[name] = reader.nextBoolean()
                } else {
                    if (reader.peek() != JsonToken.NUMBER) throw IllegalArgumentException()
                    counters[name] = reader.nextInt()
                }
            }
            reader.endObject()
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException()
        }
        if (counters.keys + flags.keys != EXPECTED_KEYS) throw IllegalArgumentException()
        if (counters.getValue("schemaVersion") != SCHEMA_VERSION) throw IllegalArgumentException()
        if (counters.filterKeys { it != "schemaVersion" }.values.any { it < 0 }) {
            throw IllegalArgumentException()
        }
        return State(
            analysisRequests = counters.getValue("analysisRequests"),
            analysisUtf16Characters = counters.getValue("analysisUtf16Characters"),
            ttsVendorRequests = counters.getValue("ttsVendorRequests"),
            ttsUtf16Characters = counters.getValue("ttsUtf16Characters"),
            cloudQuotaBlocked = flags.getValue("cloudQuotaBlocked"),
            localBudgetBlocked = flags.getValue("localBudgetBlocked"),
            inFlight = flags.getValue("inFlight")
        )
    }

    private fun budgetError(kind: String): NovelAudioServerException =
        NovelAudioServerException(kind)

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_STATE_BYTES = 4096L

        // 一期固定口径：分析 24000 UTF-16 字符、TTS 100 次供应商请求。
        const val MAX_RESERVATION_UTF16_CHARACTERS = 24_000
        const val MAX_RESERVATION_VENDOR_REQUESTS = 100

        val BOOLEAN_KEYS = setOf("cloudQuotaBlocked", "localBudgetBlocked", "inFlight")
        val EXPECTED_KEYS = setOf(
            "schemaVersion",
            "analysisRequests",
            "analysisUtf16Characters",
            "ttsVendorRequests",
            "ttsUtf16Characters"
        ) + BOOLEAN_KEYS

        val fileLocks = ConcurrentHashMap<String, Any>()
        val activeLocks = ConcurrentHashMap<String, Semaphore>()
        val localFuses = ConcurrentHashMap<String, AtomicBoolean>()
    }
}
