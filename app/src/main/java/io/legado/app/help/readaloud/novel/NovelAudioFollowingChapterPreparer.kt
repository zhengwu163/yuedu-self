package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioRetention
import kotlinx.coroutines.CancellationException

/**
 * 后续章（AUTO）准备的独立入口。
 *
 * 必须与当前章完全分离：当前章由 [NovelAudioPreparationCoordinator] 的请求槽驱动，
 * 后续章只走 [NovelAudioPreparationLane] 的 AUTO 车道。若后续章占用当前章的请求槽，
 * 用户翻到新章时最终正文回调会因请求不匹配被丢弃，用户正在等的那一章将永不被准备。
 *
 * 代次不来自阅读页：后续章没有阅读页装载代次，因此用快照散列派生一个稳定值，
 * 保证同一正文重复准备得到同一代次，正文变化则自然换代。
 */
class NovelAudioFollowingChapterPreparer(
    private val lane: NovelAudioPreparationLane,
    private val snapshot: suspend (Book, Int) -> NovelAudioChapterSnapshot?,
    private val prepare: suspend (
        snapshot: NovelAudioChapterSnapshot,
        generation: Long,
        retention: String
    ) -> NovelAudioChapterPreparer.Result?
) {

    suspend fun prepare(book: Book, chapterIndex: Int, chapterCount: Int): Boolean {
        if (chapterIndex < 0 || chapterIndex >= chapterCount) return false
        if (!lane.beginAuto(book.bookUrl, chapterIndex)) return false
        return try {
            val target = snapshot(book, chapterIndex) ?: return false
            val result = prepare(
                target,
                generationOf(target),
                NovelAudioRetention.AUTO
            )
            result is NovelAudioChapterPreparer.Result.Ready
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AppLog.put("AI 听书后续章准备失败", error)
            false
        } finally {
            // 成功、跳过、失败与取消都必须释放车道，否则 AUTO 会永久卡住。
            lane.finishAuto(book.bookUrl, chapterIndex)
        }
    }

    // 取散列的低 62 位，保证非负且同一正文稳定；正文或规则变化即自然换代。
    private fun generationOf(target: NovelAudioChapterSnapshot): Long =
        target.snapshotHash.hashCode().toLong() and 0x3FFF_FFFFL
}
