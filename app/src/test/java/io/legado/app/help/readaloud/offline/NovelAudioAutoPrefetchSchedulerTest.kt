package io.legado.app.help.readaloud.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * AUTO 预取只允许当前章之后最多三章，且必须串行推进：
 * 预算账本对同一文件路径只放行一个生成请求，准备协调器也只有单个请求槽，
 * 因此调度器一次只能下发一个目标，并在上一个目标结束后才推进下一个。
 */
class NovelAudioAutoPrefetchSchedulerTest {

    private val book = "https://example.test/book"

    @Test
    fun `window issues only the three chapters after the current one in order`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()

        assertEquals(target(11), scheduler.onWindow(book, 11..13))
        assertEquals(target(12), scheduler.onCompleted(book, 11))
        assertEquals(target(13), scheduler.onCompleted(book, 12))
        assertNull(scheduler.onCompleted(book, 13))
    }

    @Test
    fun `empty window at the end of the book issues nothing`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()

        assertNull(scheduler.onWindow(book, IntRange.EMPTY))
        assertNull(scheduler.onCompleted(book, 0))
    }

    @Test
    fun `a failed target is skipped once instead of retried`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 5..7)

        assertEquals(target(6), scheduler.onFailed(book, 5))
        assertEquals(target(7), scheduler.onFailed(book, 6))
        assertNull(scheduler.onFailed(book, 7))
    }

    @Test
    fun `a new window replaces the old one and old completions cannot advance it`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 1..3)

        assertEquals(target(21), scheduler.onWindow(book, 21..23))
        assertNull(scheduler.onCompleted(book, 1))
        assertEquals(target(22), scheduler.onCompleted(book, 21))
    }

    @Test
    fun `completion for another book or another chapter never advances the window`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 4..6)

        assertNull(scheduler.onCompleted("https://example.test/other", 4))
        assertNull(scheduler.onCompleted(book, 5))
        assertEquals(target(5), scheduler.onCompleted(book, 4))
    }

    @Test
    fun `revocation stops the window and late completions do not resume it`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 8..10)
        scheduler.revoke()

        assertNull(scheduler.onCompleted(book, 8))
        assertNull(scheduler.onFailed(book, 8))
    }

    @Test
    fun `repeated completion of the same target does not reissue or skip ahead`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 2..4)

        assertEquals(target(3), scheduler.onCompleted(book, 2))
        assertNull(scheduler.onCompleted(book, 2))
        assertEquals(target(4), scheduler.onCompleted(book, 3))
    }

    @Test
    fun `resending the same window does not restart chapters already advanced`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()
        scheduler.onWindow(book, 30..32)
        scheduler.onCompleted(book, 30)

        assertNull(scheduler.onWindow(book, 30..32))
        assertEquals(target(32), scheduler.onCompleted(book, 31))
    }

    @Test
    fun `blank book url is rejected without issuing a target`() {
        val scheduler = NovelAudioAutoPrefetchScheduler()

        assertNull(scheduler.onWindow("", 1..3))
        assertNull(scheduler.onCompleted("", 1))
    }

    private fun target(index: Int) =
        NovelAudioAutoPrefetchScheduler.Target(book, index)
}
