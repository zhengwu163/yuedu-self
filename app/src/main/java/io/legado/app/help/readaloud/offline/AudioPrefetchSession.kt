package io.legado.app.help.readaloud.offline

/**
 * 自动音频预取的内存授权状态，每实例至多一条活动会话；应用调度器应全局共享同一实例。
 *
 * 调用方负责连接显式用户音频播放动作：不能根据 isRun、打开书架、文字阅读或恢复进程
 * 调用 [onUserPlay]；未主动播放的书籍没有授权。暂停、停止、换书入口必须及时撤销授权。
 * 本类只处理自动任务的授权，不操作手动任务，也不负责 IO、持久化或删除音频。
 *
 * 各方法同步保护状态，但 [isAllowed] 与实际网络发起不是一个原子操作。
 * 调度器仍须串行化播放/取消/位置更新与请求入口，并在发起请求前复核授权；
 * 已发起请求的取消由调度器负责，不能把返回的窗口快照当作持续有效的许可。
 */
class AudioPrefetchSession {

    /** 不透明、不可持久化的票据；仅按本实例当前签发对象的引用身份校验。 */
    sealed interface Token

    private class IssuedToken : Token

    private var activeToken: Token? = null
    private var activeBookUrl: String? = null
    private var window = IntRange.EMPTY

    /** 每次显式播放均轮换票据（包括同一本书）；非法书籍也会撤销旧授权。 */
    @Synchronized
    fun onUserPlay(bookUrl: String): Token? {
        revoke()
        if (bookUrl.isNullOrBlank()) return null
        val token = IssuedToken()
        activeToken = token
        activeBookUrl = bookUrl
        return token
    }

    /**
     * 更新零基章节位置并返回当前章之后、最多三章的不可变窗口。
     * chapterCount 为总章节数，合法位置为 0 <= currentIndex < chapterCount。
     * 旧票据/其他实例票据无权修改状态；当前票据携带非法数据则撤销授权，需重新主动播放。
     * 调用方须按最新播放位置顺序调用，不能用预取完成回调回填旧位置。
     */
    @Synchronized
    fun updatePosition(
        token: Token?,
        bookUrl: String,
        currentIndex: Int,
        chapterCount: Int
    ): IntRange {
        if (token == null || token !== activeToken) return IntRange.EMPTY
        if (bookUrl.isNullOrBlank() || bookUrl != activeBookUrl ||
            chapterCount <= 0 || currentIndex < 0 || currentIndex >= chapterCount
        ) {
            revoke()
            return IntRange.EMPTY
        }

        // 先提升为 Long 再加三，避免接近 Int.MAX_VALUE 时发生溢出。
        // 已验证 index < count，端点转换回 Int 安全；书末自然形成空区间。
        val first = currentIndex.toLong() + 1L
        val last = minOf(currentIndex.toLong() + 3L, chapterCount.toLong() - 1L)
        window = first.toInt()..last.toInt()
        return window
    }

    /** 无副作用的请求前复核；从最新窗口移出的排队目标立即被拒绝。 */
    @Synchronized
    fun isAllowed(token: Token?, bookUrl: String, targetIndex: Int): Boolean =
        token != null && token === activeToken &&
            bookUrl == activeBookUrl && targetIndex in window

    /** 生命周期入口作用于当前会话，必须与显式播放动作串行连接，而非连接旧任务回调。 */
    @Synchronized
    fun onPause() = revoke()

    @Synchronized
    fun onStop() = revoke()

    /** 只撤销，不因打开另一本书而自动签发票据。 */
    @Synchronized
    fun onBookChanged() = revoke()

    // 仅在持有本实例监视器的入口中调用，票据与窗口一起失效。
    private fun revoke() {
        activeToken = null
        activeBookUrl = null
        window = IntRange.EMPTY
    }
}
