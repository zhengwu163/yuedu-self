package io.legado.app.help.readaloud.novel

import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.utils.NetworkUtils

/**
 * 准备入口的本地优先闸门。
 *
 * 判定必须发生在创建云客户端之前：否则离线也会先建连接再失败，
 * 既可能浪费不可退款的额度预占，也让飞行模式产生真实网络尝试。
 *
 * 读取本地计划失败时按「无本地计划」处理，绝不退化为本地播放——
 * 宁可在线重新准备，也不能把不可信状态当作完整音频交给播放器。
 */
class NovelAudioLocalFirstGate(
    private val plan: (String, Int) -> LocalPlanSnapshot?,
    private val online: () -> Boolean
) {

    /** 判定所需的最小本地状态，避免把整个 Room 实体带进纯逻辑层。 */
    data class LocalPlanSnapshot(
        val state: String,
        val generation: Long,
        val allArtifactsReady: Boolean,
        val snapshotHash: String? = null
    )

    fun decide(
        bookUrl: String,
        chapterIndex: Int,
        expectedGeneration: Long?,
        expectedSnapshotHash: String? = null
    ): NovelAudioLocalFirstPolicy.Decision {
        val snapshot = kotlin.runCatching { plan(bookUrl, chapterIndex) }.getOrNull()
        return NovelAudioLocalFirstPolicy.decide(
            planState = snapshot?.state,
            planGeneration = snapshot?.generation,
            expectedGeneration = expectedGeneration,
            allArtifactsReady = snapshot?.allArtifactsReady ?: false,
            online = online(),
            planSnapshotHash = snapshot?.snapshotHash,
            expectedSnapshotHash = expectedSnapshotHash
        )
    }

    companion object {
        /** 生产实例：只读 Room 计划与 artifact 就绪状态，不做任何写入。 */
        fun create(): NovelAudioLocalFirstGate = NovelAudioLocalFirstGate(
            plan = { bookUrl, chapterIndex ->
                appDb.novelAudioDao.currentChapterPlan(bookUrl, chapterIndex)?.let { entity ->
                    val decoded = NovelAudioPlanJson.decode(entity.planJson)
                    val segmentIds = decoded?.playableSegments?.map { it.segmentId }.orEmpty()
                    LocalPlanSnapshot(
                        state = entity.state,
                        generation = entity.generation,
                        allArtifactsReady = segmentIds.isNotEmpty() &&
                            NovelAudioRepository(appDb)
                                .allArtifactsReady(entity.planId, segmentIds),
                        snapshotHash = decoded?.snapshotHash
                    )
                }
            },
            online = { NetworkUtils.isAvailable() }
        )
    }
}
