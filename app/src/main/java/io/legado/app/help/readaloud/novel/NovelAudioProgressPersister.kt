package io.legado.app.help.readaloud.novel

/**
 * AI 听书播放位置的持久化节流。
 *
 * 杀进程后续播依赖章内位置已落盘，但每个分段推进都写 Room 会造成频繁磁盘写入，
 * 因此用时间窗节流。以下三种情况必须立即写入，不能被窗口吞掉：
 * 换章、换书、停止——它们都意味着当前位置不会再有后续事件来补写。
 *
 * 同章位置回退不写：这类事件通常来自迟到的段进度回调，
 * 写回更早的位置会让用户下次续播退回已听过的内容。
 */
class NovelAudioProgressPersister(
    private val write: (bookUrl: String, chapterIndex: Int, chapterPosition: Int) -> Unit
) {

    private data class Position(
        val bookUrl: String,
        val chapterIndex: Int,
        val chapterPosition: Int
    )

    private var lastWritten: Position? = null
    private var pending: Position? = null
    private var lastWriteAtMillis = 0L

    @Synchronized
    fun onProgress(
        bookUrl: String,
        chapterIndex: Int,
        chapterPosition: Int,
        atMillis: Long
    ) {
        if (bookUrl.isBlank() || chapterIndex < 0 || chapterPosition < 0) return
        val next = Position(bookUrl, chapterIndex, chapterPosition)
        val previous = lastWritten
        val changedChapter = previous == null ||
            previous.bookUrl != bookUrl ||
            previous.chapterIndex != chapterIndex
        if (changedChapter) {
            commit(next, atMillis)
            return
        }
        // 同章回退多来自迟到回调，既不落盘也不作为待写位置。
        if (chapterPosition <= previous.chapterPosition) return
        pending = next
        if (atMillis - lastWriteAtMillis >= THROTTLE_MILLIS) {
            commit(next, atMillis)
        }
    }

    /** 停止、暂停与销毁必须冲刷待写位置，否则最后一段进度会丢失。 */
    @Synchronized
    fun flush() {
        val target = pending ?: return
        commit(target, lastWriteAtMillis)
    }

    private fun commit(position: Position, atMillis: Long) {
        write(position.bookUrl, position.chapterIndex, position.chapterPosition)
        lastWritten = position
        pending = null
        lastWriteAtMillis = atMillis
    }

    private companion object {
        /** 十秒窗：足够稀释磁盘写入，又不会在崩溃时丢失太多已听内容。 */
        const val THROTTLE_MILLIS = 10_000L
    }
}
