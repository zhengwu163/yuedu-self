package io.legado.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.help.readaloud.novel.NovelAudioBookJobUploader
import io.legado.app.help.readaloud.novel.NovelAudioBookJobs
import io.legado.app.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 整本生成上传的前台服务：只负责保活与通知，逻辑在 NovelAudioBookJobs。
 * 断开 Wi‑Fi 时每分钟检查一次，回到 Wi‑Fi 后从未上传的章节继续；全部上传完自行结束。
 */
class NovelAudioBookJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.novel_audio_book_job_uploading_start)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (work?.isActive != true) work = scope.launch { runUploads() }
        return START_NOT_STICKY
    }

    private suspend fun runUploads() {
        val progressWatcher = scope.launch {
            while (isActive) {
                val progress = NovelAudioBookJobs.progress.value
                val text = if (progress.waitingForWifi) {
                    getString(R.string.novel_audio_book_job_waiting_wifi)
                } else {
                    getString(R.string.novel_audio_book_job_uploading, progress.uploaded, progress.total)
                }
                notificationManager().notify(NOTIFICATION_ID, notification(text))
                delay(1_000L)
            }
        }
        try {
            var waits = 0
            while (true) {
                val jobs = appDb.novelAudioBookJobDao.jobsInStates(listOf(NovelAudioBookJobStates.UPLOADING))
                if (jobs.isEmpty()) break
                val outcomes = jobs.map { job ->
                    try {
                        NovelAudioBookJobs.upload(job.jobId)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        AppLog.put("整本生成上传失败：${error.localizedMessage}")
                        NovelAudioBookJobUploader.Outcome.WAITING_NETWORK
                    }
                }
                when (NovelAudioBookJobUploader.nextPass(outcomes, waits, MAX_WIFI_WAITS)) {
                    NovelAudioBookJobUploader.Pass.AGAIN -> waits = 0
                    NovelAudioBookJobUploader.Pass.WAIT -> {
                        waits++
                        delay(WIFI_RETRY_MILLIS)
                    }
                    NovelAudioBookJobUploader.Pass.STOP -> break
                }
            }
        } finally {
            progressWatcher.cancel()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(NotificationChannel(
                AppConst.channelIdDownload,
                getString(R.string.novel_audio_book_job_title),
                NotificationManager.IMPORTANCE_LOW
            ))
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(getString(R.string.novel_audio_book_job_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4A0B
        private const val WIFI_RETRY_MILLIS = 60_000L
        private const val MAX_WIFI_WAITS = 30

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, NovelAudioBookJobService::class.java))
        }
    }
}
