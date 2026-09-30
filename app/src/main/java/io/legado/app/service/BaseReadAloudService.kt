@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.MediaHelp
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.ai.AiReadAloudRoleState
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.offline.AudioPrefetchLifecycle
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.help.readaloud.offline.ReadAloudAssemblyState
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.broadcastPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        @JvmStatic
        var isRun = false
            private set

        @JvmStatic
        var pause = true
            private set

        @JvmStatic
        var timeMinute: Int = 0
            private set

        // 定时朗读模式 0=按分钟 1=读完本章 2=剩余章节; remainChapters 为模式2剩余章数
        @JvmStatic
        var ttsTimerMode: Int = 0
            private set

        @JvmStatic
        var remainChapters: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        private const val TAG = "BaseReadAloudService"

    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    private val prefetchService = AudioPrefetchPlayback.lifecycle.createService()
    private val assemblyState = ReadAloudAssemblyState<TextChapter>()
    private var prefetchPlaybackWork: AudioPrefetchLifecycle.Work? = null
    private var prefetchResumeIntent: PendingIntent? = null

    /** P1/B1-③：段中触发时是否对齐到句首（装配时读取一次，全程生效） */
    @Volatile
    protected var alignSentenceStart = false

    /**
     * P1/B1-③：送引擎文本的起点（段中触发时对齐句首；偏好关闭则原样返回）。
     * 返回前按 [text] 长度收窄 —— 同时兜住既有的 `substring` 越界崩溃隐患（D6）。
     */
    protected fun sentenceAlignedStart(text: String, startPos: Int): Int {
        val aligned = if (alignSentenceStart && startPos > 0) {
            ReadAloudSentenceAligner.alignToSentenceStart(text, startPos)
        } else {
            startPos
        }
        return aligned.coerceIn(0, text.length)
    }
    var readAloudByPage = false
        private set

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        isRun = true
        pause = false
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        when (AppConfig.ttsTimerMode) {
            1 -> {
                ttsTimerMode = 1
                toastOnUi("读完本章后停止朗读")
                doDs()
            }
            2 -> {
                ttsTimerMode = 2
                remainChapters = AppConfig.ttsTimerChapters
                toastOnUi("读完 $remainChapters 章后停止朗读")
                doDs()
            }
            else -> {
                setTimer(AppConfig.ttsTimer)
                if (AppConfig.ttsTimer > 0) {
                    toastOnUi("朗读定时 ${AppConfig.ttsTimer} 分钟")
                }
            }
        }
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
    }

    open fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            dispatchPlayback(
                it.getString(AudioPrefetchPlayback.REQUEST),
                it.getString(AudioPrefetchPlayback.CONTINUATION)
            ) { work -> newReadAloud(play, pageIndex, startPos, work) }
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onDestroy() {
        assemblyState.clear()
        AudioPrefetchPlayback.lifecycle.destroy(prefetchService)
        prefetchResumeIntent?.cancel()
        prefetchResumeIntent = null
        super.onDestroy()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        pause = true
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> dispatchPlayback(
                intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION), flags
            ) { work ->
                newReadAloud(
                    intent.getBooleanExtra("play", true),
                    intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                    intent.getIntExtra("startPos", 0), work
                )
            }

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> {
                val offer = intent.getStringExtra(AudioPrefetchPlayback.RESUME_OFFER)
                val work = if (offer != null) {
                    if (flags and (START_FLAG_REDELIVERY or START_FLAG_RETRY) == 0 &&
                        AudioPrefetchPlayback.lifecycle.acceptResume(
                            prefetchService, offer, ReadBook.book?.bookUrl.orEmpty()
                        )
                    ) AudioPrefetchPlayback.lifecycle.capture(prefetchService, ReadBook.book?.bookUrl.orEmpty())
                    else null
                } else null
                if (offer != null && work == null) {
                    return super.onStartCommand(intent, flags, startId)
                }
                if (offer != null) {
                    resumePlayback(work)
                } else {
                    dispatchPlayback(
                        intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                        intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION), flags
                    ) { resumePlayback(it) }
                }
            }
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimerExt(
                intent.getIntExtra("mode", 0),
                intent.getIntExtra("minute", 0),
                intent.getIntExtra("chapters", 0)
            )
            IntentAction.stop -> {
                assemblyState.clear()
                AudioPrefetchPlayback.lifecycle.pause(prefetchService)
                stopSelf()
            }
            IntentAction.reInitTts -> onReInitTts()

            IntentAction.moveTo -> {
                // P1-12 兜底：播放面板拖进度/点 cue（原 action 无处理分支被静默丢弃）
                // 闭环为"定位到 cue 所在章节位置起播"；cue 级精确段落 seek 登记后续
                val expectedChapter = intent.getIntExtra("expectedChapterIndex", ReadBook.durChapterIndex)
                val chapterPosition = intent.getIntExtra("chapterPosition", 0)
                val play = intent.getBooleanExtra("play", BaseReadAloudService.isPlay())
                dispatchPlayback(
                    intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                    intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION), flags
                ) { moveToCueExt(expectedChapter, chapterPosition, play, it) }
            }

            IntentAction.selectChapter -> {
                // 兜底：播放面板选章（原 action 无处理分支被静默丢弃，朗读中选章完全无效）
                val chapterIndex = intent.getIntExtra("chapterIndex", ReadBook.durChapterIndex)
                val continuePlayback = intent.getBooleanExtra("continuePlayback", BaseReadAloudService.isPlay())
                dispatchPlayback(
                    intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                    intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION), flags
                ) { moveToChapterExt(chapterIndex, continuePlayback, it) }
            }

            IntentAction.playFromPosition -> {
                // 兜底：选句朗读（原 action 无处理分支被静默丢弃）：跳章后从 chapterPosition 起播
                val chapterIndex = intent.getIntExtra("chapterIndex", ReadBook.durChapterIndex)
                val chapterPosition = intent.getIntExtra("chapterPosition", 0)
                dispatchPlayback(
                    intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                    intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION), flags
                ) { playFromPositionExt(chapterIndex, chapterPosition, it) }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** 带身份的投递解析失败即丢弃，不能退化成无授权的普通播放。 */
    protected fun dispatchPlayback(
        request: String?, continuation: String?, flags: Int = 0,
        action: (AudioPrefetchLifecycle.Work?) -> Unit
    ) {
        val work = prefetchWork(request, continuation, flags)
        if ((request != null || continuation != null) && work == null) return
        action(work)
    }

    private fun prefetchWork(
        request: String?, continuation: String? = null, flags: Int = 0
    ): AudioPrefetchLifecycle.Work? {
        if (flags and (START_FLAG_REDELIVERY or START_FLAG_RETRY) != 0) return null
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        if (request != null) {
            if (!AudioPrefetchPlayback.lifecycle.acceptUserPlay(prefetchService, request, bookUrl)) return null
            return AudioPrefetchPlayback.lifecycle.capture(prefetchService, bookUrl)
        }
        return AudioPrefetchPlayback.lifecycle.resolveContinuation(prefetchService, continuation, bookUrl)
    }

    /** 媒体控制回调是新的用户播放动作；焦点/来电恢复仍走无授权的 resumeReadAloud。 */
    private fun resumeFromUser() {
        val request = AudioPrefetchPlayback.lifecycle.requestUserPlay(
            prefetchService, ReadBook.book?.bookUrl.orEmpty()
        ) ?: return
        val work = prefetchWork(request) ?: return
        if (onUserResumeFromMedia(work)) return
        resumePlayback(work)
    }

    /**
     * 子服务可以接管媒体控件发起的主动恢复。
     *
     * 返回 true 表示已经消费 [work]；默认行为保持原有文本朗读装配。
     */
    protected open fun onUserResumeFromMedia(work: AudioPrefetchLifecycle.Work): Boolean = false

    /** 正在装配时的恢复重新装配当前章，不用上一章的音频或窗口代替。 */
    private fun resumePlayback(work: AudioPrefetchLifecycle.Work?) {
        val prepared = assemblyState.prepared
        if (prepared != null && prepared === textChapter &&
            prepared.chapter.bookUrl == ReadBook.book?.bookUrl &&
            prepared.chapter.index == ReadBook.durChapterIndex && contentList.isNotEmpty()
        ) {
            resumeReadAloud()
            prefetchAssembled(work, prepared)
        } else {
            newReadAloudAtChapterPosition(ReadBook.durChapterPos, work)
        }
    }

    protected fun prefetchAssembled(
        work: AudioPrefetchLifecycle.Work?,
        bookUrl: String,
        chapterIndex: Int,
        chapterCount: Int
    ) {
        if (!AudioPrefetchPlayback.lifecycle.isCurrent(work)) return
        prefetchPlaybackWork = work
        AudioPrefetchPlayback.lifecycle.assembled(
            work, bookUrl, chapterIndex, chapterCount
        )
    }

    private fun prefetchAssembled(work: AudioPrefetchLifecycle.Work?, chapter: TextChapter) {
        if (pause || contentList.isEmpty() || !chapter.isCompleted ||
            chapter !== textChapter || ReadBook.book?.bookUrl != chapter.chapter.bookUrl
        ) return
        prefetchAssembled(
            work = work,
            bookUrl = chapter.chapter.bookUrl,
            chapterIndex = chapter.chapter.index,
            chapterCount = chapter.chaptersSize
        )
    }

    /** 引擎内重建（AD-02）：同服务类型切换时不下发 STOP，由子类重建引擎（系统 TTS=clearTTS+initTts） */
    open fun onReInitTts() {
    }

    /** E1/P0-5 AI 预热任务句柄（新预热/取消时代替，防 LLM 请求堆积） */
    private var preheatJob: Coroutine<*>? = null

    /**
     * E1/P0-5 AI 分镜预生成缓存预热（fire-and-forget）：
     * 起播装配完成后后台**直调 ensureCache**（STAGE_PREHEAT 档：无 keepAlive 前台保活），
     * 命中 DB 缓存/AI 关闭/无模型配置时内部短路零开销；失败仅留痕不阻断朗读。
     * 取消安全：Coroutine 封装守卫放行 CancellationException（Coroutine.kt:182-183），
     * 取消路径先清理孤儿 RUNNING 行（2.21），禁止 runCatching 包裹整体（项目取消传播铁律）
     */
    private fun preheatAiRoleCache(book: Book?, textChapter: TextChapter?, paragraphs: List<String>) {
        if (book == null || textChapter == null || paragraphs.isEmpty()) return
        if (!AppConfig.aiReadAloudRoleEnabled) return
        preheatJob?.cancel()
        preheatJob = execute {
            try {
                val result = AiReadAloudRoleService.ensureCache(
                    book,
                    textChapter,
                    paragraphs,
                    AiReadAloudRoleState.STAGE_PREHEAT
                )
                // 预热结果按 status 留痕（真机日志分析证据链：INFO 完成/短路、WARN 失败）
                when (result.status) {
                    AiReadAloudRoleState.STATUS_SKIPPED -> AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "AI 预热跳过：${result.message}",
                        level = AppLog.Level.INFO
                    )

                    AiReadAloudRoleState.STATUS_FAILED -> AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "AI 预热失败：${result.error}",
                        level = AppLog.Level.WARN
                    )

                    else -> AppLog.putDebugWithTag(
                        AppLog.TAG_TTS_TRACE,
                        "AI 预热完成 status=${result.status}",
                        level = AppLog.Level.INFO
                    )
                }
            } catch (e: CancellationException) {
                // 取消清理：防 RUNNING 行残留污染后续分配判断（2.21）
                AiReadAloudRoleService.cancelStaleRunningCacheRows(book.bookUrl)
                throw e
            }
        }.onError {
            AppLog.put("AI 预热异常：${it.localizedMessage}")
        }
    }

    /**
     * 章级跳转（P1-12/selectChapter 兜底）：跳章后按 play 续播/暂停；同章仅切换播放态。
     * 加载完成后经 newReadAloud 重新装配（对齐 IntentAction.play 语义）
     */
    private fun moveToChapterExt(
        targetIndex: Int, play: Boolean, prefetchWork: AudioPrefetchLifecycle.Work?
    ) {
        if (targetIndex == ReadBook.durChapterIndex) {
            if (play) resumePlayback(prefetchWork) else pauseReadAloud()
            return
        }
        playStop()
        if (play) resumeReadAloudInternal()
        ReadBook.openChapter(targetIndex.coerceAtLeast(0)) {
            if (play) {
                ReadBook.readAloud(true, prefetchContinuation = prefetchWork?.continuationId)
            } else {
                // 暂停态跳章：仅置装配标记，resume 时重装配新章
                pageChanged = true
            }
        }
    }

    /**
     * cue 定位兜底（P1-12）：同章按 chapterPosition 精确装配；跨章 openChapter 后按位装配
     */
    private fun moveToCueExt(
        targetChapter: Int, chapterPosition: Int, play: Boolean,
        prefetchWork: AudioPrefetchLifecycle.Work?
    ) {
        playStop()
        if (targetChapter == ReadBook.durChapterIndex) {
            val chapter = ReadBook.curTextChapter
            if (play && chapter != null && chapter.isCompleted && chapterPosition > 0) {
                resumeReadAloudInternal()
                newReadAloudAtChapterPosition(chapterPosition, prefetchWork)
            } else {
                if (play) resumePlayback(prefetchWork) else pauseReadAloud()
            }
            return
        }
        if (play) resumeReadAloudInternal()
        ReadBook.openChapter(targetChapter.coerceAtLeast(0), durChapterPos = chapterPosition) {
            if (play) {
                newReadAloudAtChapterPosition(chapterPosition, prefetchWork)
            } else {
                pageChanged = true
            }
        }
    }

    /**
     * 选句朗读兜底：跨章先 openChapter（durChapterPos 定位），完成后从 chapterPosition 起播
     */
    private fun playFromPositionExt(
        chapterIndex: Int, chapterPosition: Int,
        prefetchWork: AudioPrefetchLifecycle.Work?
    ) {
        playStop()
        resumeReadAloudInternal()
        if (chapterIndex != ReadBook.durChapterIndex) {
            ReadBook.openChapter(chapterIndex, durChapterPos = chapterPosition) {
                newReadAloudAtChapterPosition(chapterPosition, prefetchWork)
            }
        } else {
            // 同章选句被暂停或被系统打断后，沿用该位置恢复，而不是回退到页首。
            synchronized(ReadBook) {
                ReadBook.durChapterPos = chapterPosition.coerceAtLeast(0)
                // 同章定位只更新阅读进度，避免重复触发书源章节保存回调。
                ReadBook.saveRead(true)
            }
            newReadAloudAtChapterPosition(chapterPosition, prefetchWork)
        }
    }

    /** 按"章内字符位置"装配并起播：换算页号与页内偏移（对齐 newReadAloud 的 startPos 语义） */
    private fun newReadAloudAtChapterPosition(
        chapterPosition: Int,
        prefetchWork: AudioPrefetchLifecycle.Work?
    ) {
        val chapter = ReadBook.curTextChapter
        if (chapter == null || !chapter.isCompleted) {
            // 章未就绪：退化为整章起播
            ReadBook.readAloud(true, prefetchContinuation = prefetchWork?.continuationId)
            return
        }
        val pageIndex = chapter.getPageIndexByCharIndex(chapterPosition).coerceAtLeast(0)
        newReadAloud(true, pageIndex, (chapterPosition - chapter.getReadLength(pageIndex)).coerceAtLeast(0), prefetchWork)
    }

    private fun newReadAloud(
        play: Boolean, pageIndex: Int, startPos: Int,
        prefetchWork: AudioPrefetchLifecycle.Work?
    ) {
        // 迟到的跳章回调不可抢掉当前装配槽位；提交时还需再次复核。
        if (prefetchWork != null && !AudioPrefetchPlayback.lifecycle.isCurrent(prefetchWork)) return
        val assemblyRequest = assemblyState.begin()
        preheatJob?.cancel()
        val textChapter = ReadBook.curTextChapter ?: return
        val book = ReadBook.book ?: return
        val readToLast = toLast
        if (!play) AudioPrefetchPlayback.lifecycle.pause(prefetchService)
        execute(executeContext = IO) {
            if (!textChapter.isCompleted) {
                return@execute
            }
            var readAloudNumber = textChapter.getReadLength(pageIndex) + startPos
            val readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            // P1/B1-③：段中触发对齐句首偏好（每次装配读取一次）
            val alignSentenceStart = AppConfig.readAloudAlignSentenceStart
            val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            var pos = startPos
            val page = textChapter.getPage(pageIndex)!!
            if (pos > 0) {
                for (paragraph in page.paragraphs) {
                    val tmp = pos - paragraph.length - 1
                    if (tmp < 0) break
                    pos = tmp
                }
            }
            var nowSpeak = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            if (!readAloudByPage && startPos == 0 && !readToLast) {
                pos = page.chapterPosition -
                        textChapter.paragraphs[nowSpeak].chapterPosition
            }
            if (readToLast) {
                readAloudNumber = textChapter.getLastParagraphPosition()
                nowSpeak = contentList.lastIndex
                if (page.paragraphs.size == 1) {
                    pos = page.chapterPosition -
                            textChapter.paragraphs[nowSpeak].chapterPosition
                }
            }
            launch(Main) {
                // IO 仅计算局部结果；暂停、新装配、换书或失效工作均不能写回播放器。
                if (ReadBook.book?.bookUrl != book.bookUrl ||
                    ReadBook.curTextChapter !== textChapter ||
                    (prefetchWork != null && play &&
                        !AudioPrefetchPlayback.lifecycle.isCurrent(prefetchWork))
                ) return@launch
                if (!assemblyState.complete(assemblyRequest, textChapter)) return@launch
                this@BaseReadAloudService.pageIndex = pageIndex
                this@BaseReadAloudService.textChapter = textChapter
                this@BaseReadAloudService.readAloudNumber = readAloudNumber
                this@BaseReadAloudService.readAloudByPage = readAloudByPage
                this@BaseReadAloudService.alignSentenceStart = alignSentenceStart
                this@BaseReadAloudService.contentList = contentList
                this@BaseReadAloudService.nowSpeak = nowSpeak
                paragraphStartPos = pos
                if (readToLast) toLast = false
                // 当前播放章的 AI 标注预热不阻塞起播；暂停态装配不触发。
                if (play) preheatAiRoleCache(book, textChapter, contentList)
                if (play) play() else pageChanged = true
                if (play) prefetchAssembled(prefetchWork, textChapter)
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play() {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun playStop()

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        AudioPrefetchPlayback.lifecycle.pause(prefetchService)
        assemblyState.cancelPending()
        preheatJob?.cancel()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        postEvent(EventBus.TTS_PROGRESS, progress)
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            playStop()
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    ReadBook.moveToPrevPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            toLast = true
            ReadBook.moveToPrevChapter(
                true, fromReadAloud = true,
                prefetchContinuation = prefetchPlaybackWork?.continuationId
            )
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            playStop()
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            play()
        } else {
            if (!checkTimerAtChapterEnd()) {
                nextChapter()
            }
        }
    }

    private fun setTimer(minute: Int) {
        setTimerExt(0, minute, 0)
    }

    // 定时朗读统一入口: mode 0=按分钟(恢复原有行为) 1=读完本章 2=剩余 chapters 章 (R7.2)
    private fun setTimerExt(mode: Int, minute: Int, chapters: Int) {
        ttsTimerMode = mode
        AppConfig.ttsTimerMode = mode
        remainChapters = 0
        when (mode) {
            1 -> {
                timeMinute = 0
                AppConfig.ttsTimer = 0
                toastOnUi("读完本章后停止朗读")
            }
            2 -> {
                timeMinute = 0
                AppConfig.ttsTimer = 0
                remainChapters = chapters
                AppConfig.ttsTimerChapters = chapters
                toastOnUi("读完 $chapters 章后停止朗读")
            }
            else -> {
                timeMinute = minute
                AppConfig.ttsTimer = minute
            }
        }
        doDs()
    }

    // 自然读完当前章节时判定是否按定时模式停止; 返回 true 表示已停止不再进入下一章
    internal fun checkTimerAtChapterEnd(): Boolean {
        when (ttsTimerMode) {
            1 -> {
                toastOnUi("读完本章，定时停止朗读")
                ReadAloud.stop(this)
                return true
            }
            2 -> {
                remainChapters--
                if (remainChapters <= 0) {
                    toastOnUi("已读完设定章节，定时停止朗读")
                    ReadAloud.stop(this)
                    return true
                }
            }
        }
        return false
    }

    private fun addTimer() {
        if (timeMinute == 180) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        if (ttsTimerMode != 0) {
            // 按章节定时: 无分钟倒计时, 章末由 checkTimerAtChapterEnd 判定
            return
        }
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    if (timeMinute >= 0) {
                        timeMinute--
                    }
                    if (timeMinute == 0) {
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
                .addCustomAction(
                    "ACTION_ADD_TIMER",
                    getString(R.string.set_timer),
                    R.drawable.ic_time_add_24dp
                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        mediaSessionCompat.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                resumeFromUser()
            }

            override fun onPause() {
                pauseReadAloud()
            }

            override fun onSkipToNext() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    nextChapter()
                } else {
                    nextP()
                }
            }

            override fun onSkipToPrevious() {
                if (getPrefBoolean("mediaButtonPerNext", false)) {
                    prevChapter()
                } else {
                    prevP()
                }
            }

            override fun onStop() {
                assemblyState.clear()
                AudioPrefetchPlayback.lifecycle.pause(prefetchService)
                stopSelf()
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                if (action == "ACTION_ADD_TIMER") addTimer()
            }

            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(
                    this@BaseReadAloudService, mediaButtonEvent
                )
            }
        })
        mediaSessionCompat.setMediaButtonReceiver(
            broadcastPendingIntent<MediaButtonReceiver>(Intent.ACTION_MEDIA_BUTTON)
        )
        mediaSessionCompat.isActive = true
    }

    private fun upMediaMetadata() {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            ttsTimerMode == 1 -> getString(R.string.read_aloud_timer_chapter)
            ttsTimerMode == 2 && remainChapters > 0 -> getString(
                R.string.read_aloud_timer_chapters,
                remainChapters
            )
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        val metadata = MediaMetadataCompat.Builder()
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, cover)
            .putText(MediaMetadataCompat.METADATA_KEY_TITLE, ReadBook.curTextChapter?.title ?: "null")
            .putText(MediaMetadataCompat.METADATA_KEY_ARTIST, nTitle)
            .putText(MediaMetadataCompat.METADATA_KEY_ALBUM, ReadBook.book?.author ?: "null")
