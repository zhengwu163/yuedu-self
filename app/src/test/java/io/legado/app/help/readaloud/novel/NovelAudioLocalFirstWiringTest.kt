package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本地优先闸门接入准备入口的结构性接线回归。
 *
 * 闸门必须排在共享环境装配之前：装配会创建云客户端与预算账本，
 * 一旦顺序颠倒，离线播放已下载章节也会先建连接，飞行模式便产生真实网络尝试。
 */
class NovelAudioLocalFirstWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    private val coordinator = "help/readaloud/novel/NovelAudioPreparationCoordinator.kt"

    @Test
    fun `the gate is consulted before any generation stack is built`() {
        val text = source(coordinator)

        val gateAt = text.indexOf("localFirstGate.decide(")
        val environmentAt = text.indexOf("NovelAudioPreparationEnvironment.open()")
        assertTrue("准备入口必须先查本地优先闸门", gateAt >= 0)
        assertTrue("必须装配共享环境", environmentAt >= 0)
        assertTrue(
            "闸门必须排在环境装配之前，否则离线会先建云客户端",
            gateAt < environmentAt
        )
    }

    @Test
    fun `a complete local chapter publishes ready without preparing remotely`() {
        val text = source(coordinator)

        assertTrue(
            "本地完整时必须直接发布就绪",
            text.contains("NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL")
        )
    }

    @Test
    fun `an offline incomplete chapter reports waiting for network`() {
        val text = source(coordinator)

        assertTrue(
            "离线缺段必须报告等待网络",
            text.contains("NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK")
        )
        assertTrue(
            "等待网络必须有固定原因码",
            text.contains("WAITING_NETWORK")
        )
    }

    @Test
    fun `the gate instance is created once and not per preparation`() {
        val text = source(coordinator)

        assertTrue(
            "闸门应作为协调器字段复用",
            Regex("private val localFirstGate").containsMatchIn(text)
        )
        assertFalse(
            "不得在准备作业内重复创建闸门",
            text.contains("NovelAudioLocalFirstGate.create()\n            ")
        )
    }
}
