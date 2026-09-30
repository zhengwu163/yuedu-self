package io.legado.app.help.readaloud.offline

import org.junit.Assert.*
import org.junit.Test

class ReadAloudAssemblyStateTest {
    private val state = ReadAloudAssemblyState<Any>()

    @Test fun `matching assembly publishes exactly once`() {
        val request = state.begin()
        val chapter = Any()
        assertTrue(state.complete(request, chapter))
        assertSame(chapter, state.prepared)
        assertFalse(state.complete(request, Any()))
        assertSame(chapter, state.prepared)
    }

    @Test fun `new assembly hides previously prepared chapter immediately`() {
        state.complete(state.begin(), Any())
        state.begin()
        assertNull(state.prepared)
    }

    @Test fun `pause while assembling rejects late result`() {
        val request = state.begin()
        state.cancelPending()
        assertFalse(state.complete(request, Any()))
        assertNull(state.prepared)
    }

    @Test fun `resume after interrupted assembly accepts only replacement`() {
        val old = state.begin()
        state.cancelPending()
        val current = state.begin()
        assertFalse(state.complete(old, Any()))
        val chapter = Any()
        assertTrue(state.complete(current, chapter))
        assertSame(chapter, state.prepared)
    }

    @Test fun `late previous assembly cannot overwrite newer chapter`() {
        val old = state.begin()
        val current = state.begin()
        val chapter = Any()
        assertTrue(state.complete(current, chapter))
        assertFalse(state.complete(old, Any()))
        assertSame(chapter, state.prepared)
    }

    @Test fun `ordinary pause preserves completed chapter for precise resume`() {
        val chapter = Any()
        assertTrue(state.complete(state.begin(), chapter))
        state.cancelPending()
        assertSame(chapter, state.prepared)
    }

    @Test fun `destroy clears prepared chapter and pending result`() {
        state.complete(state.begin(), Any())
        state.clear()
        assertNull(state.prepared)
        val pending = state.begin()
        state.clear()
        assertFalse(state.complete(pending, Any()))
    }

    @Test fun `another service cannot complete this assembly`() {
        val foreign = ReadAloudAssemblyState<Any>().begin()
        val current = state.begin()
        assertFalse(state.complete(foreign, Any()))
        assertTrue(state.complete(current, Any()))
    }
}
