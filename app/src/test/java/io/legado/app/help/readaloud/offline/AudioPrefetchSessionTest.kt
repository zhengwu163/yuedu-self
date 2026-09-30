package io.legado.app.help.readaloud.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioPrefetchSessionTest {

    private val book = "book-a"
    private val otherBook = "book-b"
    private val session = AudioPrefetchSession()

    @Test
    fun `new instance and position updates without user play have no authorization`() {
        assertFalse(session.isAllowed(null, book, 1))
        assertTrue(session.updatePosition(null, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(null, book, 1))
    }

    @Test
    fun `explicit play authorizes exactly the next three chapters`() {
        val token = session.onUserPlay(book)
        assertNotNull(token)
        assertEquals(5..7, session.updatePosition(token, book, 4, 20))
        (5..7).forEach { assertTrue(session.isAllowed(token, book, it)) }
        listOf(-1, 0, 4, 8, 20).forEach {
            assertFalse(session.isAllowed(token, book, it))
        }
    }

    @Test
    fun `play alone does not authorize targets until a valid position is supplied`() {
        val token = session.onUserPlay(book)
        assertNotNull(token)
        assertFalse(session.isAllowed(token, book, 1))
    }

    @Test
    fun `rolling forward rejects queued targets outside the latest window`() {
        val token = session.onUserPlay(book)
        val oldWindow = session.updatePosition(token, book, 0, 20)
        assertEquals(1..3, oldWindow)
        assertEquals(3..5, session.updatePosition(token, book, 2, 20))
        assertEquals(1..3, oldWindow)
        assertFalse(session.isAllowed(token, book, 1))
        assertFalse(session.isAllowed(token, book, 2))
        (3..5).forEach { assertTrue(session.isAllowed(token, book, it)) }
        assertFalse(session.isAllowed(token, book, 6))
    }

    @Test
    fun `seeking backward replaces rather than merges the window`() {
        val token = session.onUserPlay(book)
        assertEquals(9..11, session.updatePosition(token, book, 8, 20))
        assertEquals(2..4, session.updatePosition(token, book, 1, 20))
        assertFalse(session.isAllowed(token, book, 9))
        assertTrue(session.isAllowed(token, book, 2))
        assertFalse(session.isAllowed(token, book, 5))
    }

    @Test
    fun `book end returns only remaining chapters and then no targets`() {
        val token = session.onUserPlay(book)
        assertEquals(8..9, session.updatePosition(token, book, 7, 10))
        assertEquals(9..9, session.updatePosition(token, book, 8, 10))
        assertFalse(session.isAllowed(token, book, 8))
        assertTrue(session.isAllowed(token, book, 9))
        assertFalse(session.isAllowed(token, book, 10))
        assertTrue(session.updatePosition(token, book, 9, 10).isEmpty())
        assertFalse(session.isAllowed(token, book, 9))
    }

    @Test
    fun `single chapter book has no prefetch window`() {
        val token = session.onUserPlay(book)
        assertNotNull(token)
        assertTrue(session.updatePosition(token, book, 0, 1).isEmpty())
        assertFalse(session.isAllowed(token, book, 0))
        assertFalse(session.isAllowed(token, book, 1))
    }

    @Test
    fun `chapter count shrink removes formerly allowed targets`() {
        val token = session.onUserPlay(book)
        assertEquals(5..7, session.updatePosition(token, book, 4, 10))
        assertEquals(5..5, session.updatePosition(token, book, 4, 6))
        assertTrue(session.isAllowed(token, book, 5))
        assertFalse(session.isAllowed(token, book, 6))
        assertFalse(session.isAllowed(token, book, 7))
    }

    @Test
    fun `pause revokes token and late position callback cannot restore it`() {
        val token = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(token, book, 0, 10))
        session.onPause()
        assertRevoked(token)
    }

    @Test
    fun `stop revokes token and late position callback cannot restore it`() {
        val token = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(token, book, 0, 10))
        session.onStop()
        assertRevoked(token)
    }

    @Test
    fun `book change revokes authorization without authorizing the opened book`() {
        val token = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(token, book, 0, 10))
        session.onBookChanged()
        assertRevoked(token)
        assertTrue(session.updatePosition(token, otherBook, 0, 10).isEmpty())
        assertFalse(session.isAllowed(token, otherBook, 1))
        assertFalse(session.isAllowed(null, otherBook, 1))
    }

    @Test
    fun `starting another book replaces the sole active authorization`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        val current = session.onUserPlay(otherBook)
        assertNotNull(current)
        assertNotSame(old, current)
        assertFalse(session.isAllowed(current, otherBook, 1))
        assertEquals(5..7, session.updatePosition(current, otherBook, 4, 10))
        assertTrue(session.updatePosition(old, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(old, book, 1))
        assertFalse(session.isAllowed(current, book, 5))
        assertTrue(session.isAllowed(current, otherBook, 5))
    }

    @Test
    fun `playing the same book again issues a distinct token and clears old window`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        val current = session.onUserPlay(book)
        assertNotNull(current)
        assertNotSame(old, current)
        assertFalse(session.isAllowed(old, book, 1))
        assertFalse(session.isAllowed(current, book, 1))
        assertEquals(5..7, session.updatePosition(current, book, 4, 10))
        assertTrue(session.updatePosition(old, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(current, book, 1))
        assertTrue(session.isAllowed(current, book, 5))
    }

    @Test
    fun `resume after pause cannot revive a token from before pause`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        session.onPause()
        val current = session.onUserPlay(book)
        assertNotSame(old, current)
        assertEquals(1..3, session.updatePosition(current, book, 0, 10))
        assertRevoked(old)
        assertTrue(session.isAllowed(current, book, 1))
    }

    @Test
    fun `returning to a previous book cannot revive its old token`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        session.onBookChanged()
        session.onUserPlay(otherBook)
        session.onBookChanged()
        val current = session.onUserPlay(book)
        assertNotSame(old, current)
        assertEquals(1..3, session.updatePosition(current, book, 0, 10))
        assertRevoked(old)
        assertTrue(session.isAllowed(current, book, 1))
    }

    @Test
    fun `restarted instance rejects tokens from an earlier instance even for same book`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        val restarted = AudioPrefetchSession()
        assertFalse(restarted.isAllowed(old, book, 1))
        assertTrue(restarted.updatePosition(old, book, 0, 10).isEmpty())
        val current = restarted.onUserPlay(book)
        assertNotSame(old, current)
        assertEquals(1..3, restarted.updatePosition(current, book, 0, 10))
        assertFalse(restarted.isAllowed(old, book, 1))
        assertTrue(session.updatePosition(current, book, 4, 10).isEmpty())
        assertFalse(session.isAllowed(current, book, 1))
        assertTrue(session.isAllowed(old, book, 1))
    }

    @Test
    fun `blank play fails closed and revokes any previous authorization`() {
        listOf("", " ", "\t\n").forEach { invalidBook ->
            val token = session.onUserPlay(book)
            assertEquals(1..3, session.updatePosition(token, book, 0, 10))
            assertNull(session.onUserPlay(invalidBook))
            assertRevoked(token)
        }
    }

    @Test
    fun `invalid position or count revokes authorization until another explicit play`() {
        val invalidPositions = listOf(
            -1 to 10,
            Int.MIN_VALUE to 10,
            0 to 0,
            0 to -1,
            0 to Int.MIN_VALUE,
            10 to 10,
            11 to 10,
            Int.MAX_VALUE to Int.MAX_VALUE
        )
        invalidPositions.forEach { (index, count) ->
            val token = session.onUserPlay(book)
            assertEquals(1..3, session.updatePosition(token, book, 0, 10))
            assertTrue(session.updatePosition(token, book, index, count).isEmpty())
            assertRevoked(token)
        }
    }

    @Test
    fun `position update for blank or mismatched book fails closed`() {
        listOf("", " \n", otherBook).forEach { invalidBook ->
            val token = session.onUserPlay(book)
            assertEquals(1..3, session.updatePosition(token, book, 0, 10))
            assertTrue(session.updatePosition(token, invalidBook, 0, 10).isEmpty())
            assertRevoked(token)
        }
    }

    @Test
    fun `invalid authorization queries deny without changing valid state`() {
        val token = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(token, book, 0, 10))
        listOf("", " ", otherBook).forEach {
            assertFalse(session.isAllowed(token, it, 1))
        }
        listOf(Int.MIN_VALUE, -1, 0, 4, 10, Int.MAX_VALUE).forEach {
            assertFalse(session.isAllowed(token, book, it))
        }
        assertFalse(session.isAllowed(null, book, 1))
        assertTrue(session.isAllowed(token, book, 1))
    }

    @Test
    fun `stale and missing tokens cannot move or revoke a new session`() {
        val old = session.onUserPlay(book)
        val current = session.onUserPlay(book)
        assertEquals(5..7, session.updatePosition(current, book, 4, 10))
        assertTrue(session.updatePosition(old, book, 0, 10).isEmpty())
        assertTrue(session.updatePosition(old, "", -1, -1).isEmpty())
        assertTrue(session.updatePosition(null, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(current, book, 1))
        assertTrue(session.isAllowed(current, book, 5))
    }

    @Test
    fun `maximum chapter count does not overflow or allocate a book sized window`() {
        val max = Int.MAX_VALUE
        val token = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(token, book, 0, max))
        assertEquals((max - 3)..(max - 1), session.updatePosition(token, book, max - 4, max))
        assertEquals((max - 1)..(max - 1), session.updatePosition(token, book, max - 2, max))
        assertTrue(session.isAllowed(token, book, max - 1))
        assertFalse(session.isAllowed(token, book, max))
        assertFalse(session.isAllowed(token, book, Int.MIN_VALUE))
        assertTrue(session.updatePosition(token, book, max - 1, max).isEmpty())
        assertFalse(session.isAllowed(token, book, max - 1))
    }

    @Test
    fun `lifecycle invalidation before play is safe and never grants permission`() {
        repeat(2) {
            session.onPause()
            session.onStop()
            session.onBookChanged()
        }
        assertTrue(session.updatePosition(null, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(null, book, 1))
        val token = session.onUserPlay(book)
        assertNotNull(token)
        assertEquals(1..3, session.updatePosition(token, book, 0, 10))
    }

    @Test
    fun `concurrent user plays leave exactly one authorized token`() {
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (1..24).map {
                executor.submit(Callable {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    session.onUserPlay(book)
                })
            }
            start.countDown()
            val tokens = futures.map { it.get(5, TimeUnit.SECONDS) }
            tokens.forEach { assertNotNull(it) }
            val winners = tokens.filter {
                !session.updatePosition(it, book, 0, 10).isEmpty()
            }
            assertEquals(1, winners.size)
            assertEquals(1, tokens.count { session.isAllowed(it, book, 1) })
            session.onStop()
            tokens.forEach { assertRevoked(it) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `callback released after stop and replay cannot modify the new window`() {
        val old = session.onUserPlay(book)
        assertEquals(1..3, session.updatePosition(old, book, 0, 10))
        val executor = Executors.newSingleThreadExecutor()
        val releaseCallback = CountDownLatch(1)
        try {
            val callback = executor.submit(Callable {
                assertTrue(releaseCallback.await(5, TimeUnit.SECONDS))
                assertTrue(session.updatePosition(old, book, 0, 10).isEmpty())
                assertFalse(session.isAllowed(old, book, 1))
            })
            session.onStop()
            val current = session.onUserPlay(book)
            assertEquals(5..7, session.updatePosition(current, book, 4, 10))
            releaseCallback.countDown()
            callback.get(5, TimeUnit.SECONDS)
            assertFalse(session.isAllowed(current, book, 1))
            assertTrue(session.isAllowed(current, book, 5))
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertRevoked(token: AudioPrefetchSession.Token?) {
        assertFalse(session.isAllowed(token, book, 1))
        assertTrue(session.updatePosition(token, book, 0, 10).isEmpty())
        assertFalse(session.isAllowed(token, book, 1))
    }
}
