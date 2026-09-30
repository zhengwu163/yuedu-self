package io.legado.app.help.readaloud.offline

/**
 * 章节加载与朗读续播的单次内存请求，不进行 IO，也不签发自动预取授权。
 * 先捕获导航身份，再绑定真实加载代次；同章的迟到查询不能绑定后来的请求。
 */
class ChapterReadAloudRequest {
    class Request internal constructor(val bookUrl: String, val index: Int, val continuation: String?)
    private var pending: Request? = null
    private var loadGeneration: Long? = null

    @Synchronized
    fun begin(bookUrl: String, index: Int, continuation: String?): Request {
        clear()
        return Request(bookUrl, index, continuation).also { pending = it }
    }

    @Synchronized
    fun bind(request: Request?, bookUrl: String, index: Int, generation: Long): Boolean {
        if (request == null || pending !== request || request.bookUrl != bookUrl ||
            request.index != index || (loadGeneration != null && loadGeneration != generation)
        ) return false
        loadGeneration = generation
        return true
    }

    @Synchronized
    fun complete(bookUrl: String, index: Int, generation: Long): Request? {
        val request = pending ?: return null
        if (request.bookUrl != bookUrl || request.index != index || loadGeneration != generation) return null
        clear()
        return request
    }

    @Synchronized
    fun completeCached(request: Request?, bookUrl: String, index: Int): Request? {
        if (request == null || pending !== request || request.bookUrl != bookUrl ||
            request.index != index
        ) return null
        clear()
        return request
    }

    @Synchronized
    fun clear() {
        pending = null
        loadGeneration = null
    }

    @Synchronized
    fun cancel(bookUrl: String, index: Int, generation: Long) {
        complete(bookUrl, index, generation)
    }
}
