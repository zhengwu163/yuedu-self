package io.legado.app.help.readaloud.server

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** 测真实原子文件实现；Keystore provider 需另跑设备测试。 */
class NovelAudioAndroidConfigStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `file replacement and recreation load the whole blob`() {
        val file = File(temp.root, "credentials")
        val blob = AndroidNovelAudioCredentialBlob(file)
        assertNull(blob.read())
        blob.write(byteArrayOf(1, 2, 3))
        blob.write(byteArrayOf(4, 5))
        assertArrayEquals(byteArrayOf(4, 5), AndroidNovelAudioCredentialBlob(file).read())
    }

    @Test fun `unfinished new file never replaces committed credentials`() {
        val file = File(temp.root, "credentials")
        val blob = AndroidNovelAudioCredentialBlob(file)
        blob.write(byteArrayOf(1, 2, 3))
        File(temp.root, "credentials.new").writeBytes(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(1, 2, 3), AndroidNovelAudioCredentialBlob(file).read())
    }

    @Test fun `orphaned temporary file is reported and preserved`() {
        val file = File(temp.root, "credentials")
        val pending = File(temp.root, "credentials.new")
        pending.writeBytes(byteArrayOf(1, 2))
        expectStorage { AndroidNovelAudioCredentialBlob(file).read() }
        assertArrayEquals(byteArrayOf(1, 2), pending.readBytes())
    }

    @Test fun `successful commit does not report failure from a subsequent read`() {
        val normal = File(temp.root, "credentials")
        normal.writeBytes(byteArrayOf(1))
        val file = object : File(normal.path) {
            override fun exists(): Boolean {
                if (super.exists() && super.length() == 3L) throw IOException("read unavailable after commit")
                return super.exists()
            }
        }
        AndroidNovelAudioCredentialBlob(file).write(byteArrayOf(2, 3, 4))
        assertArrayEquals(byteArrayOf(2, 3, 4), normal.readBytes())
    }

    @Test fun `unreadable or oversized files fail instead of appearing unconfigured`() {
        val file = File(temp.root, "credentials")
        file.writeBytes(ByteArray(NovelAudioServerConfigStore.MAX_BLOB_BYTES + 1))
        expectStorage { AndroidNovelAudioCredentialBlob(file).read() }
    }

    @Test fun `failed write is surfaced instead of silently reporting success`() {
        val parentFile = temp.newFile("not-a-directory")
        expectStorage { AndroidNovelAudioCredentialBlob(File(parentFile, "credentials")).write(byteArrayOf(0)) }
    }

    @Test fun `factory stores credentials only in no backup directory`() {
        val candidates = listOf(File("src/main"), File("app/src/main"))
        val main = candidates.first { it.exists() }
        val code = File(main, "java/io/legado/app/help/readaloud/server/NovelAudioAndroidConfigStore.kt").readText()
        assertTrue(code.contains("context.applicationContext.noBackupFilesDir"))
        assertFalse(code.contains("defaultSharedPreferences"))
        assertFalse(code.contains("getSharedPreferences"))
        assertTrue(code.contains("\"legado.novel_audio_server.v1\""))
    }

    private fun expectStorage(action: () -> Any?) {
        try {
            action()
            fail("storage failure expected")
        } catch (e: NovelAudioServerException) {
            assertEquals("STORAGE", e.kind)
            assertNull(e.cause)
        }
    }
}
