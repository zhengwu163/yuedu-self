package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.NovelAudioRetention
import kotlinx.coroutines.CancellationException

/**
 * PINNED 手动下载的执行入口。
 *
 * 与 AUTO 的本质区别是不依赖播放授权：用户暂停或停止听书不会中断手动下载，
 * 只有显式取消才停止。但仍必须串行推进，因为预算账本对同一账本文件
 * 只放行一个生成请求，并发下发只会撞 CONCURRENCY_LIMIT。
 *
 * 单章失败计入统计但不阻断队列；取消原样向外传播，不折叠成失败。
 */
class NovelAudioPinnedDownloader(
    private val prepare: suspend (chapterIndex: Int, retention: String) -> Boolean,
    private val isCancelled: () -> Boolean
) {

    sealed interface Result {
        /** 队列走完；[succeeded] 与 [failed] 之和为实际尝试的章数。 */
        data class Completed(val succeeded: Int, val failed: Int) : Result

        /** 用户显式取消；[succeeded] 为取消前已完成的章数。 */
        data class Cancelled(val succeeded: Int) : Result

        data class Rejected(val reason: NovelAudioPinnedRangePolicy.Rejection) : Result
    }

    suspend fun start(
        selection: NovelAudioPinnedRangePolicy.Selection,
        currentChapterIndex: Int,
        chapterCount: Int
    ): Result {
        val resolved = NovelAudioPinnedRangePolicy.resolve(
            selection = selection,
            currentChapterIndex = currentChapterIndex,
            chapterCount = chapterCount
        )
        val chapters = when (resolved) {
            is NovelAudioPinnedRangePolicy.Result.Rejected ->
                return Result.Rejected(resolved.reason)

            is NovelAudioPinnedRangePolicy.Result.Resolved -> resolved.chapters
        }
        var succeeded = 0
        var failed = 0
        for (chapterIndex in chapters) {
            // 只有显式取消才中断；播放暂停不影响手动下载。
            if (isCancelled()) return Result.Cancelled(succeeded)
            val ok = try {
                prepare(chapterIndex, NovelAudioRetention.PINNED)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                AppLog.put("AI 听书固定下载失败", error)
                false
            }
            if (ok) succeeded++ else failed++
        }
        return Result.Completed(succeeded, failed)
    }

    /** 重启恢复单章时复用同一条准备路径，保持 PINNED retention 不变。 */
    suspend fun resume(bookUrl: String, chapterIndex: Int): Boolean {
        if (bookUrl.isBlank() || chapterIndex < 0) return false
        return try {
            prepare(chapterIndex, NovelAudioRetention.PINNED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.put("AI 听书固定下载恢复失败", error)
            false
        }
    }
}
