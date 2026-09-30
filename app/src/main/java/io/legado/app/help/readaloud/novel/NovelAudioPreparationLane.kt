package io.legado.app.help.readaloud.novel

/**
 * 当前章准备与 AUTO 预取之间的车道仲裁。
 *
 * 存在的原因是两处硬约束：准备协调器只有一个请求槽，
 * 预算账本对同一账本文件也只放行一个生成请求。若不仲裁，
 * AUTO 预取会挤掉用户正在等待的当前章，或直接撞并发上限失败。
 *
 * 规则：当前章永远可进入并抢占 AUTO；AUTO 只在没有当前章在途时进入，
 * 且同时只允许一个目标，也不得指向已经播放过的当前章自身。
 * 完成回调按书籍与章节精确匹配，旧目标的迟到完成不会释放新目标。
 * 本类只做内存仲裁，不持有授权也不做 IO。
 */
class NovelAudioPreparationLane {

    private data class Target(val bookUrl: String, val chapterIndex: Int)

    private var current: Target? = null
    private var auto: Target? = null

    // 最近一次当前章，用于阻止 AUTO 重复准备用户已在听的那一章。
    private var lastCurrent: Target? = null

    val isCurrentActive: Boolean
        @Synchronized get() = current != null

    @Synchronized
    fun isAutoActive(bookUrl: String, chapterIndex: Int): Boolean =
        auto == Target(bookUrl, chapterIndex)

    /** 当前章请求：总是接纳，并抢占任何在途 AUTO 目标。 */
    @Synchronized
    fun beginCurrent(bookUrl: String, chapterIndex: Int): Boolean {
        if (!isValid(bookUrl, chapterIndex)) return false
        val target = Target(bookUrl, chapterIndex)
        // 在途 AUTO 一律让位；换书时旧书的 AUTO 结果也不得回填到新书。
        auto = null
        current = target
        lastCurrent = target
        return true
    }

    /** 只有精确匹配的当前章才能释放车道；旧章的迟到完成被忽略。 */
    @Synchronized
    fun finishCurrent(bookUrl: String, chapterIndex: Int) {
        if (current == Target(bookUrl, chapterIndex)) current = null
    }

    /** AUTO 目标：当前章在途时拒绝，已有 AUTO 在途时拒绝，且不得指向当前章自身。 */
    @Synchronized
    fun beginAuto(bookUrl: String, chapterIndex: Int): Boolean {
        if (!isValid(bookUrl, chapterIndex)) return false
        if (current != null) return false
        if (auto != null) return false
        val target = Target(bookUrl, chapterIndex)
        if (target == lastCurrent) return false
        auto = target
        return true
    }

    /** 只有精确匹配的 AUTO 目标才能释放车道；旧目标的迟到完成被忽略。 */
    @Synchronized
    fun finishAuto(bookUrl: String, chapterIndex: Int) {
        if (auto == Target(bookUrl, chapterIndex)) auto = null
    }

    /** 暂停、停止、换书等撤销授权的入口必须同步清空两条车道。 */
    @Synchronized
    fun revoke() {
        current = null
        auto = null
        lastCurrent = null
    }

    private fun isValid(bookUrl: String, chapterIndex: Int): Boolean =
        bookUrl.isNotBlank() && chapterIndex >= 0
}
