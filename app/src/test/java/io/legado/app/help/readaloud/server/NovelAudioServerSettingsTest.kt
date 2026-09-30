package io.legado.app.help.readaloud.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class NovelAudioServerSettingsTest {
    private val token = "test-only-server-token"
    private val url = "https://audio.invalid/"
    private val blob = object : NovelAudioCredentialBlob {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
    }
    private val store = NovelAudioServerConfigStore(
        blob, NovelAudioServerCredentialCipher { SecretKeySpec(ByteArray(32) { 5 }, "AES") }
    )
    private fun draft() = NovelAudioServerSettingsDraft()
        .withUrl(url).withToken(token)

    @Test
    fun `changing URL clears token and HTTP consent`() {
        val original = draft().withHttpConsent(true)
        val changed = original.withUrl("http://other.invalid/")
        assertEquals("", changed.token)
        assertFalse(changed.allowInsecureHttp)
        assertEquals(token, original.token)
        assertEquals(token, original.withUrl(url).token)
    }

    @Test
    fun `saving then loading and clearing uses encrypted paired store`() {
        val settings = NovelAudioServerSettings(store)
        settings.save(draft())
        assertEquals(url, settings.load().baseUrl)
        assertEquals(token, settings.load().token)
        assertFalse(String(blob.bytes!!).contains(token))
        settings.clear()
        assertEquals("", settings.load().token)
        assertNull(store.load())
    }

    @Test
    fun `invalid replacement preserves saved pair`() {
        val settings = NovelAudioServerSettings(store)
        settings.save(draft())
        assertThrows(NovelAudioServerException::class.java) {
            settings.save(draft().withUrl("https://new.invalid/"))
        }
        assertEquals(url, store.load()!!.baseUrl)
        assertEquals(token, store.load()!!.token)
    }

    @Test
    fun `HTTP requires explicit consent before saving or probing`() = runBlocking {
        var calls = 0
        val settings = NovelAudioServerSettings(store) {
            calls++
            ServerHealth("ok", "1", true, true)
        }
        val http = draft().withUrl("http://audio.invalid/").withToken(token)
        assertThrows(NovelAudioServerException::class.java) { settings.save(http) }
        val failure = kotlin.runCatching { settings.testConnection(http) }.exceptionOrNull()
        assertTrue(failure is NovelAudioServerException)
        assertEquals(0, calls)
        assertNull(store.load())
        settings.save(http.withHttpConsent(true))
        assertTrue(settings.load().allowInsecureHttp)
    }

    @Test
    fun `health checks draft without replacing saved configuration`() = runBlocking {
        val settings = NovelAudioServerSettings(store) {
            assertEquals("https://draft.invalid/", it.baseUrl)
            assertEquals("new-test-token", it.token)
            ServerHealth("ok", "1", true, true)
        }
        settings.save(draft())
        settings.testConnection(draft().withUrl("https://draft.invalid/").withToken("new-test-token"))
        assertEquals(url, store.load()!!.baseUrl)
        assertEquals(token, store.load()!!.token)
    }

    @Test
    fun `health requires both director and TTS ready`() = runBlocking {
        for (health in listOf(
            ServerHealth("ok", "1", false, true),
            ServerHealth("ok", "1", true, false),
            ServerHealth("bad", "1", true, true),
            ServerHealth("ok", "2", true, true)
        )) {
            val settings = NovelAudioServerSettings(store) { health }
            val error = kotlin.runCatching { settings.testConnection(draft()) }.exceptionOrNull()
            assertTrue(error is NovelAudioServerException)
            assertEquals("UNAVAILABLE", (error as NovelAudioServerException).kind)
            assertNull(store.load())
        }
    }

    @Test
    fun `unexpected probe errors are sanitized before coroutine logging`() = runBlocking {
        val settings = NovelAudioServerSettings(store) { throw IllegalStateException("$url $token") }
        val error = kotlin.runCatching { settings.testConnection(draft()) }.exceptionOrNull()!!
        assertFalse(error.stackTraceToString().contains(token))
        assertFalse(error.stackTraceToString().contains(url))
        assertFalse(draft().toString().contains(token))
        assertFalse(draft().toString().contains(url))
    }

    @Test
    fun `probe cancellation is not turned into a connection failure`() = runBlocking {
        val cancellation = CancellationException("cancelled")
        val settings = NovelAudioServerSettings(store) { throw cancellation }
        assertSame(cancellation, kotlin.runCatching { settings.testConnection(draft()) }.exceptionOrNull())
    }
}
