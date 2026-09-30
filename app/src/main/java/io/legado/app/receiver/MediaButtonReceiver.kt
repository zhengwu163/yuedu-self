package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.model.AudioPlay
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.AudioPlayService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.audio.AudioPlayActivity
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent


/**
 * Created by GKF on 2018/1/6.
 * 监听耳机键
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (handleIntent(context, intent) && isOrderedBroadcast) {
            abortBroadcast()
        }
    }

    companion object {

        private const val TAG = "MediaButtonReceiver"

        fun handleIntent(context: Context, intent: Intent): Boolean {
            val intentAction = intent.action
            if (Intent.ACTION_MEDIA_BUTTON == intentAction) {
                @Suppress("DEPRECATION")
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                val keycode: Int = keyEvent.keyCode
                val action: Int = keyEvent.action
                if (action == KeyEvent.ACTION_DOWN) {
                    LogUtils.d(TAG, "Receive mediaButton event, keycode:$keycode")
                    when (keycode) {
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            // pause/stop 桥同步撤销待加载的朗读，未启动服务时也生效。
                            ReadAloud.pause(context)
                            AudioPlay.pause(context)
                        }

                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            ReadAloud.stop(context)
                            AudioPlay.stop()
                        }

                        KeyEvent.KEYCODE_MEDIA_PLAY -> readAloud(context, toggle = false)

                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> readAloud(context)

                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (context.getPrefBoolean("mediaButtonPerNext", false)) {
                                ReadBook.moveToPrevChapter(true)
                            } else {
                                ReadAloud.prevParagraph(context)
                            }
                        }

                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (context.getPrefBoolean("mediaButtonPerNext", false)) {
                                ReadBook.moveToNextChapter(true)
                            } else {
                                ReadAloud.nextParagraph(context)
                            }
                        }

                        else -> Unit
                    }
                }
            }
            return true
        }

        fun readAloud(context: Context, isMediaKey: Boolean = true, toggle: Boolean = true) {
            when {
                BaseReadAloudService.isRun -> {
                    if (BaseReadAloudService.isPlay()) {
                        if (!toggle) return
                        ReadAloud.pause(context)
                        AudioPlay.pause(context)
                    } else {
                        ReadAloud.resume(context, userInitiated = true)
                        AudioPlay.resume(context)
                    }
                }

                AudioPlayService.isRun -> {
                    if (AudioPlayService.pause) {
                        AudioPlay.resume(context)
                    } else if (toggle) {
                        AudioPlay.pause(context)
                    }
                }

                isMediaKey && !AppConfig.readAloudByMediaButton -> {
                    // break
                }

                LifecycleHelp.isExistActivity(ReadBookActivity::class.java) ->
                    postEvent(EventBus.MEDIA_BUTTON, true)

                LifecycleHelp.isExistActivity(AudioPlayActivity::class.java) ->
                    postEvent(EventBus.MEDIA_BUTTON, true)

                else -> if (AppConfig.mediaButtonOnExit || LifecycleHelp.activitySize() > 0 || !isMediaKey) {
                    ReadAloud.upReadAloudClass()
                    if (ReadBook.book != null) {
                        ReadBook.readAloud(userInitiated = true)
                    } else {
                        // 先保存按键意图；查书/加载期间的停止或新播放会使它失效。
                        val lifecycle = AudioPrefetchPlayback.lifecycle
                        val gesture = lifecycle.beginDeferredPlay()
                        var prefetchRequest: String? = null
                        Coroutine.async {
                            appDb.bookDao.lastReadBook
                        }.onSuccess { book ->
                            if (book == null || ReadBook.book != null) {
                                lifecycle.cancelRequest(gesture)
                                return@onSuccess
                            }
                            val request = lifecycle.bindDeferredPlay(gesture, book.bookUrl)
                                ?: return@onSuccess
                            prefetchRequest = request
                            ReadBook.resetData(book)
                            ReadBook.clearTextChapter()
                            ReadBook.loadContent(false) {
                                ReadBook.readAloud(
                                    prefetchRequest = request,
                                    prefetchContinuation = null
                                )
                            }
                        }.onError {
                            lifecycle.cancelRequest(gesture)
                            lifecycle.cancelRequest(prefetchRequest)
                            AppLog.put("媒体键加载朗读书籍失败", it)
                        }.onCancel {
                            lifecycle.cancelRequest(gesture)
                            lifecycle.cancelRequest(prefetchRequest)
                        }
                    }
                }
            }
        }
    }

}