//            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, nowSpeak.toLong())
            .build()
        mediaSessionCompat.setMetadata(metadata)
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumePlayback(null)
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute(context = Main) {
            try {
                upMediaMetadata()
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            ttsTimerMode == 1 -> getString(R.string.read_aloud_timer_chapter)
            ttsTimerMode == 2 && remainChapters > 0 -> getString(
                R.string.read_aloud_timer_chapters,
                remainChapters
            )
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = ReadBook.curTextChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                prefetchResumePendingIntent()
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            .setMediaSession(mediaSessionCompat.sessionToken)
        )
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute(context = Main) {
            try {
                upMediaMetadata()
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    private fun prefetchResumePendingIntent(): PendingIntent? {
        prefetchResumeIntent?.cancel()
        prefetchResumeIntent = null
        val offer = AudioPrefetchPlayback.lifecycle.offerResume(
            prefetchService, ReadBook.book?.bookUrl.orEmpty()
        ) ?: return null
        val intent = Intent(this, javaClass).apply {
            action = IntentAction.resume
            // extras 不参与 PendingIntent 身份匹配；每次按钮必须保持自己的票据。
            setData(Uri.Builder().scheme("legado").authority("read-aloud-resume")
                .appendPath(offer).build())
            putExtra(AudioPrefetchPlayback.RESUME_OFFER, offer)
        }
        return PendingIntent.getService(
            this, 17041, intent, PendingIntent.FLAG_IMMUTABLE
        ).also { prefetchResumeIntent = it }
    }

    open fun prevChapter() {
        toLast = false
        resumeReadAloudInternal()
        ReadBook.moveToPrevChapter(
            true, toLast = false, fromReadAloud = true,
            prefetchContinuation = prefetchPlaybackWork?.continuationId
        )
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        resumeReadAloudInternal()
        if (!ReadBook.moveToNextChapter(
                true, fromReadAloud = true,
                prefetchContinuation = prefetchPlaybackWork?.continuationId
            )
        ) {
            AudioPrefetchPlayback.lifecycle.pause(prefetchService)
            stopSelf()
        }
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumePlayback(null)
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
