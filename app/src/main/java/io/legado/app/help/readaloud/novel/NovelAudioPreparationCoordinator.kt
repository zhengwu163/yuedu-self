package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.help.book.BookContent
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.offline.NovelAudioArtifactStore
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import io.legado.app.help.readaloud.offline.canDecodeNovelAudio
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.model.ReadBook
import io.legado.app.help.readaloud.NovelAudioPreparationState
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineStart

/**
 * Owns the short-lived hand-off from the reader's final BookContent to plan
 * analysis. It never starts work unless a user action has explicitly requested
 * NovelAudio preparation.
 */
object NovelAudioPreparationCoordinator {

    private data class CacheKey(val bookUrl: String, val chapterIndex: Int)

    private data class SnapshotEntry(
        val bookUrl: String,
        val chapterIndex: Int,
        val snapshot: NovelAudioChapterSnapshot,
        val generation: Long,
        val layoutKey: String,
        val cachedAt: Long = System.currentTimeMillis()
    )

    private data class Request(
        val bookUrl: String,
        val chapterIndex: Int,
        val retention: String,
        val lifecycleRequest: NovelAudioPreparationLifecycle.Request
    )

    private val snapshots = ConcurrentHashMap<CacheKey, SnapshotEntry>()
    private val lifecycle = NovelAudioPreparationLifecycle(
        saveExecution = { plan, retention ->
            NovelAudioRepository(appDb).savePlanForExecution(plan, retention)
        },
        releaseExecution = { execution, reason ->
            NovelAudioRepository(appDb).releaseExecution(execution, reason)
        }
    )

    @Volatile
    private var request: Request? = null

    @Volatile
    private var prepareJob: Coroutine<PreparationResult?>? = null

    @Synchronized
    fun request(
        bookUrl: String,
        chapterIndex: Int,
        retention: String = NovelAudioRetention.AUTO
    ) {
        require(bookUrl.isNotBlank())
        require(chapterIndex >= 0)
        require(
            retention == NovelAudioRetention.AUTO ||
                retention == NovelAudioRetention.PINNED
        )
        // Replacing a request must stop the old network/analysis chain even
        // when the caller has not produced the new chapter content yet.
        val lifecycleRequest = lifecycle.replaceRequest(
            bookUrl,
            chapterIndex,
            retention
        )
        prepareJob?.cancel()
        prepareJob = null
        request = Request(bookUrl, chapterIndex, retention, lifecycleRequest)
    }

    fun startCached(
        bookUrl: String,
        chapterIndex: Int,
        isCurrent: () -> Boolean
    ) {
        val entry = snapshots[CacheKey(bookUrl, chapterIndex)] ?: return
        val currentRequest = request ?: return
        if (currentRequest.bookUrl != bookUrl ||
            currentRequest.chapterIndex != chapterIndex
        ) return
        start(entry, currentRequest, isCurrent)
    }

    fun cachedGeneration(bookUrl: String, chapterIndex: Int): Long? {
        return snapshots[CacheKey(bookUrl, chapterIndex)]?.generation
    }

    fun onFinalContent(
        book: Book,
        chapter: BookChapter,
        content: BookContent,
        generation: Long,
        layoutKey: String,
        isCurrent: () -> Boolean
    ): NovelAudioChapterSnapshot {
        val snapshot = NovelAudioChapterSnapshotFactory.fromBook(book, chapter, content)
        val entry = SnapshotEntry(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.index,
            snapshot = snapshot,
            generation = generation,
            layoutKey = layoutKey
        )
        val key = CacheKey(book.bookUrl, chapter.index)
        snapshots[key] = entry
        trimCache()

        val currentRequest = request ?: return snapshot
        if (currentRequest.bookUrl == book.bookUrl &&
            currentRequest.chapterIndex == chapter.index
        ) {
            start(entry, currentRequest, isCurrent)
        }
        return snapshot
    }

