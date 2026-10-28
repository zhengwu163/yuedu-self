package io.legado.app.help.readaloud.novel

import android.net.NetworkCapabilities
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.book.isLocal
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerClient
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import splitties.init.appCtx
import splitties.systemservices.connectivityManager

/**
 * 整本生成的手机端入口：建任务、上传、暂停 / 继续 / 取消 / 删除。
 * 上传在 NovelAudioBookJobService 前台服务里执行；同步与导入见 NovelAudioBookJobSync。
 */
object NovelAudioBookJobs {

    data class UploadProgress(val jobId: String = "", val uploaded: Int = 0, val total: Int = 0,
                              val waitingForWifi: Boolean = false)

    private val progressState = MutableStateFlow(UploadProgress())
    val progress: StateFlow<UploadProgress> = progressState

    private val dao get() = appDb.novelAudioBookJobDao
    private val snapshotLoader by lazy { NovelAudioChapterSnapshotLoader.create() }

    fun activeJob(book: Book): NovelAudioBookJobEntity? =
        dao.activeJobForBook(book.bookUrl, NovelAudioBookJobStates.ACTIVE)

    /** 返回 null 表示没有配置家庭电脑。 */
    fun client(): NovelAudioServerClient? =
        NovelAudioAndroidConfigStore.open(appCtx).load()?.newClient()

    fun serverScope(): String? =
        NovelAudioAndroidConfigStore.open(appCtx).load()?.let { NovelAudioIdentity.serverScope(it.baseUrl) }

    suspend fun create(book: Book, chapterCount: Int, currentIndex: Int): NovelAudioBookJobEntity {
        activeJob(book)?.let { return it }
        val credentials = NovelAudioAndroidConfigStore.open(appCtx).load()
            ?: throw NovelAudioServerException("CONFIG")
        val client = credentials.newClient()
        return NovelAudioBookJobCreator(dao, NovelAudioRoomCharacterStore(appDb), client::createBookJob)
            .create(
                workKey = NovelAudioIdentity.workKey(book),
                physicalBookUrl = book.bookUrl,
                title = book.name,
                serverScope = NovelAudioIdentity.serverScope(credentials.baseUrl),
                chapterCount = chapterCount,
                currentIndex = currentIndex
            )
    }

    /** 在前台服务内调用；返回值决定服务是否等待 Wi‑Fi 后重试。 */
    internal suspend fun upload(jobId: String): NovelAudioBookJobUploader.Outcome {
        val job = dao.job(jobId) ?: return NovelAudioBookJobUploader.Outcome.JOB_GONE
        val book = appDb.bookDao.getBook(job.physicalBookUrl)
            ?: return NovelAudioBookJobUploader.Outcome.JOB_GONE
        val client = client() ?: return NovelAudioBookJobUploader.Outcome.STOPPED
        dao.requeueMissing(jobId, NovelAudioBookJobUploadStates.PENDING,
            NovelAudioBookJobUploadStates.MISSING, System.currentTimeMillis())
        progressState.value = UploadProgress(jobId)
        val outcome = NovelAudioBookJobUploader(
            dao = dao,
            loadSnapshot = { index -> loadOrFetch(book, index) },
            upload = client::uploadBookJobChapter,
            markUploaded = client::markBookJobUploaded,
            canContinue = ::onHomeNetwork
        ).run(jobId) { uploaded, total ->
            progressState.value = UploadProgress(jobId, uploaded, total)
        }
        progressState.value = progressState.value.copy(
            waitingForWifi = outcome == NovelAudioBookJobUploader.Outcome.WAITING_NETWORK)
        return outcome
    }

    suspend fun pause(jobId: String) = control(jobId, "pause", NovelAudioBookJobStates.PAUSED)

    suspend fun resume(jobId: String) {
        val job = dao.job(jobId) ?: return
        control(jobId, "resume", resumedState(job.stateReason,
            dao.nextChapterInUploadState(jobId, NovelAudioBookJobUploadStates.PENDING) != null))
    }

    /** 暂停时还没传完（或仍有待传章节）就回到上传；否则只等电脑生成与同步。 */
    internal fun resumedState(pausedReason: String, hasPendingUpload: Boolean): String =
        if (hasPendingUpload || pausedReason == REASON_UPLOAD_UNFINISHED) NovelAudioBookJobStates.UPLOADING
        else NovelAudioBookJobStates.UPLOADED

    suspend fun cancel(jobId: String) = control(jobId, "cancel", NovelAudioBookJobStates.CANCELLED)

    /** 删除电脑上的任务与音频；手机上已导入的章节音频保留。 */
    suspend fun delete(jobId: String) {
        try {
            client()?.deleteBookJob(jobId)
        } catch (error: NovelAudioServerException) {
            if (error.kind != "UNKNOWN_JOB") throw error
        }
        dao.deleteJobWithChapters(jobId)
    }

    private suspend fun control(jobId: String, action: String, localState: String) {
        val client = client() ?: throw NovelAudioServerException("CONFIG")
        try {
            client.controlBookJob(jobId, action)
        } catch (error: NovelAudioServerException) {
            if (error.kind != "UNKNOWN_JOB") throw error
        }
        val unfinished = dao.job(jobId)?.state == NovelAudioBookJobStates.UPLOADING
        dao.setJobState(jobId, localState, if (unfinished) REASON_UPLOAD_UNFINISHED else "",
            System.currentTimeMillis())
    }

    /** 只读已缓存正文；在线书没缓存时先从书源取一次（会写入缓存）。 */
    private suspend fun loadOrFetch(book: Book, index: Int): NovelAudioChapterSnapshot? {
        snapshotLoader.load(book, index)?.let { return it }
        if (book.isLocal) return null
        return try {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return null
            val source = appDb.bookSourceDao.getBookSource(book.origin) ?: return null
            WebBook.getContentAwait(source, book, chapter)
            snapshotLoader.load(book, index)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.put("整本生成：第 ${index + 1} 章正文获取失败，稍后重试")
            null
        }
    }

    /** 上传只走家里的 Wi‑Fi（不计流量网络），避免在外面用流量传整本书。 */
    internal fun onHomeNetwork(): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    internal const val REASON_UPLOAD_UNFINISHED = "UPLOAD_UNFINISHED"
}
