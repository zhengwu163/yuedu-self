package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 准备入口的本地优先闸门。
 *
 * 这是「已下载的章节不重复生成」与「缺段离线时零网络」两条验收标准的唯一落点：
 * 闸门必须在创建云客户端之前做出判定，否则离线也会先建连接再失败，
 * 既浪费不可退款的额度预占，也让飞行模式产生真实网络尝试。
 */
class NovelAudioLocalFirstGateTest {

    private val book = "https://example.test/book"

    @Test
    fun `a complete local plan skips preparation entirely`() {
        val gate = gate(
            plan = LocalPlan(NovelAudioStates.READY, generation = 7L, allReady = true),
            online = true
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `an incomplete plan while offline waits without touching the network`() {
        var onlineChecks = 0
        val gate = NovelAudioLocalFirstGate(
            plan = { _, _ -> LocalPlan(NovelAudioStates.PARTIAL, 7L, allReady = false).toSnapshot() },
            online = { onlineChecks++; false }
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
        assertEquals("联网状态必须被真实查询", 1, onlineChecks)
    }

    @Test
    fun `an incomplete plan while online prepares remotely`() {
        val gate = gate(
            plan = LocalPlan(NovelAudioStates.PARTIAL, generation = 7L, allReady = false),
            online = true
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `a ready plan from another generation is not reused`() {
        val gate = gate(
            plan = LocalPlan(NovelAudioStates.READY, generation = 6L, allReady = true),
            online = true
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `a ready plan with a missing artifact is never played locally`() {
        val gate = gate(
            plan = LocalPlan(NovelAudioStates.READY, generation = 7L, allReady = false),
            online = false
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `an absent plan offline waits and online prepares`() {
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            NovelAudioLocalFirstGate({ _, _ -> null }, { false })
                .decide(book, 3, expectedGeneration = 7L)
        )
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            NovelAudioLocalFirstGate({ _, _ -> null }, { true })
                .decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `a local plan lookup failure never becomes a local play`() {
        val gate = NovelAudioLocalFirstGate(
            plan = { _, _ -> throw IllegalStateException("room unavailable") },
            online = { true }
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `a local plan lookup failure while offline still refuses the network`() {
        val gate = NovelAudioLocalFirstGate(
            plan = { _, _ -> throw IllegalStateException("room unavailable") },
            online = { false }
        )

        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            gate.decide(book, 3, expectedGeneration = 7L)
        )
    }

    @Test
    fun `only the requested book and chapter are consulted`() {
        val seen = mutableListOf<Pair<String, Int>>()
        val gate = NovelAudioLocalFirstGate(
            plan = { url, index ->
                seen += url to index
                LocalPlan(NovelAudioStates.READY, 7L, allReady = true).toSnapshot()
            },
            online = { true }
        )

        gate.decide(book, 3, expectedGeneration = 7L)

        assertEquals(listOf(book to 3), seen)
        assertTrue(seen.single().first == book)
    }

    private data class LocalPlan(
        val state: String,
        val generation: Long,
        val allReady: Boolean
    ) {
        fun toSnapshot() = NovelAudioLocalFirstGate.LocalPlanSnapshot(
            state = state,
            generation = generation,
            allArtifactsReady = allReady
        )
    }

    private fun gate(plan: LocalPlan, online: Boolean) = NovelAudioLocalFirstGate(
        plan = { _, _ -> plan.toSnapshot() },
        online = { online }
    )
}
