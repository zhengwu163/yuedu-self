package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AUTO 预取接入播放服务的结构性接线回归。
 *
 * 接线必须同时满足两点，否则 AUTO 会造成用户可感知的故障：
 * 一是只在当前章真正就绪后驱动，二是每个撤销授权的入口都同步撤销预取，
 * 否则暂停或换书后后台仍在消耗不可退款的云额度。
 */
class NovelAudioAutoPrefetchWiringTest {

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
    fun `auto prefetch is driven only after the current chapter is ready`() {
        val body = method(service, "prepareAndPlay")

        assertTrue(
            "必须在当前章就绪后驱动 AUTO 预取",
            body.contains("NovelAudioAutoPrefetchDriver.onChapterReady(")
        )
        // 驱动必须排在窗口更新之后：窗口是授权来源，先驱动会拿到空窗口。
        assertTrue(
            "AUTO 驱动必须晚于窗口更新",
            body.indexOf("prefetchAssembled(") < body.indexOf("NovelAudioAutoPrefetchDriver.onChapterReady(")
        )
    }

    @Test
    fun `every revoking entry also revokes auto prefetch`() {
        listOf("playStop", "pauseReadAloud", "onDestroy").forEach { name ->
            assertTrue(
                "$name 必须撤销 AUTO 预取",
                method(service, name).contains("NovelAudioAutoPrefetchDriver.revoke()")
            )
        }
    }

    @Test
    fun `chapter navigation does not leave a stale auto window`() {
        listOf("prevChapter", "nextChapter").forEach { name ->
            val body = method(service, name)
            assertTrue(
                "$name 必须经 playStop 撤销旧窗口",
                body.contains("playStop()")
            )
        }
    }

    @Test
    fun `the driver owns the only lane and prefetch loop instance`() {
        val driver = source("help/readaloud/novel/NovelAudioAutoPrefetchDriver.kt")

        assertTrue("驱动必须持有车道仲裁", driver.contains("NovelAudioPreparationLane()"))
        assertTrue(
            "驱动必须使用后续章准备入口",
            driver.contains("NovelAudioFollowingChapterPreparer(")
        )
        assertTrue(
            "驱动必须使用共享准备环境",
            driver.contains("NovelAudioPreparationEnvironment.open()")
        )
        assertTrue(
            "AUTO 必须在整批开始前申请运行时 Lease",
            driver.contains("acquireBatch(")
        )
        assertTrue(
            "AUTO 必须在取消、异常和完成后释放批次 Lease",
            driver.contains("batch.close()")
        )
        assertTrue(
            "后续章快照必须走只读 loader",
            driver.contains("NovelAudioChapterSnapshotLoader.create()")
        )
    }

    @Test
    fun `the driver never reuses the current chapter request slot`() {
        val driver = source("help/readaloud/novel/NovelAudioAutoPrefetchDriver.kt")

        assertFalse(
            "AUTO 不得占用当前章请求槽，否则用户等待的那一章会被丢弃",
            driver.contains("NovelAudioPreparationCoordinator.request(")
        )
        assertFalse(
            "AUTO 不得改写当前章缓存入口",
            driver.contains("NovelAudioPreparationCoordinator.onFinalContent(")
        )
    }
}