    @Synchronized
    fun cancel() {
        request = null
        lifecycle.cancel()
        prepareJob?.cancel()
        prepareJob = null
    }

    @Synchronized
    private fun start(
        entry: SnapshotEntry,
        currentRequest: Request,
        isCurrent: () -> Boolean
    ) {
        // A stale final-content callback must not cancel the job created for a
        // newer request, even when both requests target the same chapter.
        if (!isRequestCurrent(currentRequest)) return
        prepareJob?.cancel()
        val run = lifecycle.start(currentRequest.lifecycleRequest) ?: return
        val job = Coroutine.async<PreparationResult?>(
            ReadBook,
            start = CoroutineStart.LAZY,
            executeContext = kotlinx.coroutines.Dispatchers.IO
        ) preparation@{
            lifecycle.run(
                run = run,
                isSuccessful = {
                    lifecycle.isCurrent(run) {
                        isCurrent() && isRequestCurrent(currentRequest)
                    }
                },
                block = runBlock@{
            if (!lifecycle.isCurrent(run) {
                    isCurrent() && isRequestCurrent(currentRequest)
                }
            ) return@runBlock null
            val credentials = NovelAudioAndroidConfigStore.open(appCtx).load()
                ?: return@runBlock PreparationResult.Failed(
                    reason = "MISSING_CREDENTIALS",
                    generation = entry.generation
                )
            val characterStore = NovelAudioRoomCharacterStore(appDb)
            val registry = CharacterRegistry(characterStore)
            val budgetLedger = NovelAudioBudgetLedger(
                File(appCtx.noBackupFilesDir, "novel-audio-budget.json")
            )
            val client = credentials.newClient(budgetLedger)
            val analysis = NovelAudioAnalysisCoordinator(
                analyze = client::analyze,
                registry = registry,
                voices = client::voices,
                match = client::match
            )
            val repository = NovelAudioRepository(appDb)
            val existingCharacters = characterStore.characters(entry.snapshot.workKey)
            val existingBindings = characterStore
                .voiceBindings(existingCharacters.map { it.id }.toSet())
                .map { it.toDomain() }
            var execution: NovelAudioRepository.Execution? = null
            val producer = NovelAudioPlanProducer(
                analyze = { snapshot, scope, generation, characters, units, bindings ->
                    analysis.analyze(
                        snapshot = snapshot,
                        scope = scope,
                        generation = generation,
                        existingCharacters = characters,
                        parsedUnits = units,
                        existingBindings = bindings
                    )
                },
                persist = { plan, retention ->
                    execution = lifecycle.persistBlocking(
                        run,
                        plan,
                        isAutoAllowed = {
                            isCurrent() && isRequestCurrent(currentRequest)
                        }
                    )
                    execution != null
                }
            )
            val artifactRoot = File(appCtx.filesDir, AUDIO_DIRECTORY)
            val decoder: (File) -> Boolean = ::canDecodeNovelAudio
            val downloadCoordinator = NovelAudioDownloadCoordinator(
                repository = repository,
                artifactStore = NovelAudioArtifactStore(
                    rootDirectory = artifactRoot,
                    decoder = decoder
                ),
                filesRoot = artifactRoot,
                synthesize = client::synthesize,
                decoder = decoder
            )
            val plan = producer.produce(
                snapshot = entry.snapshot,
                scope = NovelAudioIdentity.serverScope(credentials.baseUrl),
                generation = entry.generation,
                existingCharacters = existingCharacters,
                existingBindings = existingBindings,
                retention = currentRequest.retention,
                isCurrent = {
                    lifecycle.isCurrent(run) {
                        isCurrent() && isRequestCurrent(currentRequest)
                    }
                }
            ) ?: return@runBlock null
            when (
                val result = downloadCoordinator.downloadPlan(
                    execution = execution ?: return@runBlock null,
                    isAutoAllowed = {
                        lifecycle.isCurrent(run) {
                            isCurrent() && isRequestCurrent(currentRequest)
                        }
                    }
                )
            ) {
                NovelAudioDownloadCoordinator.Result.Ready -> PreparationResult.Ready(plan)
                NovelAudioDownloadCoordinator.Result.Cancelled -> null
                is NovelAudioDownloadCoordinator.Result.Failed ->
                    PreparationResult.Failed(
                        reason = result.reason,
                        generation = entry.generation
                    )
            }
            })
        }.onSuccess { result ->
            when (result) {
                is PreparationResult.Ready -> {
                    publishReadyIfCurrent(
                        run,
                        currentRequest,
                        isCurrent,
                        NovelAudioPreparationState(
                            bookUrl = result.plan.physicalBookUrl,
                            chapterIndex = result.plan.chapterIndex,
                            planId = result.plan.planId,
                            generation = result.plan.generation,
                            ready = true
                        )
                    )
                }

                is PreparationResult.Failed -> {
                    publishFailureIfCurrent(
                        run,
                        currentRequest,
                        isCurrent,
                        NovelAudioPreparationState(
                            bookUrl = currentRequest.bookUrl,
                            chapterIndex = currentRequest.chapterIndex,
                            generation = result.generation,
                            ready = false,
                            reason = result.reason
                        )
                    )
                }

                null -> Unit
            }
        }.onError {
            if (it !is kotlinx.coroutines.CancellationException) {
                AppLog.put("AI 听书章节计划准备失败", it)
                publishFailureIfCurrent(
                    run,
                    currentRequest,
                    isCurrent,
                    NovelAudioPreparationState(
                        bookUrl = currentRequest.bookUrl,
                        chapterIndex = currentRequest.chapterIndex,
                        generation = entry.generation,
                        ready = false,
                        reason = "PREPARATION_FAILURE"
                    )
                )
            }
        }
        prepareJob = job
        job.start()
    }

