package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 固定下载的界面状态持有者。
 *
 * 只做可选项裁剪、进度推进与结果归集，不含任何取色或组件决策，
 * 因此可在纯 JVM 下验证；界面只负责渲染这里给出的状态。
 *
 * 关键产品判断：不提供会被拒绝的选项。书末只剩两章时提供「后续 10 章」
 * 会让用户以为排了十章，实际只排两章。
 */
class NovelAudioPinnedDownloadPresenterTest {

    @Test
    fun `options expose the real chapter count of each selection`() {
        val presenter = presenter { _, _ -> true }

        val options = presenter.options(currentChapterIndex = 0, chapterCount = 100)

        assertEquals(
            listOf(
                NovelAudioPinnedRangePolicy.Selection.CurrentChapter to 1,
                NovelAudioPinnedRangePolicy.Selection.NextTen to 10,
                NovelAudioPinnedRangePolicy.Selection.NextTwenty to 20
            ),
            options.map { it.selection to it.chapters }
        )
    }

    @Test
    fun `a preset narrowed by the end of the book reports its real count`() {
        val presenter = presenter { _, _ -> true }

        val options = presenter.options(currentChapterIndex = 7, chapterCount = 10)

        assertEquals(
            listOf(
                NovelAudioPinnedRangePolicy.Selection.CurrentChapter to 1,
                NovelAudioPinnedRangePolicy.Selection.NextTen to 2,
                NovelAudioPinnedRangePolicy.Selection.NextTwenty to 2
            ),
            options.map { it.selection to it.chapters }
        )
    }

    @Test
    fun `presets are not offered at all at the last chapter`() {
        val presenter = presenter { _, _ -> true }

        val options = presenter.options(currentChapterIndex = 9, chapterCount = 10)

        assertEquals(
            listOf(NovelAudioPinnedRangePolicy.Selection.CurrentChapter),
            options.map { it.selection }
        )
    }

    @Test
    fun `an invalid book offers nothing`() {
        val presenter = presenter { _, _ -> true }

        assertTrue(presenter.options(currentChapterIndex = 0, chapterCount = 0).isEmpty())
        assertTrue(presenter.options(currentChapterIndex = 5, chapterCount = 3).isEmpty())
    }

    @Test
    fun `start publishes progress for every chapter then a final result`() {
        val states = states()
        val presenter = presenter(onState = { states += it }) { _, _ -> true }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(
            listOf(
                NovelAudioPinnedDownloadPresenter.State.Running(3, 0),
                NovelAudioPinnedDownloadPresenter.State.Running(3, 1),
                NovelAudioPinnedDownloadPresenter.State.Running(3, 2),
                NovelAudioPinnedDownloadPresenter.State.Running(3, 3),
                NovelAudioPinnedDownloadPresenter.State.Done(3, 0)
            ),
            states
        )
    }

    @Test
    fun `every chapter is prepared with pinned retention`() {
        val retentions = Collections.synchronizedList(mutableListOf<String>())
        val presenter = presenter { _, retention -> retentions += retention; true }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(2, 3),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(
            listOf(NovelAudioRetention.PINNED, NovelAudioRetention.PINNED),
            retentions
        )
    }

    @Test
    fun `a rejected selection publishes the reason without preparing`() {
        val prepared = Collections.synchronizedList(mutableListOf<Int>())
        val states = states()
        val presenter = presenter(onState = { states += it }) { index, _ ->
            prepared += index
            true
        }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(0, 40),
                currentChapterIndex = 0,
                chapterCount = 500
            )
        }

        assertTrue(prepared.isEmpty())
        assertEquals(
            listOf(
                NovelAudioPinnedDownloadPresenter.State.Rejected(
                    NovelAudioPinnedRangePolicy.Rejection.TOO_MANY_CHAPTERS
                )
            ),
            states
        )
    }

    @Test
    fun `cancel stops the queue and publishes the cancelled count`() {
        val states = states()
        lateinit var presenter: NovelAudioPinnedDownloadPresenter
        presenter = presenter(onState = { states += it }) { index, _ ->
            if (index == 3) presenter.cancel()
            true
        }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(2, 6),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(
            NovelAudioPinnedDownloadPresenter.State.Cancelled(2),
            states.last()
        )
    }

    @Test
    fun `a cancelled run does not poison the next start`() {
        val prepared = Collections.synchronizedList(mutableListOf<Int>())
        lateinit var presenter: NovelAudioPinnedDownloadPresenter
        presenter = presenter { index, _ ->
            prepared += index
            if (index == 2) presenter.cancel()
            true
        }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(2, 5),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }
        prepared.clear()
        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(7, 8),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(listOf(7, 8), prepared)
    }

    @Test
    fun `failed chapters are counted in the final result`() {
        val states = states()
        val presenter = presenter(onState = { states += it }) { index, _ -> index != 3 }

        runBlocking {
            presenter.start(
                NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(NovelAudioPinnedDownloadPresenter.State.Done(2, 1), states.last())
    }

    @Test
    fun `cancellation propagates instead of publishing a result`() {
        val states = states()
        val presenter = presenter(onState = { states += it }) { index, _ ->
            if (index == 3) throw CancellationException("cancelled")
            true
        }

        try {
            runBlocking {
                presenter.start(
                    NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                    currentChapterIndex = 0,
                    chapterCount = 100
                )
            }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(
                "取消不得发布终态",
                states.none {
                    it is NovelAudioPinnedDownloadPresenter.State.Done ||
                        it is NovelAudioPinnedDownloadPresenter.State.Cancelled
                }
            )
        }
    }

    private fun states() = Collections.synchronizedList(
        mutableListOf<NovelAudioPinnedDownloadPresenter.State>()
    )

    private fun presenter(
        onState: (NovelAudioPinnedDownloadPresenter.State) -> Unit = {},
        prepare: suspend (Int, String) -> Boolean
    ) = NovelAudioPinnedDownloadPresenter(prepare = prepare, onState = onState)
}
