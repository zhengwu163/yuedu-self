package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 共享准备环境的结构性接线回归。
 *
 * 环境的存在意义是「当前章与后续章共用同一套凭据、预算、分析与下载装配」，
 * 所以这些装配必须只出现在环境里；一旦协调器或后续章入口再自建一份，
 * 两条路径的预算口径与 artifact 校验就会各自漂移。
 */
class NovelAudioPreparationEnvironmentWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    private fun novel(name: String) = source("help/readaloud/novel/$name")

    @Test
    fun `the environment owns the only client and budget ledger construction`() {
        val environment = novel("NovelAudioPreparationEnvironment.kt")

        assertEquals(1, Regex("NovelAudioBudgetLedger\\(").findAll(environment).count())
        assertEquals(1, Regex("newClient\\(budgetLedger\\)").findAll(environment).count())
        assertTrue(
            "预算账本必须独立于凭据文件",
            environment.contains("noBackupFilesDir")
        )
    }

    @Test
    fun `the environment owns the only download coordinator and artifact store`() {
        val environment = novel("NovelAudioPreparationEnvironment.kt")

        assertEquals(
            1,
            Regex("NovelAudioDownloadCoordinator\\(").findAll(environment).count()
        )
        assertEquals(1, Regex("NovelAudioArtifactStore\\(").findAll(environment).count())
    }

    @Test
    fun `the coordinator no longer builds its own generation stack`() {
        val coordinator = novel("NovelAudioPreparationCoordinator.kt")

        assertFalse(
            "客户端与预算装配必须只在共享环境中",
            coordinator.contains("NovelAudioBudgetLedger(")
        )
        assertFalse(
            "下载器装配必须只在共享环境中",
            coordinator.contains("NovelAudioDownloadCoordinator(")
        )
        assertFalse(
            "分析器装配必须只在共享环境中",
            coordinator.contains("NovelAudioAnalysisCoordinator(")
        )
        assertTrue("协调器必须使用共享环境", coordinator.contains("NovelAudioPreparationEnvironment"))
    }

    @Test
    fun `the shared preparer stays the only single chapter sequence`() {
        val environment = novel("NovelAudioPreparationEnvironment.kt")
        val coordinator = novel("NovelAudioPreparationCoordinator.kt")

        assertEquals(1, Regex("NovelAudioChapterPreparer\\(").findAll(environment).count())
        assertFalse(
            "协调器不得再自建单章准备顺序",
            coordinator.contains("NovelAudioChapterPreparer(")
        )
    }
}
