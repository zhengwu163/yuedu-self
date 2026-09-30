package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioStates
import kotlinx.coroutines.CancellationException

/**
 * 重启后的 PINNED 任务恢复。
 *
 * 只恢复用户手动固定的任务。AUTO 任务是播放授权的副产物，进程重建后授权已失效，
 * 自动复活会在用户根本没听书时偷偷消耗不可退款的云额度，因此一律不恢复。
 *
 * 队列按章节顺序串行推进；单个任务失败不阻断其余任务，取消原样向外传播。
 */
class NovelAudioPinnedRecovery(
    private val pending: () -> List<PendingTask>,
    private val resume: suspend (String, Int) -> Boolean
) {

    /** 恢复判定所需的最小任务状态。 */
    data class PendingTask(
        val bookUrl: String,
        val chapterIndex: Int,
        val retention: String,
        val state: String
    )

    suspend fun recover() {
        val tasks = kotlin.runCatching { pending() }.getOrElse {
            AppLog.put("AI 听书固定下载恢复读取失败", it)
            return
        }
        tasks
            .filter { it.retention == NovelAudioRetention.PINNED }
            .filter { it.state in RESUMABLE_STATES }
            .filter { it.bookUrl.isNotBlank() && it.chapterIndex >= 0 }
            .sortedBy { it.chapterIndex }
            .forEach { task ->
                try {
                    resume(task.bookUrl, task.chapterIndex)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // 单个章节失败不应让整条固定下载队列停摆。
                    AppLog.put("AI 听书固定下载恢复失败", error)
                }
            }
    }

    companion object {
        /**
         * 只有「非用户意图中断且未失败」的状态才自动恢复。
         *
         * PAUSED 排除：用户显式暂停必须被尊重，自动重启既违背用户意图，
         * 也会在用户以为已停止时继续消耗不可退款的云额度。
         *
         * FAILED 排除：每次启动都重试已失败章节，会在同一章反复扣不可退款额度；
         * 重试必须由用户显式发起。这与 AUTO 预取「失败只前进不重试」同源。
         *
         * 两条共同对应一期验收标准「暂停/取消不自动重启」。
         * CANCELLED、EXPIRED、READY 同样不动。
         */
        private val RESUMABLE_STATES = setOf(
            NovelAudioStates.QUEUED,
            NovelAudioStates.RUNNING,
            NovelAudioStates.PARTIAL,
            NovelAudioStates.WAITING_NETWORK
        )

        /** 生产实例：只读 Room 中仍待完成的 PINNED 任务。 */
        fun create(
            resume: suspend (String, Int) -> Boolean
        ): NovelAudioPinnedRecovery = NovelAudioPinnedRecovery(
            pending = {
                NovelAudioRepository(appDb).recoverablePinnedTasks().map { task ->
                    PendingTask(
                        bookUrl = task.physicalBookUrl,
                        chapterIndex = task.chapterIndex,
                        retention = task.retention,
                        state = task.state
                    )
                }
            },
            resume = resume
        )
    }
}
