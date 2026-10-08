package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 固定下载恢复接入启动流程的结构性接线回归。
 *
 * 恢复只允许发生在后台 IO 协程里：放在主线程会读 Room 并可能发起云请求，
 * 直接造成启动卡顿甚至 ANR。恢复入口也必须是唯一的，避免多处重复触发
 * 同一批任务而撞并发上限。
 */
class NovelAudioPinnedRecoveryWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test
    fun `recovery is triggered once from the application startup`() {
        val app = source("App.kt")

        assertTrue(
            "启动流程必须触发固定下载恢复",
            app.contains("NovelAudioPinnedRecoveryStarter.start()")
        )
        assertEquals(
            1,
            Regex("NovelAudioPinnedRecoveryStarter\\.start\\(\\)").findAll(app).count()
        )
    }

    @Test
    fun `recovery runs off the main thread`() {
        val app = source("App.kt")
        val asyncAt = app.indexOf("Coroutine.async(executeContext = IO)")
        val startAt = app.indexOf("NovelAudioPinnedRecoveryStarter.start()")

        assertTrue("必须存在后台初始化块", asyncAt >= 0)
        assertTrue("恢复必须在后台初始化块之后", startAt > asyncAt)
    }

    @Test
    fun `the starter reuses the shared recovery and pinned preparer`() {
        val starter = source("help/readaloud/novel/NovelAudioPinnedRecoveryStarter.kt")

        assertTrue(
            "必须复用共享恢复逻辑",
            starter.contains("NovelAudioPinnedRecovery.create")
        )
        assertTrue(
            "必须复用固定下载的准备入口",
            starter.contains("NovelAudioPinnedChapterPreparer.prepare(")
        )
        assertTrue(
            "启动恢复必须复用固定下载批次入口",
            starter.contains("NovelAudioPinnedChapterPreparer.openBatch(")
        )
        assertFalse(
            "启动恢复不得自建下载执行器",
            starter.contains("NovelAudioPinnedDownloader(")
        )
    }

    @Test
    fun `the starter is idempotent so a second call cannot double the queue`() {
        val starter = source("help/readaloud/novel/NovelAudioPinnedRecoveryStarter.kt")

        assertTrue(
            "必须有一次性保护，避免重复恢复同一批任务",
            starter.contains("AtomicBoolean")
        )
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
