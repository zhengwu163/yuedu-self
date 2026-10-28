package io.legado.app.ui.book.info

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.help.readaloud.novel.NovelAudioBookJobStatus
import io.legado.app.help.readaloud.novel.NovelAudioBookJobSync
import io.legado.app.help.readaloud.novel.NovelAudioBookJobs
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.service.NovelAudioBookJobService
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 三套书籍详情页共用的「整本生成」入口（书架长按也进入书籍详情）。
 * 只用现有的确认框与操作列表对话框，配色随主题，不新增自定义视图。
 */
object NovelAudioBookJobEntry {

    fun open(activity: AppCompatActivity, book: Book?) {
        if (book == null || book.bookUrl.isBlank()) return
        activity.lifecycleScope.launch {
            try {
                val job = withContext(Dispatchers.IO) { NovelAudioBookJobs.activeJob(book) }
                if (job == null) confirmStart(activity, book) else showProgress(activity, book, job)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.put("整本生成入口失败", error)
                activity.toastOnUi(error.localizedMessage)
            }
        }
    }

    private suspend fun confirmStart(activity: AppCompatActivity, book: Book) {
        if (NovelAudioBookJobs.client() == null) {
            activity.toastOnUi(R.string.novel_audio_book_job_no_server)
            return
        }
        val chapterCount = withContext(Dispatchers.IO) { appDb.bookChapterDao.getChapterCount(book.bookUrl) }
        if (chapterCount <= 0) {
            activity.toastOnUi(R.string.novel_audio_book_job_no_chapters)
            return
        }
        val current = book.durChapterIndex.coerceIn(0, chapterCount - 1)
        activity.showComposeConfirmDialog(
            title = activity.getString(R.string.novel_audio_book_job_title),
            message = NovelAudioBookJobStatus.confirmMessage(chapterCount, current, totalChars(book)),
            positiveText = activity.getString(R.string.novel_audio_book_job_start),
            onPositive = { start(activity, book, chapterCount, current) }
        )
    }

    private fun start(activity: AppCompatActivity, book: Book, chapterCount: Int, current: Int) {
        if (!NovelAudioBookJobs.onHomeNetwork()) {
            activity.toastOnUi(R.string.novel_audio_book_job_need_wifi)
            return
        }
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { NovelAudioBookJobs.create(book, chapterCount, current) }
                NovelAudioBookJobService.start(activity)
                NovelAudioBookJobSync.schedule(activity, true)
                activity.toastOnUi(R.string.novel_audio_book_job_started)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NovelAudioServerException) {
                activity.toastOnUi(error.message)
            }
        }
    }

    private suspend fun showProgress(activity: AppCompatActivity, book: Book, job: NovelAudioBookJobEntity) {
        val (rows, host) = withContext(Dispatchers.IO) {
            if (NovelAudioBookJobs.onHomeNetwork()) {
                // 打开进度时顺手同步一次：电脑已完成的章节立即下载。
                kotlin.runCatching { NovelAudioBookJobSync.syncBookNow(book) }
            }
            val host = if (NovelAudioBookJobs.onHomeNetwork()) {
                kotlin.runCatching { NovelAudioBookJobs.client()?.bookJob(job.jobId) }.getOrNull()
            } else null
            appDb.novelAudioBookJobDao.chapters(job.jobId) to host
        }
        val latest = withContext(Dispatchers.IO) { appDb.novelAudioBookJobDao.job(job.jobId) } ?: return
        val paused = latest.state == NovelAudioBookJobStates.PAUSED
        val actions = listOf(
            activity.getString(if (paused) R.string.novel_audio_book_job_resume else R.string.novel_audio_book_job_pause),
            activity.getString(R.string.novel_audio_book_job_cancel),
            activity.getString(R.string.novel_audio_book_job_delete)
        )
        activity.showComposeActionListDialog(
            title = activity.getString(R.string.novel_audio_book_job_title),
            labels = actions,
            message = NovelAudioBookJobStatus.progress(latest, rows, host),
            dangerIndices = setOf(1, 2),
            negativeText = activity.getString(R.string.novel_audio_book_job_close)
        ) { index ->
            when (index) {
                0 -> control(activity) {
                    if (paused) {
                        NovelAudioBookJobs.resume(job.jobId)
                        NovelAudioBookJobService.start(activity)
                    } else {
                        NovelAudioBookJobs.pause(job.jobId)
                    }
                }
                1 -> confirmDanger(activity, R.string.novel_audio_book_job_cancel_confirm) {
                    control(activity) { NovelAudioBookJobs.cancel(job.jobId) }
                }
                2 -> confirmDanger(activity, R.string.novel_audio_book_job_delete_confirm) {
                    control(activity) { NovelAudioBookJobs.delete(job.jobId) }
                }
            }
        }
    }

    private fun confirmDanger(activity: AppCompatActivity, message: Int, action: () -> Unit) {
        activity.showComposeConfirmDialog(
            title = activity.getString(R.string.novel_audio_book_job_title),
            message = activity.getString(message),
            dangerPositive = true,
            onPositive = action
        )
    }

    private fun control(activity: AppCompatActivity, action: suspend () -> Unit) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: NovelAudioServerException) {
                activity.toastOnUi(error.message)
            }
        }
    }

    /** 书籍字数未知时不估算音频时长与占用空间。 */
    private fun totalChars(book: Book): Long? {
        val text = book.wordCount?.trim().orEmpty()
        val number = Regex("([0-9]+(?:\\.[0-9]+)?)").find(text)?.value?.toDoubleOrNull() ?: return null
        return when {
            "万" in text -> (number * 10_000).toLong()
            else -> number.toLong()
        }.takeIf { it > 0 }
    }
}
