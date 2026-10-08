package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.offline.AudioPrefetchLifecycle
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.help.readaloud.offline.NovelAudioAutoPrefetchCoordinator
import io.legado.app.model.ReadBook
import kotlinx.coroutines.Dispatchers

/**
 * 把 AUTO 预取接到播放服务的唯一入口。
 *
 * 只在当前章真正就绪后驱动：窗口由 [AudioPrefetchLifecycle.assembled] 在装配完成时
 * 更新，提前驱动只会拿到空窗口。每个撤销授权的入口都必须调用 [revoke]，
 * 否则暂停或换书后后台仍在消耗不可退款的云额度。
 *
 * AUTO 走自己的车道和后续章入口，绝不占用当前章的请求槽：占用会让用户翻章后
 * 当前章的最终正文回调因请求不匹配被丢弃，正在等的那一章永不被准备。
 */
object NovelAudioAutoPrefetchDriver {

    private val lane = NovelAudioPreparationLane()
    private val snapshotLoader = NovelAudioChapterSnapshotLoader.create()

    @Volatile
    private var job: Coroutine<Unit>? = null

    /**
     * 当前章已就绪：占用当前章车道后立即释放，再按最新窗口串行补后续章。
     * 先占后放是为了让「当前章刚播」这一事实被车道记住，避免 AUTO 重复准备同一章。
     */
    fun onChapterReady(work: AudioPrefetchLifecycle.Work?, bookUrl: String, chapterIndex: Int) {
        if (work == null || bookUrl.isBlank() || chapterIndex < 0) return
        lane.beginCurrent(bookUrl, chapterIndex)
        lane.finishCurrent(bookUrl, chapterIndex)
        val window = AudioPrefetchPlayback.lifecycle.currentWindow() ?: return
        if (window.work !== work || window.chapters.isEmpty()) return
        val book = ReadBook.book?.takeIf { it.bookUrl == bookUrl } ?: return
        val chapters = window.chapters
        job?.cancel()
        job = Coroutine.async<Unit>(ReadBook, executeContext = Dispatchers.IO) {
            val environment = NovelAudioPreparationEnvironment.open() ?: return@async
            val batch = environment.acquireBatch(
                purpose = "auto_prefetch",
                expectedChapterCount = chapters.count()
            )
            try {
                NovelAudioAutoPrefetchCoordinator(
                    isAllowed = { target ->
                        AudioPrefetchPlayback.lifecycle.isAllowed(work, target)
                    },
                    isLocallyReady = { targetBook, index -> isReady(targetBook, index) },
                    prepare = { targetBook, index ->
                        prepare(
                            book = book,
                            bookUrl = targetBook,
                            chapterIndex = index,
                            lastChapterIndex = chapters.last,
                            environment = batch.environment
                        )
                    }
                ).run(bookUrl, chapters)
            } finally {
                batch.close()
            }
        }.onError {
            AppLog.put("AI 听书自动预取中断", it)
        }
    }

    /** 暂停、停止、销毁与换书都必须撤销，避免后台继续消耗额度。 */
    fun revoke() {
        job?.cancel()
        job = null
        lane.revoke()
    }

    // 本地已完整的后续章直接跳过，不重复分析或合成。
    // 判定读真实 Room 状态：计划状态、代次与全部 artifact 就绪缺一不可。
    private fun isReady(bookUrl: String, chapterIndex: Int): Boolean {
        val entity = appDb.novelAudioDao.currentChapterPlan(bookUrl, chapterIndex) ?: return false
        val plan = NovelAudioPlanJson.decode(entity.planJson) ?: return false
        val segmentIds = plan.playableSegments.map { it.segmentId }
        val allReady = NovelAudioRepository(appDb).allArtifactsReady(plan.planId, segmentIds)
        return NovelAudioLocalFirstPolicy.decide(
            planState = entity.state,
            planGeneration = entity.generation,
            expectedGeneration = entity.generation,
            allArtifactsReady = allReady,
            online = true
        ) == NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL
    }

    private suspend fun prepare(
        book: Book,
        bookUrl: String,
        chapterIndex: Int,
        lastChapterIndex: Int,
        environment: NovelAudioPreparationEnvironment
    ): Boolean {
        if (book.bookUrl != bookUrl) return false
        val following = NovelAudioFollowingChapterPreparer(
            lane = lane,
            snapshot = { targetBook, index -> snapshotLoader.load(targetBook, index) },
            prepare = { snapshot, generation, retention ->
                environment.preparer { plan, planRetention ->
                    NovelAudioRepository(appDb).savePlanForExecution(plan, planRetention)
                }.prepare(
                    snapshot = snapshot,
                    generation = generation,
                    retention = retention,
                    isCurrent = { true }
                )
            }
        )
        return following.prepare(book, chapterIndex, lastChapterIndex + 1)
    }
}
