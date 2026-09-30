package io.legado.app.help.readaloud.offline

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException

/**
 * AUTO 预取的执行循环：把授权窗口内的章节逐个准备完成。
 *
 * 串行推进由 [NovelAudioAutoPrefetchScheduler] 决定顺序，本类负责真正执行。
 * 每个目标发起前复核授权，因此窗口滚出或用户暂停后不会再消耗额度；
 * 本地已完整的章节直接跳过，不重新分析或合成。
 *
 * 失败只前进不重试：预占过的额度不可退款，重试失败章只会重复扣费。
 * 取消原样向外传播，其他异常记录后终止本轮窗口，不向调用方逃逸。
 */
class NovelAudioAutoPrefetchCoordinator(
    private val isAllowed: (Int) -> Boolean,
    private val isLocallyReady: suspend (String, Int) -> Boolean,
    private val prepare: suspend (String, Int) -> Boolean
) {

    private val scheduler = NovelAudioAutoPrefetchScheduler()

    suspend fun run(bookUrl: String, chapters: IntRange) {
        var target = scheduler.onWindow(bookUrl, chapters) ?: return
        while (true) {
            if (!isAllowed(target.chapterIndex)) {
                scheduler.revoke()
                return
            }
            val succeeded = try {
                if (isLocallyReady(target.bookUrl, target.chapterIndex)) {
                    true
                } else {
                    prepare(target.bookUrl, target.chapterIndex)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.put("AI 听书自动预取失败", error)
                scheduler.revoke()
                return
            }
            target = if (succeeded) {
                scheduler.onCompleted(target.bookUrl, target.chapterIndex)
            } else {
                scheduler.onFailed(target.bookUrl, target.chapterIndex)
            } ?: return
        }
    }

    /** 暂停、停止、换书等撤销授权的入口必须同步撤销窗口。 */
    fun revoke() = scheduler.revoke()
}
