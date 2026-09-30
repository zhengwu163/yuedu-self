package io.legado.app.help.readaloud.offline

import java.util.UUID

/**
 * 播放生命周期与自动预取授权之间的内存桥；不执行 IO、不持有 Android Service。
 * 请求、通知按钮、活动会话各至多一个槽位，进程重建默认拒绝。
 */
class AudioPrefetchLifecycle {
    internal val chapterRequests = ChapterReadAloudRequest()
    class Service internal constructor() {
        internal var token: AudioPrefetchSession.Token? = null
        internal var latestWork: Work? = null
    }

    /** 必须在异步工作发起前捕获，回调只能提交这个对象，不能重新读取当前 token。 */
    class Work internal constructor(
        val token: AudioPrefetchSession.Token,
        val bookUrl: String,
        internal val service: Service
    ) {
        internal val continuationId: String = UUID.randomUUID().toString()
    }

    class Window internal constructor(val work: Work, val chapters: IntRange)

    private class Request(
        val id: String,
        val bookUrl: String,
        val token: AudioPrefetchSession.Token,
        val service: Service?
    )

    private class ResumeOffer(val id: String, val bookUrl: String, val service: Service)

    private val session = AudioPrefetchSession()
    private var currentService: Service? = null
    private var token: AudioPrefetchSession.Token? = null
    private var bookUrl: String? = null
    private var pending: Request? = null
    private var resumeOffer: ResumeOffer? = null
    private var window: Window? = null
    private var deferredGesture: String? = null
    private var bookAdoptionRequest: String? = null

    /** 只读取发起时的工作身份；领取时再次校验，不因消息到达而借用最新 token。 */
    @Synchronized
    fun continuation(bookUrl: String): String? =
        currentService?.latestWork?.takeIf {
            isCurrent(it) && it.bookUrl == bookUrl
        }?.continuationId

    @Synchronized
    fun resolveContinuation(service: Service, id: String?, bookUrl: String): Work? {
        val previous = service.latestWork ?: return null
        if (id == null || previous.continuationId != id || previous.bookUrl != bookUrl ||
            !isCurrent(previous)
        ) return null
        return capture(service, bookUrl)
    }

    /** 只取消本次未投递请求；迟到的启动失败不能影响已消费或更新的请求。 */
    @Synchronized
    fun cancelRequest(request: String?) {
        if (request != null && (pending?.id == request || deferredGesture == request)) {
            revoke()
        }
    }

    /** 冷启动媒体键在查最近书之前记录用户动作，本步骤不授权任何一本书。 */
    @Synchronized
    fun beginDeferredPlay(): String =
        UUID.randomUUID().toString().also {
            revoke()
            deferredGesture = it
        }

    @Synchronized
    fun bindDeferredPlay(request: String, bookUrl: String): String? {
        if (request != deferredGesture) return null
        deferredGesture = null
        return requestUserPlay(bookUrl).also { bookAdoptionRequest = it }
    }

    @Synchronized
    fun createService(): Service {
        // 唯一例外是首次用户操作先于 Service.onCreate 的待投递请求。
        if (currentService != null || pending?.service != null) revoke()
        return Service().also { currentService = it }
    }

    @Synchronized
    fun requestUserPlay(bookUrl: String): String? {
        revoke()
        val issued = session.onUserPlay(bookUrl) ?: return null
        token = issued
        this.bookUrl = bookUrl
        return UUID.randomUUID().toString().also {
            pending = Request(it, bookUrl, issued, currentService)
        }
    }

    @Synchronized
    fun requestUserPlay(service: Service, bookUrl: String): String? {
        if (service !== currentService) return null
        return requestUserPlay(bookUrl)
    }

    @Synchronized
    fun isPendingUserPlay(request: String?, bookUrl: String): Boolean {
        val issued = pending ?: return false
        return request != null &&
            issued.id == request &&
            issued.bookUrl == bookUrl &&
            issued.token === token
    }

