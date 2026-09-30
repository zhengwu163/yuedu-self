package io.legado.app.help.readaloud.offline

/**
 * AUTO 预取的串行推进状态机：把 [AudioPrefetchLifecycle.assembled] 返回的
 * 「当前章之后最多三章」窗口，转换成一次只有一个在途目标的下发序列。
 *
 * 只做纯内存决策，不持有授权、不做 IO、不发起请求。授权仍由
 * [AudioPrefetchSession] 负责，调用方在真正下发前必须再次复核
 * [AudioPrefetchLifecycle.isAllowed]；本类的返回值不是许可。
 *
 * 串行是硬约束而非优化：预算账本对同一账本文件只放行一个生成请求，
 * 准备协调器也只有单个请求槽，并行下发只会互相取消或撞 CONCURRENCY_LIMIT。
 */
class NovelAudioAutoPrefetchScheduler {

    /** 下一个应当下发的预取目标；仅作为调度建议，不代表已获授权。 */
    data class Target(val bookUrl: String, val chapterIndex: Int)

    private var bookUrl: String? = null
    private var window = IntRange.EMPTY
    private var current: Int? = null

    /**
     * 接收最新窗口。相同书籍与相同区间视为重复通知，保持已推进的位置不变，
     * 避免装配回调重复触发时把已完成的章节重新下发一遍。
     */
    @Synchronized
    fun onWindow(bookUrl: String, chapters: IntRange): Target? {
        if (bookUrl.isBlank()) {
            revokeLocked()
            return null
        }
        if (this.bookUrl == bookUrl && window == chapters) return null
        this.bookUrl = bookUrl
        window = chapters
        if (chapters.isEmpty()) {
            current = null
            return null
        }
        current = chapters.first
        return Target(bookUrl, chapters.first)
    }

    /** 在途目标成功结束，推进到窗口内下一章。 */
    @Synchronized
    fun onCompleted(bookUrl: String, chapterIndex: Int): Target? =
        advanceLocked(bookUrl, chapterIndex)

    /** 在途目标失败。窗口只前进不重试，避免失败章节反复消耗额度。 */
    @Synchronized
    fun onFailed(bookUrl: String, chapterIndex: Int): Target? =
        advanceLocked(bookUrl, chapterIndex)

    /** 暂停、停止、换书等撤销授权的入口必须同步撤销窗口。 */
    @Synchronized
    fun revoke() = revokeLocked()

    // 仅接受当前书籍当前在途目标的结果；旧窗口、旧章节和其他书籍的回调都被忽略。
    private fun advanceLocked(bookUrl: String, chapterIndex: Int): Target? {
        val active = this.bookUrl ?: return null
        val inFlight = current ?: return null
        if (bookUrl != active || chapterIndex != inFlight) return null
        val next = inFlight + 1
        if (next > window.last) {
            current = null
            return null
        }
        current = next
        return Target(active, next)
    }

    private fun revokeLocked() {
        bookUrl = null
        window = IntRange.EMPTY
        current = null
    }
}
