package io.legado.app.help.readaloud.novel

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.offline.NovelAudioArtifactStore
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import io.legado.app.help.readaloud.offline.canDecodeNovelAudio
import io.legado.app.help.readaloud.server.NovelAudioServerClient
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.service.NovelAudioBookJobService
import io.legado.app.service.NovelAudioBookJobSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import java.io.File

/**
 * 同步的生产接线：在家里 Wi‑Fi 下把电脑已完成的章节导入手机。
 * 触发点：系统周期任务（不计流量网络）、App 启动、打开书籍详情、播放到未导入的章节。
 */
object NovelAudioBookJobSync {

    private val mutex = Mutex()
    private val snapshotLoader by lazy { NovelAudioChapterSnapshotLoader.create() }

    /** App 启动：续传未传完的任务，并安排后台同步。离开家里 Wi‑Fi 时什么都不做。 */
    fun onAppStart(context: Context) {
        Coroutine.async(executeContext = Dispatchers.IO) {
            val active = appDb.novelAudioBookJobDao.jobsInStates(NovelAudioBookJobStates.ACTIVE)
            schedule(context, active.isNotEmpty())
            if (active.isEmpty() || !NovelAudioBookJobs.onHomeNetwork()) return@async
            if (active.any { it.state == NovelAudioBookJobStates.UPLOADING }) {
                NovelAudioBookJobService.start(context)
            }
            syncAll()
        }.onError { AppLog.put("整本生成同步失败", it) }
    }

    /** 打开书籍详情时调用：只同步这本书。 */
    fun syncBookSoon(book: Book) {
        Coroutine.async(executeContext = Dispatchers.IO) {
            val job = NovelAudioBookJobs.activeJob(book) ?: return@async
            if (NovelAudioBookJobs.onHomeNetwork()) syncJob(job.jobId)
        }.onError { AppLog.put("整本生成同步失败", it) }
    }

    enum class PlaybackSource { NOT_BOOK_JOB, IMPORTED, NOT_READY }

    /**
     * 播放准备在逐章生成之前调用：整本任务里已上传、未导入的章节立即尝试从电脑导入；
     * 电脑还没生成完（或不在家）就返回 NOT_READY，由播放器暂停并提示，不再逐章请求。
     */
    suspend fun playbackSource(bookUrl: String, chapterIndex: Int): PlaybackSource {
        val dao = appDb.novelAudioBookJobDao
        val job = dao.activeJobForBook(bookUrl, NovelAudioBookJobStates.ACTIVE)
            ?: return PlaybackSource.NOT_BOOK_JOB
        if (!NovelAudioBookJobImporter.shouldImportForPlayback(dao.chapter(job.jobId, chapterIndex))) {
            return PlaybackSource.NOT_BOOK_JOB
        }
        if (!NovelAudioBookJobs.onHomeNetwork()) return PlaybackSource.NOT_READY
        val imported = try {
            syncJob(job.jobId, chapterIndex).imported > 0
        } catch (error: NovelAudioServerException) {
            false
        }
        return if (imported) PlaybackSource.IMPORTED else PlaybackSource.NOT_READY
    }

    const val NOT_READY_MESSAGE = "这一章电脑还在生成，回到家里 Wi‑Fi 后会自动下载"

    suspend fun syncAll(canContinue: () -> Boolean = { true }) {
        appDb.novelAudioBookJobDao.jobsInStates(NovelAudioBookJobStates.ACTIVE).forEach {
            if (!canContinue()) return
            syncJob(it.jobId, canContinue = canContinue)
        }
        schedule(appCtx, appDb.novelAudioBookJobDao.jobsInStates(NovelAudioBookJobStates.ACTIVE).isNotEmpty())
    }

    private suspend fun syncJob(
        jobId: String,
        onlyChapter: Int? = null,
        canContinue: () -> Boolean = { true }
    ): NovelAudioBookJobImporter.Summary = mutex.withLock {
        val client = NovelAudioBookJobs.client() ?: return NovelAudioBookJobImporter.Summary()
        val job = appDb.novelAudioBookJobDao.job(jobId) ?: return NovelAudioBookJobImporter.Summary()
        val book = appDb.bookDao.getBook(job.physicalBookUrl) ?: return NovelAudioBookJobImporter.Summary()
        importer(client, book).sync(jobId, onlyChapter, canContinue)
    }

    private fun importer(client: NovelAudioServerClient, book: Book): NovelAudioBookJobImporter {
        val root = File(appCtx.filesDir, NovelAudioPreparationEnvironment.AUDIO_DIRECTORY)
        val decoder: (File) -> Boolean = ::canDecodeNovelAudio
        val repository = NovelAudioRepository(appDb)
        return NovelAudioBookJobImporter(
            dao = appDb.novelAudioBookJobDao,
            registry = CharacterRegistry(NovelAudioRoomCharacterStore(appDb)),
            loadSnapshot = { snapshotLoader.load(book, it) },
            fetchDetail = client::bookJob,
            fetchResult = client::bookJobChapter,
            fetchAudio = client::bookJobAudio,
            markDownloaded = client::markBookJobChapterDownloaded,
            savePlan = { plan, fetch ->
                val execution = repository.savePlanForExecution(plan, NovelAudioRetention.PINNED)
                execution != null && NovelAudioDownloadCoordinator(
                    repository = repository,
                    artifactStore = NovelAudioArtifactStore(rootDirectory = root, decoder = decoder),
                    filesRoot = root,
                    synthesize = fetch,
                    decoder = decoder
                ).downloadPlan(execution) { true } == NovelAudioDownloadCoordinator.Result.Ready
            }
        )
    }

    /** 有未结束的任务时安排每 30 分钟一次、只在不计流量网络下运行的系统任务；没有就取消。 */
    fun schedule(context: Context, hasActiveJobs: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!hasActiveJobs) {
            scheduler.cancel(SYNC_JOB_ID)
            return
        }
        if (scheduler.allPendingJobs.any { it.id == SYNC_JOB_ID }) return
        scheduler.schedule(
            JobInfo.Builder(SYNC_JOB_ID, ComponentName(context, NovelAudioBookJobSyncService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(SYNC_PERIOD_MILLIS)
                .setPersisted(true)
                .build()
        )
    }

    private const val SYNC_JOB_ID = 0x4A0C
    private const val SYNC_PERIOD_MILLIS = 30 * 60 * 1000L
}
