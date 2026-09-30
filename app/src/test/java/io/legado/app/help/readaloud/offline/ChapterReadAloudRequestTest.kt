package io.legado.app.help.readaloud.offline

import org.junit.Assert.*
import org.junit.Test

class ChapterReadAloudRequestTest {
    private val requests = ChapterReadAloudRequest()

    @Test fun `loaded chapter preserves its original continuation and completes once`() {
        val request = requests.begin("book", 2, "original")
        assertTrue(requests.bind(request, "book", 2, 10))
        assertSame(request, requests.complete("book", 2, 10))
        assertEquals("original", request.continuation)
        assertNull(requests.complete("book", 2, 10))
    }

    @Test fun `old lookup cannot attach a replacement request`() {
        val old = requests.begin("book", 2, "old")
        val current = requests.begin("book", 2, "new")
        assertFalse(requests.bind(old, "book", 2, 10))
        assertNull(requests.complete("book", 2, 10))
        assertTrue(requests.bind(current, "book", 2, 11))
        assertSame(current, requests.complete("book", 2, 11))
    }

    @Test fun `wrong book chapter or generation cannot complete pending playback`() {
        val request = requests.begin("book", 2, "original")
        assertTrue(requests.bind(request, "book", 2, 10))
        assertNull(requests.complete("other", 2, 10))
        assertNull(requests.complete("book", 3, 10))
        assertNull(requests.complete("book", 2, 11))
        assertSame(request, requests.complete("book", 2, 10))
    }

    @Test fun `in flight text load can be explicitly joined without replacing its generation`() {
        val request = requests.begin("book", 2, "original")
        assertTrue(requests.bind(request, "book", 2, 10))
        assertTrue(requests.bind(request, "book", 2, 10))
        assertFalse(requests.bind(request, "book", 2, 11))
        assertSame(request, requests.complete("book", 2, 10))
    }

    @Test fun `cached chapter completes original request without load generation`() {
        val request = requests.begin("book", 2, null)
        assertSame(request, requests.completeCached(request, "book", 2))
        assertNull(request.continuation)
        assertNull(requests.completeCached(request, "book", 2))
    }

    @Test fun `cached completion cannot consume a newer request`() {
        val old = requests.begin("book", 2, "old")
        val current = requests.begin("book", 2, "new")
        assertNull(requests.completeCached(old, "book", 2))
        assertSame(current, requests.completeCached(current, "book", 2))
    }

    @Test fun `clearing rejects both delayed lookup and delayed completion`() {
        val request = requests.begin("book", 2, "original")
        requests.bind(request, "book", 2, 10)
        requests.clear()
        assertFalse(requests.bind(request, "book", 2, 10))
        assertNull(requests.complete("book", 2, 10))
        assertNull(requests.completeCached(request, "book", 2))
    }

    @Test fun `failed old generation does not clear replacement`() {
        val old = requests.begin("book", 2, "old")
        requests.bind(old, "book", 2, 10)
        val current = requests.begin("book", 2, "new")
        requests.bind(current, "book", 2, 11)
        requests.cancel("book", 2, 10)
        assertSame(current, requests.complete("book", 2, 11))
    }

    @Test fun `failed matching load clears its playback request`() {
        val request = requests.begin("book", 2, "original")
        requests.bind(request, "book", 2, 10)
        requests.cancel("book", 2, 10)
        assertNull(requests.complete("book", 2, 10))
    }
}
