package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 准备协调器的结构性接线回归；与 [NovelAudioChapterPreparerTest] 的行为测试配对。
 *
 * 单章准备顺序必须只有一份实现：协调器委派给 [NovelAudioChapterPreparer]，
 * 不再内联展开下载结果分支，否则当前章与 AUTO 后续章会各自演化出不同顺序。
 */
class NovelAudioPreparationWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test
    fun `coordinator delegates single chapter preparation to the shared preparer`() {
        val text = source("help/readaloud/novel/NovelAudioPreparationCoordinator.kt")

        assertTrue("协调器必须使用共享准备顺序", text.contains("NovelAudioChapterPreparer("))
        assertTrue("必须调用共享准备入口", text.contains(".prepare("))
    }

    @Test
    fun `coordinator no longer inlines the download result branches`() {
        val text = source("help/readaloud/novel/NovelAudioPreparationCoordinator.kt")

        assertFalse(
            "下载结果分支只能存在于共享准备顺序中",
            text.contains("NovelAudioDownloadCoordinator.Result.Ready")
        )
        assertFalse(
            "下载结果分支只能存在于共享准备顺序中",
            text.contains("NovelAudioDownloadCoordinator.Result.Cancelled")
        )
    }

    @Test
    fun `the shared preparer owns exactly one download result mapping`() {
        val preparer = source("help/readaloud/novel/NovelAudioChapterPreparer.kt")

        assertEquals(
            1,
            Regex("NovelAudioDownloadCoordinator\\.Result\\.Ready").findAll(preparer).count()
        )
        assertEquals(
            1,
            Regex("NovelAudioDownloadCoordinator\\.Result\\.Cancelled").findAll(preparer).count()
        )
    }

    @Test
    fun `the budget ledger stays independent from server credentials`() {
        val text = source("help/readaloud/novel/NovelAudioPreparationCoordinator.kt")

        assertTrue(
            "预算账本必须放在 noBackupFilesDir，避免随凭据清除被重置",
            text.contains("noBackupFilesDir")
        )
        assertTrue(
            "生成客户端必须带预算账本",
            text.contains("newClient(budgetLedger)")
        )
    }
}
