package io.legado.app.help.readaloud.offline

import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AUTO 预取的执行循环：按窗口顺序逐章准备，且不得抢占当前章的准备槽位。
 *
 * 本地已完整的章节必须跳过而不重新合成；授权一旦撤销立刻停止，
 * 失败只前进不重试，避免失败章反复消耗不可退款的额度。
 */
class NovelAudioAutoPrefetchCoordinatorTest {

    private val book = "https://example.test/book"

    @Test
    fun `window prepares the three following chapters in order`() {
        val prepared = log()
        val coordinator = coordinator(prepared = { _, index -> prepared += index; true })

        runBlocking { coordinator.run(book, 11..13) }

        assertEquals(listOf(11, 12, 13), prepared)
    }

    @Test
    fun `chapters already complete locally are skipped without synthesizing`() {
        val prepared = log()
        val coordinator = coordinator(
            ready = { _, index -> index == 12 },
            prepared = { _, index -> prepared += index; true }
        )

        runBlocking { coordinator.run(book, 11..13) }

        assertEquals(listOf(11, 13), prepared)
    }

    @Test
    fun `revoked authorization stops the loop before the next chapter`() {
        val prepared = log()
        val coordinator = coordinator(
            allowed = { index -> index < 12 },
            prepared = { _, index -> prepared += index; true }
        )

        runBlocking { coordinator.run(book, 11..13) }

        assertEquals(listOf(11), prepared)
    }

    @Test
    fun `a failed chapter advances instead of being retried`() {
        val attempts = log()
        val coordinator = coordinator(
            prepared = { _, index -> attempts += index; index != 5 }
        )

        runBlocking { coordinator.run(book, 5..7) }

        assertEquals(listOf(5, 6, 7), attempts)
    }

    @Test
    fun `empty window prepares nothing`() {
        val prepared = log()
        val coordinator = coordinator(prepared = { _, index -> prepared += index; true })

        runBlocking { coordinator.run(book, IntRange.EMPTY) }

        assertTrue(prepared.isEmpty())
    }

    @Test
    fun `cancellation propagates and does not continue the window`() {
        val prepared = log()
        val coordinator = coordinator(
            prepared = { _, index ->
                prepared += index
                if (index == 6) throw CancellationException("cancelled")
                true
            }
        )

        try {
            runBlocking { coordinator.run(book, 5..7) }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertEquals(listOf(5, 6), prepared)
        }
    }

    @Test
    fun `an unexpected failure stops the window without escaping to the caller`() {
        val prepared = log()
        val coordinator = coordinator(
            prepared = { _, index ->
                prepared += index
                if (index == 6) throw IllegalStateException("boom")
                true
            }
        )

        runBlocking { coordinator.run(book, 5..7) }

        assertEquals(listOf(5, 6), prepared)
    }

    @Test
    fun `blank book url never prepares anything`() {
        val prepared = log()
        val coordinator = coordinator(prepared = { _, index -> prepared += index; true })

        runBlocking { coordinator.run("", 1..3) }

        assertTrue(prepared.isEmpty())
    }

    @Test
    fun `revoke clears the window so a later run starts from the new window`() {
        val prepared = log()
        val coordinator = coordinator(prepared = { _, index -> prepared += index; true })

        runBlocking { coordinator.run(book, 1..1) }
        coordinator.revoke()
        runBlocking { coordinator.run(book, 9..9) }

        assertEquals(listOf(1, 9), prepared)
    }

    private fun log() = Collections.synchronizedList(mutableListOf<Int>())

    private fun coordinator(
        allowed: (Int) -> Boolean = { true },
        ready: suspend (String, Int) -> Boolean = { _, _ -> false },
        prepared: suspend (String, Int) -> Boolean
    ) = NovelAudioAutoPrefetchCoordinator(
        isAllowed = allowed,
        isLocallyReady = ready,
        prepare = prepared
    )
}
