package io.legado.app.help.readaloud.offline

import io.legado.app.help.readaloud.server.SynthesizedAudio
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests disk publication only; an injected decoder is not Android decoding evidence. */
class NovelAudioArtifactRepairTest {
    @Test
    fun verifiedDownloadAtomicallyRepairsCorruptTargetAtTheSamePath() = withCorruptTarget { root, target ->
        var decoded = false
        val store = NovelAudioArtifactStore(root) { part ->
            assertTrue("Validate a staging file before changing the bad target", part.name.endsWith(".part"))
            assertArrayEquals(CORRUPT, target.readBytes())
            decoded = true
            part.readBytes().contentEquals(AUDIO)
        }

        val repaired = store.commitFixture()

        assertTrue(decoded)
        assertEquals(target.canonicalFile, File(root, repaired.path).canonicalFile)
        assertArrayEquals(AUDIO, target.readBytes())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun undecodableReplacementPreservesCorruptTargetForRetry() = withCorruptTarget { root, target ->
        var decoded = false
        val store = NovelAudioArtifactStore(root) {
            decoded = true
            false
        }

        assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
            store.commitFixture()
        }

        assertTrue("Replacement must reach its own decoder", decoded)
        assertArrayEquals(CORRUPT, target.readBytes())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun cancellationAfterValidatingReplacementPreservesOldTarget() = withCorruptTarget { root, target ->
        var current = true
        val store = NovelAudioArtifactStore(root) {
            current = false
            true
        }

        assertThrows(CancellationException::class.java) {
            store.commitFixture { current }
        }

        assertArrayEquals(CORRUPT, target.readBytes())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun failedAtomicReplacementPreservesOldTargetAndCleansStaging() = withCorruptTarget { root, target ->
        var attempted = false
        val store = NovelAudioArtifactStore(
            rootDirectory = root,
            decoder = { true },
            commitFile = { _, destination ->
                assertEquals(target.canonicalFile, destination.canonicalFile)
                assertArrayEquals(CORRUPT, destination.readBytes())
                attempted = true
                throw IOException("fixture publication failure")
            }
        )

        assertThrows(NovelAudioArtifactStore.ArtifactStoreException::class.java) {
            store.commitFixture()
        }

        assertTrue("A verified replacement must attempt publication", attempted)
        assertArrayEquals(CORRUPT, target.readBytes())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }

    @Test
    fun cancellationAfterPublicationKeepsVerifiedRepairButDoesNotReturnSuccess() =
        withCorruptTarget { root, target ->
            var current = true
            val store = NovelAudioArtifactStore(
                rootDirectory = root,
                decoder = { it.readBytes().contentEquals(AUDIO) },
                commitFile = { part, destination ->
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    )
                    current = false
                }
            )

            assertThrows(CancellationException::class.java) {
                store.commitFixture { current }
            }

            // A published content-addressed file may already belong to another plan.
            assertTrue("Cancellation must not roll back a verified shared target", target.isFile)
            assertArrayEquals(AUDIO, target.readBytes())
            assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
        }

    @Test
    fun cancelledPublisherCannotDeleteOrForceRewriteOfAConcurrentReuse() =
        withCorruptTarget { root, target ->
            val published = CountDownLatch(1)
            val rewrites = AtomicInteger()
            val executor = Executors.newFixedThreadPool(2)
            var current = true
            val publisher = NovelAudioArtifactStore(
                rootDirectory = root,
                decoder = { it.readBytes().contentEquals(AUDIO) },
                commitFile = { part, destination ->
                    Files.move(
                        part.toPath(), destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                    )
                    current = false
                    published.countDown()
                }
            )
            val consumer = NovelAudioArtifactStore(
                rootDirectory = root,
                decoder = { it.readBytes().contentEquals(AUDIO) },
                writePart = { part, bytes ->
                    rewrites.incrementAndGet()
                    part.writeBytes(bytes)
                }
            )
            try {
                val first = executor.submit {
                    assertThrows(CancellationException::class.java) {
                        publisher.commitFixture { current }
                    }
                }
                val second = executor.submit<NovelAudioArtifactStore.StoredArtifact> {
                    assertTrue("Publisher must reach the atomic move", published.await(5, TimeUnit.SECONDS))
                    consumer.commitFixture()
                }
                first.get(5, TimeUnit.SECONDS)
                val reused = second.get(5, TimeUnit.SECONDS)

                assertEquals(target.canonicalFile, File(root, reused.path).canonicalFile)
                assertEquals("Reusing verified bytes must not write another staging file", 0, rewrites.get())
                assertTrue("The reused target must survive the old publisher", target.isFile)
                assertArrayEquals(AUDIO, target.readBytes())
                assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }

    private fun withCorruptTarget(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("novel-audio-repair").toFile()
        try {
            val artifact = NovelAudioArtifactStore(root) { true }.commitFixture()
            val target = File(root, artifact.path)
            target.writeBytes(CORRUPT)
            test(root, target)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun NovelAudioArtifactStore.commitFixture(
        isCurrent: () -> Boolean = { true }
    ): NovelAudioArtifactStore.StoredArtifact = commit(
        serverScope = "repair-fixture",
        workKey = "work-fixture",
        physicalBookUrl = "book://repair-fixture",
        chapterIndex = 2,
        chapterUrl = "chapter://repair-fixture/2",
        segmentId = "segment-fixture",
        voiceAssetId = "voice-fixture",
        bindingRevision = 1L,
        language = "zh-CN",
        speed = 1.0,
        audio = SynthesizedAudio(AUDIO, "audio/ogg", "profile-fixture"),
        isCurrent = isCurrent
    )

    private companion object {
        val AUDIO = byteArrayOf(1, 2, 3)
        val CORRUPT = byteArrayOf(9, 9, 9)
    }
}
