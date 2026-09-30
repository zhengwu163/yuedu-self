package io.legado.app.help.readaloud.server

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class NovelAudioServerConfigStoreTest {
    private class MemoryBlob : NovelAudioCredentialBlob {
        var value: ByteArray? = null
        var failWrite = false
        override fun read(): ByteArray? = value?.clone()
        override fun write(value: ByteArray) {
            if (failWrite) throw IOException("secret-host secret-token")
            this.value = value.clone()
        }
    }

    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var failEncrypt = false
    private var createRequests = 0
    private val cipher = NovelAudioServerCredentialCipher { create ->
        if (create) createRequests++
        if (create && failEncrypt) throw IOException("secret-token")
        key ?: throw IOException("secret-host")
    }
    private val blob = MemoryBlob()
    private val store = NovelAudioServerConfigStore(blob, cipher)

    @Test fun `unconfigured load does not access or create key`() {
        key = null
        assertNull(store.load())
        assertEquals(0, createRequests)
    }

    @Test fun `credentials survive store recreation without plaintext storage`() {
        store.replace("https://example.com/prefix", "secret-token")
        val recreated = NovelAudioServerConfigStore(blob, cipher).load()!!
        assertEquals("https://example.com/prefix", recreated.baseUrl)
        assertEquals("secret-token", recreated.token)
        assertFalse(blob.value!!.toString(Charsets.UTF_8).contains("secret-token"))
        assertFalse(blob.value!!.toString(Charsets.UTF_8).contains("example.com"))
    }

    @Test fun `replacement preserves old immutable pair`() {
        store.replace("https://old.example", "old-token")
        val old = store.load()!!
        store.replace("https://new.example", "new-token")
        assertEquals("https://old.example/", old.baseUrl)
        assertEquals("old-token", old.token)
        assertEquals("https://new.example/", store.load()!!.baseUrl)
        assertEquals("new-token", store.load()!!.token)
    }

    @Test fun `URL change requires explicit token and rejects invalid input before writing`() {
        store.replace("https://old.example", "old-token")
        val original = blob.value!!.clone()
        for ((url, token) in listOf(
            "https://new.example" to "",
            "https://user:pass@new.example" to "new-token",
            "https://new.example?token=secret" to "new-token",
            "https://new.example/v1" to "new-token",
            "file:///private" to "new-token",
            "https://new.example" to "bad\nToken",
            "https://new.example" to "bad token",
            "https://new.example" to "t".repeat(4097)
        )) {
            error("CONFIG") { store.replace(url, token) }
            assertArrayEquals(original, blob.value)
        }
    }

    @Test fun `HTTP requires explicit consent and persists accepted pair`() {
        error("CONFIG") { store.replace("http://127.0.0.1:8787", "local-token") }
        assertNull(blob.value)
        store.replace("http://127.0.0.1:8787", "local-token", allowInsecureHttp = true)
        assertEquals("http://127.0.0.1:8787/", store.load()!!.baseUrl)
    }

    @Test fun `failed encryption preserves existing credentials`() {
        store.replace("https://old.example", "old-token")
        failEncrypt = true
        error("STORAGE") { store.replace("https://new.example", "new-token") }
        assertEquals("old-token", store.load()!!.token)
    }

    @Test fun `failed atomic commit preserves existing credentials`() {
        store.replace("https://old.example", "old-token")
        blob.failWrite = true
        error("STORAGE") { store.replace("https://new.example", "new-token") }
        assertEquals("https://old.example/", store.load()!!.baseUrl)
    }

    @Test fun `clear commits empty state and survives recreation`() {
        store.replace("https://old.example", "old-token")
        store.clear()
        assertNull(NovelAudioServerConfigStore(blob, cipher).load())
    }

    @Test fun `failed clear reports failure and preserves old state`() {
        store.replace("https://old.example", "old-token")
        blob.failWrite = true
        error("STORAGE") { store.clear() }
        assertEquals("old-token", store.load()!!.token)
    }

    @Test fun `tampered ciphertext fails without deletion or key regeneration`() {
        store.replace("https://old.example", "old-token")
        blob.value!![blob.value!!.lastIndex] = (blob.value!!.last().toInt() xor 1).toByte()
        val corrupted = blob.value!!.clone()
        val count = createRequests
        error("STORAGE") { store.load() }
        assertArrayEquals(corrupted, blob.value)
        assertEquals(count, createRequests)
    }

    @Test fun `missing key fails without replacing saved ciphertext`() {
        store.replace("https://old.example", "old-token")
        val saved = blob.value!!.clone()
        key = null
        val count = createRequests
        error("STORAGE") { store.load() }
        assertArrayEquals(saved, blob.value)
        assertEquals(count, createRequests)
    }

    @Test fun `encryption uses fresh nonce`() {
        val content = "same-input".toByteArray()
        val one = cipher.encrypt(content)
        val two = cipher.encrypt(content)
        assertFalse(one.contentEquals(two))
        assertArrayEquals(content, cipher.decrypt(one))
        assertArrayEquals(content, cipher.decrypt(two))
    }

    @Test fun `malformed blob version and oversize are fixed storage errors`() {
        for (value in listOf(byteArrayOf(9, 12, 1), ByteArray(16 * 1024 + 1))) {
            blob.value = value
            error("STORAGE") { store.load() }
        }
    }

    @Test fun `credentials string representation redacts both fields`() {
        val credentials = NovelAudioServerCredentials.create("https://secret-host", "secret-token")
        assertFalse(credentials.toString().contains("secret-host"))
        assertFalse(credentials.toString().contains("secret-token"))
    }

    @Test fun `corrupt decrypted record cannot become a configured server`() {
        blob.value = cipher.encrypt("invalid-record secret-token".toByteArray())
        error("STORAGE") { store.load() }
    }

    private fun error(kind: String, action: () -> Any?) {
        try {
            action()
            fail("expected $kind")
        } catch (e: NovelAudioServerException) {
            assertEquals(kind, e.kind)
            assertNull(e.cause)
            assertFalse(e.toString().contains("secret-token"))
            assertFalse(e.toString().contains("secret-host"))
        }
    }
}
