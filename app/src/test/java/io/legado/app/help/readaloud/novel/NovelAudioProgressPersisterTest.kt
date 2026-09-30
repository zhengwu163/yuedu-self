package io.legado.app.help.readaloud.novel

import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 听书播放位置的持久化节流。
 *
 * 杀进程后要能续播，就必须在播放过程中持久化章内位置；
 * 但每段推进都写 Room 会造成频繁磁盘写入，因此需要节流。
 * 节流规则本身是可验证逻辑：章节切换与停止必须立即写入，不能被节流窗口吞掉。
 */
class NovelAudioProgressPersisterTest {

    private val book = "https://example.test/book"

    @Test
    fun `the first progress of a chapter is written immediately`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 120, atMillis = 1_000L)

        assertEquals(listOf(Triple(book, 3, 120)), writes)
    }

    @Test
    fun `a later progress inside the throttle window is skipped`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.onProgress(book, 3, 180, atMillis = 3_000L)

        assertEquals(1, writes.size)
    }

    @Test
    fun `a progress past the throttle window is written`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.onProgress(book, 3, 180, atMillis = 12_000L)

        assertEquals(
            listOf(Triple(book, 3, 120), Triple(book, 3, 180)),
            writes
        )
    }

    @Test
    fun `a chapter change is written immediately even inside the window`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.onProgress(book, 4, 0, atMillis = 1_500L)

        assertEquals(
            listOf(Triple(book, 3, 120), Triple(book, 4, 0)),
            writes
        )
    }

    @Test
    fun `a book change is written immediately even inside the window`() {
        val writes = writes()
        val persister = persister(writes)
        val other = "https://example.test/other"

        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.onProgress(other, 3, 120, atMillis = 1_500L)

        assertEquals(
            listOf(Triple(book, 3, 120), Triple(other, 3, 120)),
            writes
        )
    }

    @Test
    fun `stopping flushes the latest position regardless of the window`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.onProgress(book, 3, 300, atMillis = 2_000L)
        persister.flush()

        assertEquals(
            listOf(Triple(book, 3, 120), Triple(book, 3, 300)),
            writes
        )
    }

    @Test
    fun `flush without any pending position writes nothing`() {
        val writes = writes()
        val persister = persister(writes)

        persister.flush()
        persister.onProgress(book, 3, 120, atMillis = 1_000L)
        persister.flush()
        persister.flush()

        assertEquals(1, writes.size)
    }

    @Test
    fun `a negative position is never persisted`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, -1, atMillis = 1_000L)

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a blank book is never persisted`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress("", 3, 10, atMillis = 1_000L)

        assertTrue(writes.isEmpty())
    }

    @Test
    fun `a backwards position inside the window does not overwrite the latest`() {
        val writes = writes()
        val persister = persister(writes)

        persister.onProgress(book, 3, 300, atMillis = 1_000L)
        persister.onProgress(book, 3, 100, atMillis = 2_000L)
        persister.flush()

        // 同章回退通常来自迟到的段进度事件，不应把进度写回更早的位置。
        assertEquals(listOf(Triple(book, 3, 300)), writes)
    }

    private fun writes() = Collections.synchronizedList(mutableListOf<Triple<String, Int, Int>>())

    private fun persister(writes: MutableList<Triple<String, Int, Int>>) =
        NovelAudioProgressPersister { bookUrl, chapterIndex, position ->
            writes += Triple(bookUrl, chapterIndex, position)
        }
}
