package io.legado.app.help.readaloud.offline

import io.legado.app.help.readaloud.server.NovelAudioServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NovelAudioRetryPolicyTest {

    @Test
    fun `retries transient server failures at most three attempts`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val result = NovelAudioRetryPolicy(delayMillis = { waits += it }).run {
            attempts++
            if (attempts < 3) throw NovelAudioServerException("UNAVAILABLE")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), waits)
    }

    @Test
    fun `does not retry protocol failures`() = runBlocking {
        var attempts = 0
        assertThrows(NovelAudioServerException::class.java) {
            runBlocking {
                NovelAudioRetryPolicy(delayMillis = {}).run {
                    attempts++
                    throw NovelAudioServerException("PROTOCOL")
                }
            }
        }
        assertEquals(1, attempts)
    }
}
