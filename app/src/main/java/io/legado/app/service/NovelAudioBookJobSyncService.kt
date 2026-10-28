package io.legado.app.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.help.readaloud.novel.NovelAudioBookJobSync
import io.legado.app.help.readaloud.novel.NovelAudioBookJobs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 系统周期任务：在不计流量网络下把电脑生成完的章节导入手机。
 * 系统给的执行时间有限，每次最多跑 8 分钟，剩余章节留给下一次。
 */
class NovelAudioBookJobSyncService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!NovelAudioBookJobs.onHomeNetwork()) return false
        val deadline = SystemClock.elapsedRealtime() + MAX_RUN_MILLIS
        work = scope.launch {
            try {
                NovelAudioBookJobSync.syncAll { SystemClock.elapsedRealtime() < deadline }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AppLog.put("整本生成后台同步失败", error)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MAX_RUN_MILLIS = 8 * 60 * 1000L
    }
}
