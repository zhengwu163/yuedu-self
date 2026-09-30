package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns run-token fencing and the serialized Room mutation boundary for one
 * preparation coordinator.
 *
 * Request replacement/cancellation changes only memory state synchronously.
 * The old execution is released later on the same IO mutation lane used by
 * persistence, so a new run cannot save before its predecessor is released.
 */
internal class NovelAudioPreparationLifecycle(
    private val saveExecution: (NovelAudioChapterPlan, String) ->
        NovelAudioRepository.Execution?,
    private val releaseExecution: (NovelAudioRepository.Execution, String) -> Unit,
    private val mutationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + mutationDispatcher
    )
) {

    internal data class Request(
        val bookUrl: String,
        val chapterIndex: Int,
        val retention: String
    )

    internal class Run internal constructor(
        val id: Long,
        internal val request: Request,
        internal val retention: String,
        internal val previousCleanup: Deferred<Unit>?
    ) {
        internal val cleanup = CompletableDeferred<Unit>()

        @Volatile
        internal var valid = true

        @Volatile
        internal var completed = false

        internal var execution: NovelAudioRepository.Execution? = null
        internal var cleanupScheduled = false
        internal var invalidationReason = "CANCELLED"
    }

    private val stateLock = Any()
    private val mutationLock = Mutex()
    private val tokenSequence = AtomicLong(0L)
    private var currentRequest: Request? = null
    private var currentRun: Run? = null
    private var lastCleanup: Deferred<Unit>? = null

    @Synchronized
    fun replaceRequest(
        bookUrl: String,
        chapterIndex: Int,
        retention: String
    ): Request {
        require(bookUrl.isNotBlank())
        require(chapterIndex >= 0)
        require(
            retention == NovelAudioRetention.AUTO ||
                retention == NovelAudioRetention.PINNED
        )
        val retired = synchronized(stateLock) {
            retireCurrentLocked("CANCELLED")
        }
        scheduleCleanup(retired)
        return Request(bookUrl, chapterIndex, retention).also {
            synchronized(stateLock) {
                currentRequest = it
            }
        }
    }

    /**
     * Starts a run for the current request. Calling this twice for the same
     * request still retires the first run and allocates a new token.
     */
    @Synchronized
    fun start(request: Request): Run? {
        val retired = synchronized(stateLock) {
            if (currentRequest !== request) return@synchronized null
            retireCurrentLocked("CANCELLED")
        }
        if (currentRequest !== request) {
            scheduleCleanup(retired)
            return null
        }
        val run = synchronized(stateLock) {
            Run(
                id = tokenSequence.incrementAndGet(),
                request = request,
                retention = request.retention,
                previousCleanup = retired?.cleanup ?: lastCleanup
            ).also {
                currentRun = it
            }
        }
        scheduleCleanup(retired)
        return run
    }

    @Synchronized
    fun cancel() {
        val retired = synchronized(stateLock) {
            currentRequest = null
            retireCurrentLocked("CANCELLED")
        }
        scheduleCleanup(retired)
    }

    fun isCurrent(run: Run, isAutoAllowed: () -> Boolean = { true }): Boolean {
        if (run.retention == NovelAudioRetention.AUTO && !isAutoAllowed()) {
            return false
        }
        return synchronized(stateLock) {
            currentRun === run &&
                currentRequest === run.request &&
                run.valid
        }
    }

    suspend fun persist(
        run: Run,
        plan: NovelAudioChapterPlan,
        retention: String = run.retention,
        isAutoAllowed: () -> Boolean = { true }
    ): NovelAudioRepository.Execution? {
        run.previousCleanup?.await()
        return withContext(NonCancellable + mutationDispatcher) {
            mutationLock.withLock {
                if (!isCurrent(run, isAutoAllowed)) return@withLock null
                val execution = saveExecution(plan, retention) ?: return@withLock null
                synchronized(run) {
                    run.execution = execution
                }
                if (!isCurrent(run, isAutoAllowed)) {
                    releaseOwnedLocked(
                        run,
                        if (run.retention == NovelAudioRetention.AUTO &&
                            !isAutoAllowed()
                        ) {
                            "AUTO_LEASE_REVOKED"
                        } else {
                            run.invalidationReason
                        }
                    )
                    return@withLock null
                }
                execution
            }
        }
    }

    /**
     * The producer persistence callback is synchronous. It is only called by
     * the coordinator's IO preparation job, while this method still keeps the
     * actual Room mutation on the serialized dispatcher.
     */
    fun persistBlocking(
        run: Run,
        plan: NovelAudioChapterPlan,
        isAutoAllowed: () -> Boolean
    ): NovelAudioRepository.Execution? {
        return kotlinx.coroutines.runBlocking {
            persist(run, plan, run.retention, isAutoAllowed)
        }
    }

    fun execution(run: Run): NovelAudioRepository.Execution? {
        return synchronized(run) { run.execution }
    }

    suspend fun <T> run(
        run: Run,
        isSuccessful: () -> Boolean,
        block: suspend () -> T
    ): T {
        var completed = false
        return try {
            block().also {
                completed = isSuccessful()
                if (completed) complete(run)
            }
        } finally {
            if (!completed) release(run)
        }
    }

    fun complete(run: Run) {
        synchronized(run) {
            run.completed = true
            run.execution = null
            run.cleanup.complete(Unit)
        }
    }

    suspend fun release(run: Run) {
        withContext(NonCancellable + mutationDispatcher) {
            mutationLock.withLock {
                releaseOwnedLocked(run, run.invalidationReason)
                run.cleanup.complete(Unit)
            }
        }
    }

    fun dispatchIfCurrent(
        run: Run,
        isCurrent: () -> Boolean,
        dispatch: () -> Unit
    ): Boolean {
        synchronized(stateLock) {
            if (currentRun !== run ||
                currentRequest !== run.request ||
                !run.valid
            ) return false
            if (run.retention == NovelAudioRetention.AUTO && !isCurrent()) {
                return false
            }
            dispatch()
            return true
        }
    }

    fun clearRequestIfCurrent(run: Run): Boolean {
        synchronized(stateLock) {
            if (currentRun !== run || currentRequest !== run.request) {
                return false
            }
            currentRequest = null
            return true
        }
    }

    fun currentRequest(): Request? = synchronized(stateLock) { currentRequest }

    private fun retireCurrentLocked(reason: String): Run? {
        val retired = currentRun ?: return null
        retired.valid = false
        retired.invalidationReason = reason
        currentRun = null
        lastCleanup = retired.cleanup
        return retired
    }

    private fun scheduleCleanup(run: Run?) {
        if (run == null) return
        synchronized(run) {
            if (run.cleanupScheduled) return
            run.cleanupScheduled = true
        }
        val predecessor = synchronized(stateLock) {
            lastCleanup?.takeUnless { it === run.cleanup }
        }
        cleanupScope.async {
            predecessor?.await()
            release(run)
        }
    }

    private fun releaseOwnedLocked(
        run: Run,
        reason: String
    ) {
        synchronized(run) {
            if (run.completed) {
                run.execution = null
                return
            }
            val execution = run.execution ?: return
            run.execution = null
            if (execution.alreadyReady) return
            releaseExecution(execution, reason)
        }
    }
}
