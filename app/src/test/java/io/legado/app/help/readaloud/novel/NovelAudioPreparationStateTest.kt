package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioPreparationStateTest {

    @Test
    fun `success clears only the request instance that started the work`() {
        val state = NovelAudioPreparationState()
        val first = state.replace("book", 3, "AUTO")
        val second = state.replace("book", 3, "AUTO")

        assertFalse(state.clearIfCurrent(first))
        assertSame(second, state.current())
        assertTrue(state.clearIfCurrent(second))
        assertNull(state.current())
    }
}
