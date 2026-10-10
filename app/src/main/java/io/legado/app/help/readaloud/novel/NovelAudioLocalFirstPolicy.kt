package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioStates

/**
 * 本地优先与离线零网络的唯一判定点。
 *
 * 只读入本地 Room 状态与网络可用性，不做 IO、不发请求，便于在 JVM 上完整验证。
 * 规则有意保守：只有「同一章节快照的 READY 计划 + 全部 artifact 就绪」才允许直接播放；
 * 其余情况在线才准备远端资源，离线一律等待网络，绝不用不完整清单凑合播放。
 */
object NovelAudioLocalFirstPolicy {

    enum class Decision(val requiresNetwork: Boolean) {
        /** 本地音频完整，直接交给播放器；此路径不允许任何网络访问。 */
        PLAY_LOCAL(false),

        /** 需要分析或合成缺失内容，只有在线且获得授权时才可进入。 */
        PREPARE_REMOTE(true),

        /** 缺内容且当前离线：标记等待网络，不触网也不降级播放。 */
        WAIT_FOR_NETWORK(false)
    }

    fun decide(
        planState: String?,
        planGeneration: Long?,
        expectedGeneration: Long?,
        allArtifactsReady: Boolean,
        online: Boolean,
        planSnapshotHash: String? = null,
        expectedSnapshotHash: String? = null
    ): Decision {
        // Reader generations reset on process restart and differ from PINNED batch generations.
        // The snapshot hash also binds book/chapter identity and preprocessing rules.
        val sameContent = if (expectedSnapshotHash != null) {
            expectedSnapshotHash.isNotBlank() && planSnapshotHash == expectedSnapshotHash
        } else {
            // Compatibility for callers that have not supplied a verified snapshot identity.
            expectedGeneration != null && planGeneration == expectedGeneration
        }
        val reusable = planState == NovelAudioStates.READY && allArtifactsReady && sameContent
        return when {
            reusable -> Decision.PLAY_LOCAL
            online -> Decision.PREPARE_REMOTE
            else -> Decision.WAIT_FOR_NETWORK
        }
    }
}
