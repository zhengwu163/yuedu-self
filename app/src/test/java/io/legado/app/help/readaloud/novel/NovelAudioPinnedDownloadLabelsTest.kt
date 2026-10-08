package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定下载入口的面向用户文案。
 *
 * 文案与状态一样属于可验证逻辑：章数必须是真实值而非选项名义值，
 * 拒绝原因必须给出用户能据此行动的说明，而不是把内部枚举名抛给用户。
 */
class NovelAudioPinnedDownloadLabelsTest {

    @Test
    fun `preset labels carry the real chapter count`() {
        assertEquals(
            "当前章节（1 章）",
            NovelAudioPinnedDownloadLabels.option(
                NovelAudioPinnedDownloadPresenter.Option(
                    NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
                    1
                )
            )
        )
        assertEquals(
            "后续 10 章（10 章）",
            NovelAudioPinnedDownloadLabels.option(
                NovelAudioPinnedDownloadPresenter.Option(
                    NovelAudioPinnedRangePolicy.Selection.NextTen,
                    10
                )
            )
        )
    }

    @Test
    fun `a narrowed preset shows the real count instead of the nominal one`() {
        assertEquals(
            "后续 10 章（2 章）",
            NovelAudioPinnedDownloadLabels.option(
                NovelAudioPinnedDownloadPresenter.Option(
                    NovelAudioPinnedRangePolicy.Selection.NextTen,
                    2
                )
            )
        )
        assertEquals(
            "后续 20 章（3 章）",
            NovelAudioPinnedDownloadLabels.option(
                NovelAudioPinnedDownloadPresenter.Option(
                    NovelAudioPinnedRangePolicy.Selection.NextTwenty,
                    3
                )
            )
        )
    }

    @Test
    fun `a custom range names its inclusive bounds in reader numbering`() {
        assertEquals(
            "第 3 至 5 章（3 章）",
            NovelAudioPinnedDownloadLabels.option(
                NovelAudioPinnedDownloadPresenter.Option(
                    NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                    3
                )
            )
        )
    }

    @Test
    fun `running progress reports finished over total`() {
        assertEquals(
            "正在下载 2/5 章",
            NovelAudioPinnedDownloadLabels.state(
                NovelAudioPinnedDownloadPresenter.State.Running(5, 2)
            )
        )
    }

    @Test
    fun `a fully successful run does not mention failures`() {
        val text = NovelAudioPinnedDownloadLabels.state(
            NovelAudioPinnedDownloadPresenter.State.Done(4, 0)
        )

        assertEquals("已下载 4 章", text)
        assertTrue(!text.contains("失败"))
    }

    @Test
    fun `a partially failed run reports both counts`() {
        assertEquals(
            "已下载 3 章，2 章失败",
            NovelAudioPinnedDownloadLabels.state(
                NovelAudioPinnedDownloadPresenter.State.Done(3, 2)
            )
        )
    }

    @Test
    fun `cancellation keeps what was already downloaded`() {
        assertEquals(
            "已取消，已下载 2 章",
            NovelAudioPinnedDownloadLabels.state(
                NovelAudioPinnedDownloadPresenter.State.Cancelled(2)
            )
        )
    }

    @Test
    fun `each rejection explains what the user can do`() {
        val texts = NovelAudioPinnedRangePolicy.Rejection.entries.map { reason ->
            NovelAudioPinnedDownloadLabels.state(
                NovelAudioPinnedDownloadPresenter.State.Rejected(reason)
            )
        }

        assertEquals(texts.distinct().size, texts.size)
        texts.forEach { text ->
            assertTrue("不得向用户暴露内部枚举名：$text", !text.contains("_"))
            assertTrue("文案不得为空", text.isNotBlank())
        }
        assertTrue(texts.any { it.contains("范围") })
    }
}