    @Synchronized
    private fun isRequestCurrent(expected: Request): Boolean {
        // 再次点击同一章也是一次新请求，不能用相同字段复活上次的准备任务。
        return request === expected
    }

    @Synchronized
    private fun clearRequestIfCurrent(expected: Request) {
        if (request === expected) request = null
    }

    private fun publishReadyIfCurrent(
        run: NovelAudioPreparationLifecycle.Run,
        expected: Request,
        isCurrent: () -> Boolean,
        state: NovelAudioPreparationState
    ) {
        dispatchIfCurrent(run, expected, isCurrent) {
            clearRequestIfCurrent(expected)
            lifecycle.clearRequestIfCurrent(run)
            postEvent(EventBus.NOVEL_AUDIO_PREPARATION, state)
        }
    }

    private fun publishFailureIfCurrent(
        run: NovelAudioPreparationLifecycle.Run,
        expected: Request,
        isCurrent: () -> Boolean,
        state: NovelAudioPreparationState
    ) {
        dispatchIfCurrent(run, expected, isCurrent) {
            postEvent(EventBus.NOVEL_AUDIO_PREPARATION, state)
        }
    }

    private fun dispatchIfCurrent(
        run: NovelAudioPreparationLifecycle.Run,
        expected: Request,
        isCurrent: () -> Boolean,
        dispatch: () -> Unit
    ) {
        lifecycle.dispatchIfCurrent(run, isCurrent) {
            synchronized(this) {
                if (request !== expected) return@dispatchIfCurrent
                dispatch()
            }
        }
    }

    private fun trimCache() {
        while (snapshots.size > MAX_CACHED_SNAPSHOTS) {
            val oldest = snapshots.entries.minByOrNull { it.value.cachedAt } ?: return
            snapshots.remove(oldest.key, oldest.value)
        }
    }

    private const val MAX_CACHED_SNAPSHOTS = 12
    private const val AUDIO_DIRECTORY = "novel-audio"

    sealed interface PreparationResult {
        data class Ready(val plan: NovelAudioChapterPlan) : PreparationResult
        data class Failed(
            val reason: String,
            val generation: Long
        ) : PreparationResult
    }
}
