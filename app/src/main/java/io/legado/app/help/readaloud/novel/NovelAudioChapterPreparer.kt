package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator

/**
 * 单章准备的固定顺序：产出计划 → 取得执行令牌 → 下载缺段。
 *
 * 当前章与 AUTO 后续章共用这一段，因此它刻意不含任何 Android 依赖：
 * 计划产出、执行令牌获取和下载都由调用方注入，可在纯 JVM 下完整验证。
 *
 * 取消语义按项目铁律原样向外传播，不折叠成 [Result.Failed]；
 * 缺少计划或执行令牌时直接返回 null，绝不进入下载阶段。
 */
class NovelAudioChapterPreparer(
    private val produce: suspend (
        snapshot: NovelAudioChapterSnapshot,
        generation: Long,
        retention: String,
        isCurrent: () -> Boolean
    ) -> NovelAudioChapterPlan?,
    private val execution: () -> NovelAudioRepository.Execution?,
    private val download: suspend (
        execution: NovelAudioRepository.Execution,
        isAutoAllowed: () -> Boolean
    ) -> NovelAudioDownloadCoordinator.Result
) {

    sealed interface Result {
        data class Ready(val plan: NovelAudioChapterPlan) : Result
        data class Failed(val reason: String, val generation: Long) : Result
    }

    suspend fun prepare(
        snapshot: NovelAudioChapterSnapshot,
        generation: Long,
        retention: String,
        isCurrent: () -> Boolean
    ): Result? {
        val plan = produce(snapshot, generation, retention, isCurrent) ?: return null
        // 执行令牌由 produce 的持久化副作用产生；拿不到说明该次执行已被替换或撤销。
        val token = execution() ?: return null
        return when (val result = download(token, isCurrent)) {
            NovelAudioDownloadCoordinator.Result.Ready -> Result.Ready(plan)
            NovelAudioDownloadCoordinator.Result.Cancelled -> null
            is NovelAudioDownloadCoordinator.Result.Failed ->
                Result.Failed(result.reason, generation)
        }
    }
}
