package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PINNED 手动下载的范围解析。
 *
 * 一期只允许四种选择：当前章、后续 10 章、后续 20 章、自定义区间；
 * 任何选择都不得扩大到整本书，因此存在统一的章数上限，超限一律拒绝而非静默截断——
 * 静默截断会让用户以为已经排队的章节其实没有排队。
 */
class NovelAudioPinnedRangePolicyTest {

    @Test
    fun `current chapter only pins itself`() {
        assertEquals(
            resolved(listOf(4)),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
                currentChapterIndex = 4,
                chapterCount = 100
            )
        )
    }

    @Test
    fun `next ten and next twenty start after the current chapter`() {
        assertEquals(
            resolved((5..14).toList()),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.NextTen,
                currentChapterIndex = 4,
                chapterCount = 100
            )
        )
        assertEquals(
            resolved((5..24).toList()),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.NextTwenty,
                currentChapterIndex = 4,
                chapterCount = 100
            )
        )
    }

    @Test
    fun `presets are clamped by the end of the book instead of overflowing`() {
        assertEquals(
            resolved(listOf(8, 9)),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.NextTen,
                currentChapterIndex = 7,
                chapterCount = 10
            )
        )
        assertEquals(
            NovelAudioPinnedRangePolicy.Result.Rejected(
                NovelAudioPinnedRangePolicy.Rejection.EMPTY_RANGE
            ),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.NextTen,
                currentChapterIndex = 9,
                chapterCount = 10
            )
        )
    }

    @Test
    fun `custom range is inclusive and ordered`() {
        assertEquals(
            resolved(listOf(3, 4, 5)),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.Custom(3, 5),
                currentChapterIndex = 0,
                chapterCount = 50
            )
        )
    }

    @Test
    fun `custom range beyond the chapter limit is rejected instead of truncated`() {
        assertEquals(
            NovelAudioPinnedRangePolicy.Result.Rejected(
                NovelAudioPinnedRangePolicy.Rejection.TOO_MANY_CHAPTERS
            ),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.Custom(0, 20),
                currentChapterIndex = 0,
                chapterCount = 500
            )
        )
    }

    @Test
    fun `custom range outside the book is rejected`() {
        listOf(
            NovelAudioPinnedRangePolicy.Selection.Custom(-1, 3),
            NovelAudioPinnedRangePolicy.Selection.Custom(0, 10),
            NovelAudioPinnedRangePolicy.Selection.Custom(10, 12)
        ).forEach { selection ->
            assertEquals(
                NovelAudioPinnedRangePolicy.Result.Rejected(
                    NovelAudioPinnedRangePolicy.Rejection.OUT_OF_BOUNDS
                ),
                NovelAudioPinnedRangePolicy.resolve(
                    selection,
                    currentChapterIndex = 0,
                    chapterCount = 10
                )
            )
        }
    }

    @Test
    fun `inverted custom range is rejected`() {
        assertEquals(
            NovelAudioPinnedRangePolicy.Result.Rejected(
                NovelAudioPinnedRangePolicy.Rejection.EMPTY_RANGE
            ),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.Custom(6, 5),
                currentChapterIndex = 0,
                chapterCount = 50
            )
        )
    }

    @Test
    fun `an empty or invalid book is rejected before producing work`() {
        listOf(0, -1).forEach { count ->
            assertEquals(
                NovelAudioPinnedRangePolicy.Result.Rejected(
                    NovelAudioPinnedRangePolicy.Rejection.OUT_OF_BOUNDS
                ),
                NovelAudioPinnedRangePolicy.resolve(
                    NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
                    currentChapterIndex = 0,
                    chapterCount = count
                )
            )
        }
        assertEquals(
            NovelAudioPinnedRangePolicy.Result.Rejected(
                NovelAudioPinnedRangePolicy.Rejection.OUT_OF_BOUNDS
            ),
            NovelAudioPinnedRangePolicy.resolve(
                NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
                currentChapterIndex = 10,
                chapterCount = 10
            )
        )
    }

    @Test
    fun `no selection can pin a whole large book`() {
        listOf(
            NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
            NovelAudioPinnedRangePolicy.Selection.NextTen,
            NovelAudioPinnedRangePolicy.Selection.NextTwenty,
            NovelAudioPinnedRangePolicy.Selection.Custom(0, 19)
        ).forEach { selection ->
            val result = NovelAudioPinnedRangePolicy.resolve(
                selection,
                currentChapterIndex = 0,
                chapterCount = 3000
            )

            val chapters = (result as NovelAudioPinnedRangePolicy.Result.Resolved).chapters
            assertEquals(chapters.distinct(), chapters)
            assertEquals(chapters.sorted(), chapters)
            assertEquals(true, chapters.size <= NovelAudioPinnedRangePolicy.MAX_CHAPTERS)
        }
    }

    private fun resolved(chapters: List<Int>) =
        NovelAudioPinnedRangePolicy.Result.Resolved(chapters)
}
