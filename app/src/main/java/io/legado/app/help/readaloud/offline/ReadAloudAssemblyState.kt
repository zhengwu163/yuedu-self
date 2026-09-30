package io.legado.app.help.readaloud.offline

/** 单个朗读服务的装配槽位；调用者在主线程提交结果与操作播放器。 */
internal class ReadAloudAssemblyState<T : Any> {
    class Request internal constructor()
    private var pending: Request? = null
    var prepared: T? = null
        private set

    @Synchronized
    fun begin(): Request {
        prepared = null
        return Request().also { pending = it }
    }

    @Synchronized
    fun complete(request: Request, result: T): Boolean {
        if (pending !== request) return false
        pending = null
        prepared = result
        return true
    }

    @Synchronized
    fun cancelPending() {
        pending = null
    }

    @Synchronized
    fun clear() {
        cancelPending()
        prepared = null
    }
}
