package io.legado.app.help.readaloud.offline

import io.legado.app.help.readaloud.server.SynthesizedAudio
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assume.assumeNoException

class NovelAudioArtifactStoreTest {

    @Test
    fun `verified audio is atomically committed and no part file remains`() {
        val root = Files.createTempDirectory("novel-audio-artifact").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { it.readBytes().contentEquals(BYTES_A) }
            val result = store.commitWith(audio(BYTES_A))
            assertTrue(root.resolve(result.path).isFile)
            assertTrue(result.path.endsWith(".ogg"))
            assertTrue(result.path.contains(result.sha256))
            assertFalse(result.path.contains(SCOPE))
            assertFalse(result.path.contains(PROFILE))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid input never commits`() {
        val root = Files.createTempDirectory("novel-audio-artifact-invalid").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { true }
            assertThrows(IllegalArgumentException::class.java) {
                store.commitWith(audio(byteArrayOf()))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.commitWith(audio(BYTES_A, contentType = "audio/wav"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.commitWith(audio(BYTES_A, ttsProfile = " "))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.commitWith(segmentId = "", audio = audio(BYTES_A))
            }
            assertFalse(root.walkTopDown().any { it.isFile })
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `scope voice revision speed and profile are isolated`() {
        val root = Files.createTempDirectory("novel-audio-artifact-identity").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { true }
            val paths = listOf(
                store.commitWith(audio(BYTES_A)).path,
                store.commitWith(serverScope = "other-scope", audio = audio(BYTES_A)).path,
                store.commitWith(voiceAssetId = "other-voice", audio = audio(BYTES_A)).path,
                store.commitWith(bindingRevision = 2L, audio = audio(BYTES_A)).path,
                store.commitWith(speed = 1.25, audio = audio(BYTES_A)).path,
                store.commitWith(audio = audio(BYTES_A, ttsProfile = "other-profile")).path
            )
            assertTrue(paths.toSet().size == paths.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `different audio content gets immutable content addressed targets`() {
        val root = Files.createTempDirectory("novel-audio-artifact-immutable").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { true }
            val first = store.commitWith(audio(BYTES_A))
            val second = store.commitWith(audio(BYTES_B))
            assertNotEquals(first.path, second.path)
            assertTrue(root.resolve(first.path).readBytes().contentEquals(BYTES_A))
            assertTrue(root.resolve(second.path).readBytes().contentEquals(BYTES_B))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".bak") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `corrupt existing target is repaired by a verified download`() {
        val root = Files.createTempDirectory("novel-audio-artifact-corrupt").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { true }
            val committed = store.commitWith(audio(BYTES_A))
            val target = root.resolve(committed.path)
            target.writeBytes(byteArrayOf(9, 9, 9))
            val repaired = store.commitWith(audio(BYTES_A))
            assertTrue(repaired.path == committed.path)
            assertTrue(target.readBytes().contentEquals(BYTES_A))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed write and publication leave no part or target`() {
        val root = Files.createTempDirectory("novel-audio-artifact-failure").toFile()
        try {
            val writeFailingStore = NovelAudioArtifactStore(
                rootDirectory = root,
                decoder = { true },
                writePart = { _, _ -> throw IOException("write failure") }
            )
            assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
                writeFailingStore.commitWith(audio(BYTES_A))
            }
            assertFalse(root.walkTopDown().any { it.isFile })

            val publishFailingStore = NovelAudioArtifactStore(
                rootDirectory = root,
                decoder = { true },
                commitFile = { _, _ -> throw IOException("rename failure") }
            )
            assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
                publishFailingStore.commitWith(audio(BYTES_B))
            }
            assertFalse(root.walkTopDown().any { it.isFile })
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `decoder failure leaves no target or part`() {
        val root = Files.createTempDirectory("novel-audio-artifact-decode").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { false }
            assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
                store.commitWith(audio(BYTES_A))
            }
            assertFalse(root.walkTopDown().any { it.isFile })
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancellation after decode leaves no new target and preserves previous artifact`() {
        val root = Files.createTempDirectory("novel-audio-artifact-cancel").toFile()
        try {
            val store = NovelAudioArtifactStore(root) { true }
            val previous = store.commitWith(audio(BYTES_A))
            var decoderReturned = false
            val cancellingStore = NovelAudioArtifactStore(root) {
                decoderReturned = true
                true
            }
            assertThrows(CancellationException::class.java) {
                cancellingStore.commitWith(
                    audio = audio(BYTES_B),
                    isCurrent = { !decoderReturned }
                )
            }
            assertTrue(root.resolve(previous.path).readBytes().contentEquals(BYTES_A))
            assertTrue(root.walkTopDown().filter { it.isFile }.toList().size == 1)
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `path escaping through a symlink is rejected`() {
        val root = Files.createTempDirectory("novel-audio-artifact-root").toFile()
        val outside = Files.createTempDirectory("novel-audio-artifact-outside").toFile()
        try {
            val scope = root.resolve(NovelAudioPathCodec.scopeDirectory(SCOPE))
            try {
                Files.createSymbolicLink(scope.toPath(), outside.toPath())
            } catch (error: UnsupportedOperationException) {
                assumeNoException("Host does not support symlinks", error)
            } catch (error: SecurityException) {
                assumeNoException("Host denies symlink creation", error)
            } catch (error: java.nio.file.FileSystemException) {
                val reason = error.reason.orEmpty()
                if (!System.getProperty("os.name").startsWith("Windows") ||
                    !(reason.contains("privilege", ignoreCase = true) || reason.contains("特权"))
                ) throw error
                assumeNoException("Windows symlink privilege unavailable", error)
            }
            val store = NovelAudioArtifactStore(root) { true }
            assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
                store.commitWith(audio(BYTES_A))
            }
            assertFalse(outside.walkTopDown().any { it.isFile })
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun audio(
        bytes: ByteArray,
        contentType: String = "audio/ogg",
        ttsProfile: String = PROFILE
    ) = SynthesizedAudio(bytes = bytes, contentType = contentType, ttsProfile = ttsProfile)

    private fun NovelAudioArtifactStore.commitWith(
        audio: SynthesizedAudio,
        serverScope: String = SCOPE,
        workKey: String = WORK,
        physicalBookUrl: String = BOOK,
        chapterIndex: Int = 0,
        chapterUrl: String = CHAPTER,
        segmentId: String = SEGMENT,
        voiceAssetId: String = VOICE,
        bindingRevision: Long = 1L,
        language: String = LANGUAGE,
        speed: Double = 1.0,
        isCurrent: () -> Boolean = { true }
    ): NovelAudioArtifactStore.StoredArtifact = commit(
        serverScope = serverScope,
        workKey = workKey,
        physicalBookUrl = physicalBookUrl,
        chapterIndex = chapterIndex,
        chapterUrl = chapterUrl,
        segmentId = segmentId,
        voiceAssetId = voiceAssetId,
        bindingRevision = bindingRevision,
        language = language,
        speed = speed,
        audio = audio,
        isCurrent = isCurrent
    )

    private companion object {
        val BYTES_A = byteArrayOf(1, 2, 3)
        val BYTES_B = byteArrayOf(4, 5, 6)
        const val SCOPE = "https://audio.example/v1"
        const val WORK = "work:author/title"
        const val BOOK = "book://physical"
        const val CHAPTER = "chapter://0"
        const val SEGMENT = "segment:1"
        const val VOICE = "voice:1"
        const val LANGUAGE = "zh-CN"
        const val PROFILE = "profile:test"
    }
}
