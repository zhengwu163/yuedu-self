package io.legado.app.help.readaloud.novel

/**
 * 跨章连播在下一章尚未就绪时的补救判定。
 *
 * 缺口来自既有链路：章节播完走 `nextChapter()` → `moveToNextChapter(fromReadAloud=true)`
 * → `readAloud(userInitiated=false, prefetchRequest=null)`，此时
 * [NovelAudioPreparationPolicy.shouldPrepare] 必然为 false（既非用户发起，
 * 也没有待投递的预取请求），不会发起准备。播放服务随后只读本地计划，
 * 若 AUTO 预取尚未覆盖该章，界面会停在「准备中」且永远收不到完成事件。
 *
 * 因此仅在「计划缺失或未就绪」且仍持有有效播放授权时补一次准备。
 * 其他阻塞原因（损坏、校验失败）必须走失效与报错路径：
 * 在那些情况下重新准备会掩盖问题，并重复消耗不可退款的额度。
 */
object NovelAudioContinuationPolicy {

    fun shouldPrepareOnBlock(
        missingOrNotReady: Boolean,
        hasCurrentWork: Boolean
    ): Boolean = missingOrNotReady && hasCurrentWork
}
