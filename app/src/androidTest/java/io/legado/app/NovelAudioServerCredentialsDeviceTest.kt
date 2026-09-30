package io.legado.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.help.readaloud.server.AndroidNovelAudioCredentialBlob
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** 隔离测试 alias/file；绝不读取或清除用户的服务器配置。 */
@RunWith(AndroidJUnit4::class)
class NovelAudioServerCredentialsDeviceTest {
    @Test fun encryptedCredentialsSurviveReloadAndClear() = withIsolatedStore { store, file, alias ->
        store.replace("https://example.com", "device-test-token")
        val loaded = NovelAudioServerConfigStore(
            AndroidNovelAudioCredentialBlob(file), NovelAudioAndroidConfigStore.cipher(alias)
        ).load()!!
        assertEquals("https://example.com/", loaded.baseUrl)
        assertEquals("device-test-token", loaded.token)
        assertFalse(file.readText().contains("device-test-token"))
        store.clear()
        assertNull(store.load())
    }

    @Test fun missingKeystoreKeyIsNotSilentlyReplaced() = withIsolatedStore { store, _, alias ->
        store.replace("https://example.com", "device-test-token")
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        try {
            store.load()
            fail("missing key must fail")
        } catch (e: NovelAudioServerException) {
            assertEquals("STORAGE", e.kind)
        }
        assertFalse(keys.containsAlias(alias))
    }

    private fun withIsolatedStore(action: (NovelAudioServerConfigStore, File, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val alias = "legado.novel_audio_server.test.$id"
        val file = File(context.noBackupFilesDir, "novel-audio-test-$id")
        try {
            action(NovelAudioServerConfigStore(
                AndroidNovelAudioCredentialBlob(file), NovelAudioAndroidConfigStore.cipher(alias)
            ), file, alias)
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            listOf(file, File(file.path + ".new"), File(file.path + ".bak")).forEach { it.delete() }
        }
    }
}