    @Synchronized
    fun acceptUserPlay(service: Service, request: String?, bookUrl: String): Boolean {
        val issued = pending ?: return false
        if (service !== currentService || request == null || issued.id != request ||
            issued.bookUrl != bookUrl || issued.token !== token ||
            (issued.service != null && issued.service !== service)
        ) return false
        pending = null
        bookAdoptionRequest = null
        service.token = issued.token
        service.latestWork = null
        return true
    }

    @Synchronized
    fun capture(service: Service, bookUrl: String): Work? {
        val issued = token ?: return null
        if (service !== currentService || service.token !== issued || this.bookUrl != bookUrl) return null
        return Work(issued, bookUrl, service).also {
            service.latestWork = it
            window = null
        }
    }

    @Synchronized
    fun isCurrent(work: Work?): Boolean =
        work != null && work.service === currentService && work.token === token &&
            work.service.token === token && work.service.latestWork === work

    @Synchronized
    fun assembled(work: Work?, bookUrl: String, index: Int, count: Int): IntRange {
        if (!isCurrent(work)) return IntRange.EMPTY
        val range = session.updatePosition(work!!.token, bookUrl, index, count)
        window = Window(work, range)
        return range
    }

    /** 调度器未来领取和发请求前都需复核；窗口快照不是持续有效的许可。 */
    @Synchronized
    fun isAllowed(work: Work?, target: Int): Boolean =
        isCurrent(work) && window?.work === work &&
            session.isAllowed(work!!.token, work.bookUrl, target)

    @Synchronized
    fun currentWindow(): Window? = window?.takeIf { isCurrent(it.work) }

    @Synchronized
    fun pause(service: Service) {
        // 当前 Service 的暂停也撤销尚未消费的用户请求；旧实例无权撤销新实例。
        if (service === currentService) revoke()
        service.token = null
        service.latestWork = null
    }

    @Synchronized
    fun destroy(service: Service) {
        if (service !== currentService) return
        revoke()
        currentService = null
    }

    @Synchronized
    fun revoke() {
        session.onStop()
        chapterRequests.clear()
        token = null
        bookUrl = null
        pending = null
        resumeOffer = null
        window = null
        deferredGesture = null
        bookAdoptionRequest = null
        currentService?.token = null
        currentService?.latestWork = null
    }

    @Synchronized
    fun bookChanged(old: String?, new: String?) {
        if (old == new) return
        // 冷启动只允许这次已绑定请求将空书籍填为预期书；后续任何换书仍撤销。
        val request = pending
        if (old == null && request != null && request.id == bookAdoptionRequest &&
            request.bookUrl == new
        ) {
            bookAdoptionRequest = null
            return
        }
        revoke()
    }

    /** 构建通知本身不授权；只有仍属本进程、本 Service、本书的按钮可消费一次。 */
    @Synchronized
    fun offerResume(service: Service, bookUrl: String): String? {
        if (service !== currentService || bookUrl.isBlank()) return null
        return UUID.randomUUID().toString().also {
            resumeOffer = ResumeOffer(it, bookUrl, service)
        }
    }

    @Synchronized
    fun acceptResume(service: Service, offer: String?, bookUrl: String): Boolean {
        val offered = resumeOffer ?: return false
        if (service !== currentService || offered.service !== service ||
            offered.id != offer || offered.bookUrl != bookUrl
        ) return false
        return acceptUserPlay(service, requestUserPlay(bookUrl), bookUrl)
    }
}

/** 全应用仅共享这一份自动授权状态；手动下载不接入此生命周期。 */
object AudioPrefetchPlayback {
    const val REQUEST = "audioPrefetchRequest"
    const val CONTINUATION = "audioPrefetchContinuation"
    const val RESUME_OFFER = "audioPrefetchResumeOffer"
    val lifecycle = AudioPrefetchLifecycle()
}
