package io.legado.app.model

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.novel.NovelAudioPreparationCoordinator
import io.legado.app.help.readaloud.novel.NovelAudioPreparationPolicy
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechRouteServiceResolver
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.LogUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.startForegroundServiceCompat
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx

object ReadAloud {
    // 当前生效路由（AD-01）：经 resolveSpeechRoute 纯同步解析，服务内装配以此为准
    var currentRoute: SpeechRoute = SpeechRoute.resolveSpeechRoute(ttsEngine)
        private set
    private var aloudClass: Class<*> = routeToClass(currentRoute)
    val ttsEngine get() = ReadBook.book?.getTtsEngine() ?: AppConfig.ttsEngine

    internal fun routeToClass(route: SpeechRoute): Class<*> {
        return SpeechRouteServiceResolver.routeToClass(route)
    }

    private fun getReadAloudClass(): Class<*> {
        return routeToClass(SpeechRoute.resolveSpeechRoute(ttsEngine))
    }

    /**
     * 续播意图（AD-02）：切换期间上收数据层，面板 STOP 分支经 consumePendingSwitch 取用后重建续播
     */
    data class PendingSwitch(
        val wasPlaying: Boolean,
        val pageIndex: Int,
        val startPos: Int
    )

    @Volatile
    private var pendingSwitch: PendingSwitch? = null

    fun consumePendingSwitch(): PendingSwitch? {
        val pending = pendingSwitch
        pendingSwitch = null
        return pending
    }

