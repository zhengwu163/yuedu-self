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
 * PINNED 手动下载的执行入口。
 *
 * 与 AUTO 的本质区别：不依赖播放授权，因此暂停或停止听书不会中断手动下载；
 * 但仍必须串行，因为预算账本对同一账本文件只放行一个生成请求。
 */
class NovelAudioPinnedDownloaderTest {

    private val book = "https://example.test/book"

    @Test
    fun `a resolved range is prepared in order with pinned retention`() {
        val prepared = log()
        val retentions = Collections.synchronizedList(mutableListOf<String>())
        val downloader = downloader(
            prepare = { index, retention ->
                prepared += index
                retentions += retention
                true
            }
        )

        val result = runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.NextTen,
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals((1..10).toList(), prepared)
        assertTrue(retentions.all { it == NovelAudioRetention.PINNED })
        assertEquals(NovelAudioPinnedDownloader.Result.Completed(10, 0), result)
    }

    @Test
    fun `a rejected range never prepares anything`() {
        val prepared = log()
        val downloader = downloader(prepare = { index, _ -> prepared += index; true })

        val result = runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.Custom(0, 10_000),
                currentChapterIndex = 0,
                chapterCount = 20_000
            )
        }

        assertTrue(prepared.isEmpty())
        assertEquals(
            NovelAudioPinnedDownloader.Result.Rejected(
                NovelAudioPinnedRangePolicy.Rejection.TOO_MANY_CHAPTERS
            ),
            result
        )
    }

    @Test
    fun `download does not stop when playback authorization is revoked`() {
        val prepared = log()
        val downloader = NovelAudioPinnedDownloader(
            prepare = { index, retention -> prepared += index; retention == NovelAudioRetention.PINNED },
            isCancelled = { false }
        )

        runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
                currentChapterIndex = 7,
                chapterCount = 100
            )
        }

        assertEquals(listOf(7), prepared)
    }

    @Test
    fun `an explicit cancel stops before the next chapter`() {
        val prepared = log()
        var cancelled = false
        val downloader = NovelAudioPinnedDownloader(
            prepare = { index, _ ->
                prepared += index
                if (index == 3) cancelled = true
                true
            },
            isCancelled = { cancelled }
        )

        val result = runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.Custom(2, 6),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(listOf(2, 3), prepared)
        assertEquals(NovelAudioPinnedDownloader.Result.Cancelled(2), result)
    }

    @Test
    fun `a failed chapter is counted and the queue continues`() {
        val attempted = log()
        val downloader = downloader(
            prepare = { index, _ ->
                attempted += index
                index != 3
            }
        )

        val result = runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(listOf(2, 3, 4), attempted)
        assertEquals(NovelAudioPinnedDownloader.Result.Completed(2, 1), result)
    }

    @Test
    fun `an unexpected failure is contained and counted as failed`() {
        val attempted = log()
        val downloader = downloader(
            prepare = { index, _ ->
                attempted += index
                if (index == 3) throw IllegalStateException("boom")
                true
            }
        )

        val result = runBlocking {
            downloader.start(
                selection = NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                currentChapterIndex = 0,
                chapterCount = 100
            )
        }

        assertEquals(listOf(2, 3, 4), attempted)
        assertEquals(NovelAudioPinnedDownloader.Result.Completed(2, 1), result)
    }

    @Test
    fun `cancellation propagates instead of being reported as failure`() {
        val downloader = downloader(
            prepare = { index, _ ->
                if (index == 3) throw CancellationException("cancelled")
                true
            }
        )

        try {
            runBlocking {
                downloader.start(
                    selection = NovelAudioPinnedRangePolicy.Selection.Custom(2, 4),
                    currentChapterIndex = 0,
                    chapterCount = 100
                )
            }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun `resuming a single chapter uses pinned retention too`() {
        val retentions = Collections.synchronizedList(mutableListOf<String>())
        val downloader = downloader(
            prepare = { _, retention -> retentions += retention; true }
        )

        assertTrue(runBlocking { downloader.resume(book, 9) })

        assertEquals(listOf(NovelAudioRetention.PINNED), retentions)
    }

    @Test
    fun `resuming a negative chapter is refused without preparing`() {
        val prepared = log()
        val downloader = downloader(prepare = { index, _ -> prepared += index; true })

        assertTrue(!runBlocking { downloader.resume(book, -1) })
        assertTrue(prepared.isEmpty())
    }

    private fun log() = Collections.synchronizedList(mutableListOf<Int>())

    private fun downloader(prepare: suspend (Int, String) -> Boolean) =
        NovelAudioPinnedDownloader(prepare = prepare, isCancelled = { false })
}
