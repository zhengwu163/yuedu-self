package io.legado.app.help.readaloud.offline

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Android 入口的结构性接线回归；与 LifecycleTest 的真实状态机行为测试配对。
 * 检查具体方法的参数传递、默认值和异步边界顺序，不以全文件出现某个名称视为接线成功。
 * 不代替设备上的 Service/MediaSession 测试。
 */
class AudioPrefetchWiringTest {
    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }
    private fun source(path: String) = File(root, "io/legado/app/$path").readText()
    private fun method(path: String, name: String): String {
        val text = source(path)
        val start = text.indexOf("fun $name(")
        assertTrue("找不到 $path::$name", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 1
        var end = open + 1
        while (depth > 0 && end < text.length) {
            when (text[end++]) { '{' -> depth++; '}' -> depth-- }
        }
        return text.substring(start, end)
    }

    @Test fun `public playback bridges default to internal and forward explicit request`() {
        listOf("play", "playByEventBus", "resume", "playFromPosition", "moveToCue").forEach {
            val body = method("model/ReadAloud.kt", it)
            assertTrue("$it 默认不可授权", body.contains("userInitiated: Boolean = false"))
            assertTrue("$it 必须传递请求", body.contains("prefetchRequest"))
        }
        val readBook = method("model/ReadBook.kt", "readAloud")
        assertTrue(readBook.contains("userInitiated: Boolean = false"))
        assertTrue(readBook.contains("userInitiated = userInitiated"))
        assertTrue(readBook.contains("prefetchRequest = prefetchRequest"))
    }

    @Test fun `cancel bridges revoke before checking service running`() {
        listOf("pause", "stop").forEach {
            val body = method("model/ReadAloud.kt", it)
            val revoke = body.indexOf("AudioPrefetchPlayback.lifecycle.revoke()")
            assertTrue("$it 必须同步撤销待投递请求", revoke >= 0)
            assertTrue(revoke < body.indexOf("if (BaseReadAloudService.isRun)"))
        }
        assertTrue(source("model/ReadBook.kt").contains("bookChanged(field?.bookUrl, value?.bookUrl)"))
    }

    @Test fun `assembly captures before IO and publishes only after assembly`() {
        val body = method("service/BaseReadAloudService.kt", "newReadAloud")
        assertTrue(body.contains("prefetchWork: AudioPrefetchLifecycle.Work?"))
        assertTrue(body.indexOf("prefetchWork:") < body.indexOf("execute(executeContext = IO)"))
        assertTrue(body.indexOf("prefetchAssembled(") > body.indexOf("paragraphStartPos = pos"))
        assertFalse(body.substringAfter("execute(executeContext = IO)").contains("lifecycle.capture("))
        val destroy = method("service/BaseReadAloudService.kt", "onDestroy")
        assertTrue(destroy.contains("lifecycle.destroy(prefetchService)"))
        assertFalse(destroy.contains("lifecycle.revoke()"))
    }

    @Test fun `user button captures ticket before async chapter navigation`() {
        val body = method("ui/book/read/ReadBookActivity.kt", "onClickReadAloud")
        assertTrue(body.indexOf("requestUserPlay(") in 0 until body.indexOf("ReadBook.openChapter("))
        assertTrue(body.contains("prefetchRequest = prefetchRequest"))
        assertFalse(body.contains("userInitiated = true"))
        val select = method("ui/book/read/ReadBookActivity.kt", "handleSelectedTextReadAloud")
        assertTrue(select.contains("userInitiated = true"))
    }

    @Test fun `media play is explicit but focus and phone resume stay internal`() {
        val media = method("service/BaseReadAloudService.kt", "initMediaSession")
        assertTrue(media.contains("resumeFromUser()"))
        val focus = method("service/BaseReadAloudService.kt", "onAudioFocusChange")
        assertTrue(focus.contains("resumePlayback(null)"))
        assertFalse(focus.contains("resumeFromUser"))
        val phone = method("service/BaseReadAloudService.kt", "onCallStateChanged")
        assertFalse(phone.contains("resumeFromUser"))
        assertTrue(method("receiver/MediaButtonReceiver.kt", "readAloud")
            .contains("userInitiated = true"))
    }

    @Test fun `panel seek is explicit and engine rebuild stays internal`() {
        val seek = method("ui/book/read/ReadAloudPlayerPanel.kt", "seekToChapterPosition")
        assertEquals(2, Regex("userInitiated = true").findAll(seek).count())
        val rate = method("ui/book/read/ReadAloudPlayerPanel.kt", "setSpeechRate")
        assertFalse(rate.contains("userInitiated = true"))
        val state = method("ui/book/read/ReadAloudPlayerPanel.kt", "onAloudState")
        assertFalse(state.contains("userInitiated = true"))
    }

    @Test fun `internal delivery resolves captured continuation without borrowing current token`() {
        val body = method("service/BaseReadAloudService.kt", "prefetchWork")
        assertTrue(body.contains("continuation: String?"))
        assertTrue(body.contains("resolveContinuation("))
        assertFalse(body.contains("request != null &&"))
        val assembly = method("service/BaseReadAloudService.kt", "newReadAloud")
        assertFalse(assembly.substringBefore(") {").contains("lifecycle.capture("))
        val bridge = method("model/ReadAloud.kt", "play")
        assertTrue(bridge.contains("AudioPrefetchPlayback.CONTINUATION"))
    }

    @Test fun `failed service start cancels only its own pending request`() {
        listOf("play", "resume", "moveToCue", "playFromPosition").forEach {
            val body = method("model/ReadAloud.kt", it)
            assertTrue("$it 应在启动失败时清理自己的请求", body.contains("cancelRequest("))
        }
    }

    @Test fun `cold media play captures intent before loading and never grants in callback`() {
        val body = method("receiver/MediaButtonReceiver.kt", "readAloud")
        val cold = body.substringAfter("if (ReadBook.book != null)")
        assertTrue(cold.indexOf("beginDeferredPlay(") in 0 until cold.indexOf("Coroutine.async"))
        assertTrue(cold.contains("bindDeferredPlay("))
        assertTrue(cold.substringAfter("ReadBook.loadContent(false)").contains("prefetchRequest ="))
        assertFalse(cold.substringAfter("ReadBook.loadContent(false)").contains("userInitiated = true"))
    }

    @Test fun `chapter advancement forwards playback origin and captured continuation`() {
        listOf("nextChapter", "prevChapter", "prevP").forEach { name ->
            val body = method("service/BaseReadAloudService.kt", name)
            assertTrue("$name 应保留朗读起因", body.contains("fromReadAloud = true"))
            assertTrue("$name 应传递装配时身份", body.contains("prefetchContinuation ="))
        }
        listOf("moveToNextChapter", "moveToNextChapterAwait", "moveToPrevChapter").forEach { name ->
            val body = method("model/ReadBook.kt", name)
            assertTrue("$name 应携带原身份", body.contains("prefetchContinuation: String? = null"))
            assertTrue("$name 应绑定对应加载", body.contains("readAloudRequest ="))
        }
    }

    @Test fun `novel audio playback stores its captured work for chapter continuation`() {
        assertTrue(
            source("service/BaseReadAloudService.kt")
                .contains("protected fun prefetchAssembled(")
        )
        assertTrue(
            source("service/NovelAudioReadAloudService.kt")
                .contains("prefetchAssembled(")
        )
    }

    @Test fun `novel audio download coordinator selects only missing segments`() {
        assertTrue(
            source("help/readaloud/novel/NovelAudioRepository.kt")
                .contains("missingOrUnreadySegmentIds(")
        )
        assertTrue(
            source("help/readaloud/offline/NovelAudioDownloadCoordinator.kt")
                .contains("missingOrUnreadySegmentIds(")
        )
    }

    @Test fun `chapter completion uses exact load identity instead of chapter number alone`() {
        val text = source("model/ReadBook.kt")
        assertFalse(text.contains("readAloudPendingLoadChapterIndex"))
        listOf("contentLoadFinish", "contentLoadFinishAwait").forEach { name ->
            val body = method("model/ReadBook.kt", name)
            assertTrue("$name 应匹配加载代次", body.contains("completeChapterReadAloud("))
        }
        assertTrue(method("model/ReadBook.kt", "clearTextChapter").contains("chapterRequests.clear()"))
    }

    @Test fun `media pause and stop do not enter the cold play path`() {
        val body = method("receiver/MediaButtonReceiver.kt", "handleIntent")
        listOf("PAUSE" to "pause", "STOP" to "stop").forEach { (key, operation) ->
            val branch = Regex("""KeyEvent\.KEYCODE_MEDIA_$key\s*->\s*\{([^}]+)}""")
                .find(body)?.groupValues?.get(1)
            assertNotNull("$key 必须有独立控制分支", branch)
            assertTrue(branch!!.contains("ReadAloud.$operation(context)"))
            assertTrue(branch.contains("AudioPlay.$operation("))
            assertFalse(branch.contains("isRun"))
            assertFalse(branch.contains("readAloud("))
        }
    }

    @Test fun `media play is idempotent and headset key remains a toggle`() {
        val body = method("receiver/MediaButtonReceiver.kt", "handleIntent")
        assertTrue(Regex("""KEYCODE_MEDIA_PLAY\s*->\s*readAloud\(context,\s*toggle = false\)""")
            .containsMatchIn(body))
        assertTrue(body.contains("KeyEvent.KEYCODE_HEADSETHOOK"))
        val playback = method("receiver/MediaButtonReceiver.kt", "readAloud")
        assertTrue(playback.contains("toggle: Boolean = true"))
        assertTrue(Regex("""if \(!toggle\) return""").containsMatchIn(playback))
        assertTrue(playback.contains("else if (toggle)"))
    }

    @Test fun `media user resume signs authorization only for the current service`() {
        val body = method("service/BaseReadAloudService.kt", "resumeFromUser")
        assertTrue(Regex("""requestUserPlay\(\s*prefetchService""").containsMatchIn(body))
        assertTrue(body.substringBefore("resumePlayback(").contains("?: return"))
        assertTrue(source("help/readaloud/offline/AudioPrefetchLifecycle.kt")
            .contains("fun requestUserPlay(service: Service, bookUrl: String)"))
    }

    @Test fun `reader view media resume is an explicit user action`() {
        assertTrue(
            source("ui/book/read/page/ReadView.kt")
                .contains("ReadAloud.resume(context, userInitiated = true)")
        )
    }

    @Test fun `assembly invalidates prepared chapter before starting asynchronous work`() {
        val body = method("service/BaseReadAloudService.kt", "newReadAloud")
        val begin = body.indexOf("assemblyState.begin()")
        assertTrue(begin in 0 until body.indexOf("execute(executeContext = IO)"))
        val complete = body.indexOf("assemblyState.complete(")
        assertTrue(complete > body.indexOf("launch(Main)"))
        assertTrue(complete in 0 until body.indexOf("this@BaseReadAloudService.textChapter ="))
        assertTrue(complete < body.indexOf("if (play) play()"))
        val background = body.substringAfter("execute(executeContext = IO)").substringBefore("launch(Main)")
        assertFalse(background.contains("this@BaseReadAloudService."))
        assertFalse(background.contains("preheatAiRoleCache("))
        val pause = method("service/BaseReadAloudService.kt", "pauseReadAloud")
        assertTrue(pause.contains("assemblyState.cancelPending()"))
        val resume = method("service/BaseReadAloudService.kt", "resumePlayback")
        assertTrue(resume.contains("assemblyState.prepared"))
        assertTrue(resume.contains("newReadAloudAtChapterPosition(ReadBook.durChapterPos, work)"))
        val position = method("service/BaseReadAloudService.kt", "newReadAloudAtChapterPosition")
        assertTrue(position.contains("newReadAloud(true, pageIndex,"))
        assertTrue(position.contains(".coerceAtLeast(0), prefetchWork)"))
        assertTrue(position.contains("prefetchContinuation = prefetchWork?.continuationId"))
    }

    @Test fun `rejected notification offer cannot resume playback`() {
        val body = method("service/BaseReadAloudService.kt", "onStartCommand")
            .substringAfter("IntentAction.resume ->")
            .substringBefore("IntentAction.upTtsSpeechRate")
        assertTrue(body.contains("offer != null && work == null"))
        assertTrue(body.indexOf("offer != null && work == null") < body.indexOf("resumePlayback(work)"))
    }

    @Test fun `resume notification carries a unique identity in PendingIntent`() {
        val body = method("service/BaseReadAloudService.kt", "prefetchResumePendingIntent")
        assertTrue(body.contains("setData("))
        assertTrue(body.contains("offer"))
        assertTrue(body.contains("prefetchResumeIntent?.cancel()"))
        assertTrue(method("service/BaseReadAloudService.kt", "onDestroy")
            .contains("prefetchResumeIntent?.cancel()"))
    }

    @Test fun `ready chapter is played even when it is the last chapter`() {
        val ready = method("model/ReadBook.kt", "curPageChanged")
        assertFalse("目标章已就绪，不应再次判断它后面是否还有章节",
            ready.contains("nextChapterDecision("))
        assertFalse(ready.contains("hasNextSpeechChapter"))
        assertTrue(ready.contains("else"))
        assertTrue(ready.contains("else {\n"))
        assertTrue(ready.contains("prefetchContinuation = prefetchContinuation"))
        val end = method("service/BaseReadAloudService.kt", "nextChapter")
        assertTrue(end.contains("if (!ReadBook.moveToNextChapter("))
        assertTrue(end.contains("stopSelf()"))
    }

    @Test fun `notification construction is serialized with service state`() {
        listOf("upReadAloudNotification", "startForegroundNotification").forEach {
            assertTrue("$it 的通知票据签发与发布必须在同一主线程任务",
                method("service/BaseReadAloudService.kt", it).contains("execute(context = Main)"))
        }
    }

    @Test fun `rejected playback identity is dropped before dispatching action`() {
        val dispatch = method("service/BaseReadAloudService.kt", "dispatchPlayback")
        assertTrue(dispatch.contains("request != null || continuation != null"))
        assertTrue(dispatch.contains("work == null"))
        assertTrue(dispatch.substringBefore("action(work)").contains("return"))
        val start = method("service/BaseReadAloudService.kt", "onStartCommand")
        assertEquals("play/resume/moveTo/selectChapter/playFromPosition 共五条投递路径",
            5, Regex("""dispatchPlayback\(""").findAll(start).count())
        assertTrue(method("service/BaseReadAloudService.kt", "observeLiveBus")
            .contains("dispatchPlayback("))
    }

    @Test fun `stale work is rejected before replacing a newer assembly`() {
        val body = method("service/BaseReadAloudService.kt", "newReadAloud")
        val guard = body.indexOf("!AudioPrefetchPlayback.lifecycle.isCurrent(prefetchWork)")
        assertTrue(guard in 0 until body.indexOf("assemblyState.begin()"))
    }

    @Test fun `stop cancels pending assembly before service destruction`() {
        val start = method("service/BaseReadAloudService.kt", "onStartCommand")
        val stop = start.substringAfter("IntentAction.stop ->").substringBefore("IntentAction.reInitTts")
        assertTrue(stop.contains("assemblyState.clear()"))
        val media = method("service/BaseReadAloudService.kt", "onStop")
        assertTrue(media.contains("assemblyState.clear()"))
    }

    @Test fun `paused assembly still publishes local result after prefetch authorization is revoked`() {
        val body = method("service/BaseReadAloudService.kt", "newReadAloud")
        assertTrue(body.contains("if (!play) AudioPrefetchPlayback.lifecycle.pause(prefetchService)"))
        val commitGuard = body.substringAfter("launch(Main)")
            .substringBefore("assemblyState.complete(")
        assertTrue(
            "暂停态只撤销预缓存授权，不应丢弃本地装配结果",
            commitGuard.contains("(prefetchWork != null && play &&")
        )
        assertTrue(body.contains("if (play) prefetchAssembled(prefetchWork, textChapter)"))
    }

    @Test fun `position playback records chapter position and resume rebuilds from it`() {
        val playFromPosition = method("service/BaseReadAloudService.kt", "playFromPositionExt")
        assertTrue(playFromPosition.contains("ReadBook.durChapterPos = chapterPosition.coerceAtLeast(0)"))

        val resume = method("service/BaseReadAloudService.kt", "resumePlayback")
        assertTrue(resume.contains("newReadAloudAtChapterPosition(ReadBook.durChapterPos, work)"))
        assertFalse(
            "恢复不能固定从当前页首重新开始",
            resume.contains("newReadAloud(true, ReadBook.durPageIndex, 0")
        )
    }

    @Test fun `selected position only updates matching chapter under reader lock`() {
        val body = method("service/BaseReadAloudService.kt", "playFromPositionExt")
        val localUpdate = body.substringAfter("} else {")
        assertFalse("跨章前不能把新章位置写到旧章",
            body.substringBefore("} else {").contains("ReadBook.durChapterPos ="))
        assertTrue(Regex("""synchronized\(ReadBook\)\s*\{\s*ReadBook\.durChapterPos = chapterPosition\.coerceAtLeast\(0\)""")
            .containsMatchIn(localUpdate))
        assertTrue(body.contains("ReadBook.openChapter(chapterIndex, durChapterPos = chapterPosition)"))
    }

    @Test fun `same chapter position enters existing read progress persistence`() {
        val body = method("service/BaseReadAloudService.kt", "playFromPositionExt")
        val sameChapterBranch = body.substringAfter("} else {")
        assertTrue(
            "同章选句更新内存位置后必须进入现有阅读进度保存流程",
            sameChapterBranch.contains("ReadBook.saveRead(true)")
        )
    }
}
