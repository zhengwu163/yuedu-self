package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 固定下载入口的结构性接线回归。
 *
 * 入口刻意复用既有 SettingActionSpec 与既有主题化选择对话框：
 * 不新增绘制组件、不引入新的取色决策，因此主题四态没有新增风险面。
 * 同时必须只在 AI 多角色听书路线下出现，否则普通朗读用户会看到无效入口。
 */
class NovelAudioPinnedDownloadWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    private val dialog = "ui/book/read/config/ReadAloudConfigDialog.kt"

    @Test
    fun `the entry is offered in the engine group`() {
        val text = source(dialog)

        assertTrue("必须登记固定下载入口的 key", text.contains("KEY_NOVEL_AUDIO_PINNED_DOWNLOAD"))
        assertTrue(
            "入口必须使用既有 action 项",
            Regex("key = KEY_NOVEL_AUDIO_PINNED_DOWNLOAD").containsMatchIn(text)
        )
    }

    @Test
    fun `the entry only shows on the novel audio route`() {
        val text = source(dialog)

        assertTrue(
            "入口必须限定 AI 多角色听书路线",
            text.contains("SpeechRoute.ENGINE_NOVEL_AUDIO")
        )
        assertTrue(
            "入口必须支持按路线隐藏",
            Regex("visible = isNovelAudioRoute").containsMatchIn(text)
        )
    }

    @Test
    fun `the entry reuses the themed choice dialog instead of a new component`() {
        val text = source(dialog)

        assertTrue(
            "必须复用既有主题化选择对话框",
            text.contains("showComposeChoiceListDialog(")
        )
        assertFalse(
            "不得为固定下载新增独立对话框类",
            text.contains("NovelAudioPinnedDownloadDialog")
        )
    }

    @Test
    fun `option and state text come from the shared labels`() {
        val text = source(dialog)

        assertTrue(
            "选项文案必须来自共享文案层",
            text.contains("NovelAudioPinnedDownloadLabels.option(")
        )
        assertTrue(
            "状态文案必须来自共享文案层",
            text.contains("NovelAudioPinnedDownloadLabels.state(")
        )
    }

    @Test
    fun `the entry drives the shared presenter rather than the downloader directly`() {
        val text = source(dialog)

        assertTrue(
            "必须经状态持有者驱动",
            text.contains("NovelAudioPinnedDownloadPresenter(")
        )
        assertFalse(
            "界面不得直接驱动下载执行器，否则进度与取消规则会出现第二份实现",
            text.contains("NovelAudioPinnedDownloader(")
        )
    }
}
