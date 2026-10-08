package io.legado.app.help.readaloud.novel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runtime Lease 的结构性接线回归。
 *
 * Lease 必须位于准备环境边界，不能散落成每章一次的 acquire/release；
 * 当前章、AUTO 与 PINNED 都应复用同一批次上下文。
 */
class NovelAudioRuntimeLeaseWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test
    fun `preparation environment owns the batch lease lifecycle`() {
        val environment = source("help/readaloud/novel/NovelAudioPreparationEnvironment.kt")

        assertTrue(environment.contains("suspend fun acquireBatch("))
        assertTrue(environment.contains("acquireRuntime("))
        assertTrue(environment.contains("releaseRuntime("))
        assertTrue(environment.contains("AtomicBoolean"))
    }

    @Test
    fun `current chapter preparation also uses one chapter batch`() {
        val coordinator = source("help/readaloud/novel/NovelAudioPreparationCoordinator.kt")

        assertTrue(coordinator.contains("acquireBatch("))
        assertTrue(coordinator.contains("expectedChapterCount = 1"))
        assertTrue(coordinator.contains("batch.close()"))
    }

    @Test
    fun `analysis and download are bound before a batch starts`() {
        val environment = source("help/readaloud/novel/NovelAudioPreparationEnvironment.kt")

        assertTrue(environment.contains("analysis.withLease("))
        assertTrue(environment.contains("downloadCoordinator.withLease("))
    }
}
