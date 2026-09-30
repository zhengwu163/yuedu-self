package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播放位置持久化接入服务的结构性接线回归。
 *
 * 后台播放时 Activity 不在前台，原有「事件 → Activity 写 durChapterPos」链断开，
 * 因此服务必须自己落盘。停止类入口必须冲刷，否则最后一段进度丢失，
 * 用户续播会退回上一次节流写入的位置。
 */
class NovelAudioProgressPersistWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    private fun method(path: String, name: String): String {
        val text = source(path)
        val start = text.indexOf("fun $name(")
        assertTrue("找不到 $path::$name", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 1
        var end = open + 1
        while (depth > 0 && end < text.length) {
            when (text[end++]) {
                '{' -> depth++
                '}' -> depth--
            }
        }
        return text.substring(start, end)
    }

    private val service = "service/NovelAudioReadAloudService.kt"

    @Test
    fun `segment progress persists the chapter position`() {
        val body = method(service, "publishSegmentProgress")

        assertTrue(
            "分段进度必须持久化章内位置",
            body.contains("progressPersister.onProgress(")
        )
    }

    @Test
    fun `every stopping entry flushes the pending position`() {
        listOf("playStop", "pauseReadAloud", "onDestroy").forEach { name ->
            assertTrue(
                "$name 必须冲刷待写位置",
                method(service, name).contains("progressPersister.flush()")
            )
        }
    }

    @Test
    fun `the persister is a single service field rather than per call`() {
        val text = source(service)

        assertTrue(
            "持久化器必须是服务字段",
            Regex("private val progressPersister").containsMatchIn(text)
        )
    }

    @Test
    fun `the write path updates the reader position and saves it`() {
        val text = source(service)

        assertTrue(
            "写入必须更新阅读位置",
            text.contains("ReadBook.durChapterPos =")
        )
        assertTrue(
            "写入必须落盘阅读进度",
            text.contains("ReadBook.saveRead(")
        )
    }
}