    fun upReadAloudClass() {
        val oldClass = aloudClass
        val newRoute = SpeechRoute.resolveSpeechRoute(ttsEngine)
        val newClass = routeToClass(newRoute)
        // TtsTrace 真机联调：引擎切换路由判定关键证据（raw 配置 → 解析结果 → 分支走向）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "upReadAloudClass raw=${ttsEngine?.take(120) ?: "null"} → type=${newRoute.engineType} value=${newRoute.engineValue} sameService=${newClass == oldClass} isRun=${BaseReadAloudService.isRun}",
            level = AppLog.Level.INFO
        )
        if (BaseReadAloudService.isRun) {
            if (newClass == oldClass) {
                // 同服务类型：引擎内重建（reInitTts），不整服务重启（AD-02）
                val intent = Intent(appCtx, oldClass)
                intent.action = IntentAction.reInitTts
                runCatching {
                    appCtx.startForegroundServiceCompat(intent)
                }
            } else {
                // 跨类型：捕获续播意图 → 用重算前旧 Class 引用 stop（重算后字段已变，禁用字段发 Intent）→ 面板 STOP 消费 PendingSwitch 重建续播
                pendingSwitch = PendingSwitch(
                    wasPlaying = BaseReadAloudService.isPlay(),
                    pageIndex = ReadBook.durPageIndex,
                    startPos = ReadBook.durChapterPos
                )
                val intent = Intent(appCtx, oldClass)
                intent.action = IntentAction.stop
                runCatching {
                    appCtx.startForegroundServiceCompat(intent)
                }
            }
        }
        currentRoute = newRoute
        aloudClass = newClass
    }

    fun play(
        context: Context,
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0,
        userInitiated: Boolean = false,
        prefetchRequest: String? = null,
        prefetchContinuation: String? =
            AudioPrefetchPlayback.lifecycle.continuation(ReadBook.book?.bookUrl.orEmpty())
    ) {
        prepareNovelAudioIfRequested(
            play = play,
            userInitiated = userInitiated,
            prefetchRequest = prefetchRequest,
            bookUrl = ReadBook.book?.bookUrl.orEmpty(),
            chapterIndex = ReadBook.durChapterIndex
        )
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.play
        intent.putExtra("play", play)
        intent.putExtra("pageIndex", pageIndex)
        intent.putExtra("startPos", startPos)
        val request = prefetchRequest ?: if (play && userInitiated) {
            AudioPrefetchPlayback.lifecycle.requestUserPlay(ReadBook.book?.bookUrl.orEmpty())
        } else null
        intent.putExtra(AudioPrefetchPlayback.REQUEST, request)
        intent.putExtra(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
        LogUtils.d("ReadAloud", intent.toString())
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            AudioPrefetchPlayback.lifecycle.cancelRequest(request)
            val msg = "启动朗读服务出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun playByEventBus(
        play: Boolean = true,
        pageIndex: Int = ReadBook.durPageIndex,
        startPos: Int = 0,
        userInitiated: Boolean = false,
        prefetchRequest: String? = null,
        prefetchContinuation: String? =
            AudioPrefetchPlayback.lifecycle.continuation(ReadBook.book?.bookUrl.orEmpty())
    ) {
        prepareNovelAudioIfRequested(
            play = play,
            userInitiated = userInitiated,
            prefetchRequest = prefetchRequest,
            bookUrl = ReadBook.book?.bookUrl.orEmpty(),
            chapterIndex = ReadBook.durChapterIndex
        )
        val bundle = Bundle().apply {
            putBoolean("play", play)
            putInt("pageIndex", pageIndex)
            putInt("startPos", startPos)
            putString(AudioPrefetchPlayback.REQUEST, prefetchRequest ?: if (play && userInitiated) {
                AudioPrefetchPlayback.lifecycle.requestUserPlay(ReadBook.book?.bookUrl.orEmpty())
            } else null)
            putString(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
        }
        postEvent(EventBus.READ_ALOUD_PLAY, bundle)
    }

    fun refreshReadAloudClass(): Class<*> {
        aloudClass = getReadAloudClass()
        return aloudClass
    }

    fun moveToCue(
        context: Context,
        cueIndex: Int,
        chapterPosition: Int,
        expectedChapterIndex: Int = ReadBook.durChapterIndex,
        play: Boolean = BaseReadAloudService.isPlay(),
        userInitiated: Boolean = false,
        prefetchContinuation: String? =
            AudioPrefetchPlayback.lifecycle.continuation(ReadBook.book?.bookUrl.orEmpty())
    ) {
        if (!BaseReadAloudService.isRun) return
        prepareNovelAudioIfRequested(
            play = play,
            userInitiated = userInitiated,
            prefetchRequest = null,
            bookUrl = ReadBook.book?.bookUrl.orEmpty(),
            chapterIndex = expectedChapterIndex
        )
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.moveTo
        intent.putExtra("cueIndex", cueIndex)
        intent.putExtra("chapterPosition", chapterPosition)
        intent.putExtra("expectedChapterIndex", expectedChapterIndex)
        intent.putExtra("play", play)
        val prefetchRequest = if (play && userInitiated) {
            AudioPrefetchPlayback.lifecycle.requestUserPlay(ReadBook.book?.bookUrl.orEmpty())
        } else null
        intent.putExtra(AudioPrefetchPlayback.REQUEST, prefetchRequest)
        intent.putExtra(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
        kotlin.runCatching {
            context.startForegroundServiceCompat(intent)
        }.onFailure {
            AudioPrefetchPlayback.lifecycle.cancelRequest(prefetchRequest)
            val msg = "定位朗读出错\n${it.localizedMessage}"
            AppLog.put(msg, it)
            context.toastOnUi(msg)
        }
    }

    fun playFromPosition(
        context: Context,
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        chapterPosition: Int,
        userInitiated: Boolean = false,
        prefetchContinuation: String? = AudioPrefetchPlayback.lifecycle.continuation(bookUrl)
    ) {
        prepareNovelAudioIfRequested(
            play = true,
            userInitiated = userInitiated,
            prefetchRequest = null,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex
        )
        val intent = Intent(context, aloudClass)
        intent.action = IntentAction.playFromPosition
        intent.putExtra("bookUrl", bookUrl)
        intent.putExtra("chapterIndex", chapterIndex)
        intent.putExtra("chapterUrl", chapterUrl)
        intent.putExtra("chapterPosition", chapterPosition)
        val prefetchRequest = if (userInitiated) {
            AudioPrefetchPlayback.lifecycle.requestUserPlay(bookUrl)
        } else null
        intent.putExtra(AudioPrefetchPlayback.REQUEST, prefetchRequest)
        intent.putExtra(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
        try {
            context.startForegroundServiceCompat(intent)
        } catch (e: Exception) {
            AudioPrefetchPlayback.lifecycle.cancelRequest(prefetchRequest)
            val msg = "启动选句朗读出错\n${e.localizedMessage}"
            AppLog.put(msg, e)
            context.toastOnUi(msg)
        }
    }

    fun prevChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prev
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextChapter(context: Context, continuePlayback: Boolean = BaseReadAloudService.isPlay()) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.next
            intent.putExtra("continuePlayback", continuePlayback)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun selectChapter(
        context: Context,
        chapterIndex: Int,
        continuePlayback: Boolean = BaseReadAloudService.isPlay(),
        prefetchContinuation: String? =
            AudioPrefetchPlayback.lifecycle.continuation(ReadBook.book?.bookUrl.orEmpty())
    ) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.selectChapter
            intent.putExtra("chapterIndex", chapterIndex)
            intent.putExtra("continuePlayback", continuePlayback)
            intent.putExtra(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
            context.startForegroundServiceCompat(intent)
        }
    }

    fun pause(context: Context) {
        AudioPrefetchPlayback.lifecycle.revoke()
        NovelAudioPreparationCoordinator.cancel()
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.pause
            context.startForegroundServiceCompat(intent)
        }
    }

    fun resume(
        context: Context,
        userInitiated: Boolean = false,
        prefetchRequest: String? = null,
        prefetchContinuation: String? =
            AudioPrefetchPlayback.lifecycle.continuation(ReadBook.book?.bookUrl.orEmpty())
    ) {
        if (BaseReadAloudService.isRun) {
            prepareNovelAudioIfRequested(
                play = true,
                userInitiated = userInitiated,
                prefetchRequest = prefetchRequest,
                bookUrl = ReadBook.book?.bookUrl.orEmpty(),
                chapterIndex = ReadBook.durChapterIndex
            )
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.resume
            val request = prefetchRequest ?: if (userInitiated) {
                AudioPrefetchPlayback.lifecycle.requestUserPlay(ReadBook.book?.bookUrl.orEmpty())
            } else null
            intent.putExtra(AudioPrefetchPlayback.REQUEST, request)
            intent.putExtra(AudioPrefetchPlayback.CONTINUATION, prefetchContinuation)
            kotlin.runCatching {
                context.startForegroundServiceCompat(intent)
            }.onFailure {
                AudioPrefetchPlayback.lifecycle.cancelRequest(request)
                val msg = "继续朗读出错\n${it.localizedMessage}"
                AppLog.put(msg, it)
                context.toastOnUi(msg)
            }
        } else {
            AudioPrefetchPlayback.lifecycle.cancelRequest(prefetchRequest)
        }
    }

    fun stop(context: Context) {
        AudioPrefetchPlayback.lifecycle.revoke()
        NovelAudioPreparationCoordinator.cancel()
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.stop
            context.startForegroundServiceCompat(intent)
        }
    }

    // 切换书籍时停止朗读（archive-ui P1-B）：与 stop 等价，供 ReadBook.stopReadAloudForBookSwitch 联动 UI 状态
    fun stopForBookSwitch(context: Context) {
        stop(context)
    }

    private fun prepareNovelAudioIfRequested(
        play: Boolean,
        userInitiated: Boolean,
        prefetchRequest: String?,
        bookUrl: String,
        chapterIndex: Int
    ) {
        if (!NovelAudioPreparationPolicy.shouldPrepare(
                currentRoute,
                play,
                userInitiated,
                prefetchRequest,
                AudioPrefetchPlayback.lifecycle.isPendingUserPlay(prefetchRequest, bookUrl)
            ) ||
            bookUrl.isBlank() ||
            chapterIndex < 0
        ) return

        NovelAudioPreparationCoordinator.request(bookUrl, chapterIndex)
        NovelAudioPreparationCoordinator.startCached(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            isCurrent = {
                ReadBook.book?.bookUrl == bookUrl &&
                    ReadBook.durChapterIndex == chapterIndex &&
                    ReadBook.contentLoadFinish
            }
        )
    }

    fun prevParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.prevParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun nextParagraph(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.nextParagraph
            context.startForegroundServiceCompat(intent)
        }
    }

    fun upTtsSpeechRate(context: Context) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.upTtsSpeechRate
            context.startForegroundServiceCompat(intent)
        }
    }

    fun setTimer(context: Context, minute: Int) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("minute", minute)
            context.startForegroundServiceCompat(intent)
        }
    }

    // 定时朗读模式入口: mode 1=读完本章 2=剩余 chapters 章 (R7.2)
    fun setTimerMode(context: Context, mode: Int, chapters: Int = 0) {
        if (BaseReadAloudService.isRun) {
            val intent = Intent(context, aloudClass)
            intent.action = IntentAction.setTimer
            intent.putExtra("mode", mode)
            intent.putExtra("minute", 0)
            intent.putExtra("chapters", chapters)
            context.startForegroundServiceCompat(intent)
        }
    }

}
