package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 跨章补救准备接入服务的结构性接线回归。
 *
 * 阻塞在「计划缺失或未就绪」时必须真的发起一次准备，否则界面停在准备中
 * 且永远收不到完成事件（静默卡死）。补救必须经共享判定，不得在服务里
 * 重写条件，避免损坏与校验失败也被误当成可重新准备。
 */
class NovelAudioContinuationWiringTest {

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
    fun `the blocked branch consults the shared continuation policy`() {
        val body = method(service, "prepareAndPlay")

        assertTrue(
            "补救判定必须来自共享策略",
            body.contains("NovelAudioContinuationPolicy.shouldPrepareOnBlock(")
        )
    }

    @Test
    fun `a blocked continuation actually requests preparation`() {
        val body = method(service, "prepareAndPlay")

        assertTrue(
            "阻塞时必须真的发起准备，否则界面永远停在准备中",
            body.contains("requestContinuationPreparation(")
        )
    }

    @Test
    fun `the request path drives the coordinator for the current chapter`() {
        val body = method(service, "requestContinuationPreparation")

        assertTrue(
            "必须经准备协调器发起当前章请求",
            body.contains("NovelAudioPreparationCoordinator.request(")
        )
        assertTrue(
            "必须尝试用已缓存正文立即开始",
            body.contains("NovelAudioPreparationCoordinator.startCached(")
        )
    }

    @Test
    fun `the request path keeps the pending work so the ready event can resume`() {
        val body = method(service, "prepareAndPlay")

        assertTrue(
            "必须保留待处理 Work 以便准备完成后续播",
            body.contains("pendingPreparationWork = work")
        )
    }
}
