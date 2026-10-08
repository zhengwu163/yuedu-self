package io.legado.app.help.readaloud.novel

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * 固定下载的界面状态持有者。
 *
 * 刻意不含取色与组件决策：界面只渲染这里给出的 [State]，
 * 因此下载范围、进度与结果的全部规则都能在纯 JVM 下验证。
 *
 * 可选项按真实章数裁剪：书末只剩两章时若仍提供「后续 10 章」，
 * 用户会以为排了十章而实际只有两章；无后续章时干脆不提供预设。
 */
internal class NovelAudioPinnedDownloadPresenter(
    private val prepare: suspend (chapterIndex: Int, retention: String) -> Boolean,
    private val openBatch: suspend (expectedChapterCount: Int) -> NovelAudioChapterBatch? = { null },
    private val onState: (State) -> Unit
) {

    /** 一个可选项及其真实章数，供界面直接显示。 */
    data class Option(
        val selection: NovelAudioPinnedRangePolicy.Selection,
        val chapters: Int
    )

    sealed interface State {
        data class Running(val total: Int, val finished: Int) : State
        data class Done(val succeeded: Int, val failed: Int) : State
        data class Cancelled(val succeeded: Int) : State
        data class Rejected(val reason: NovelAudioPinnedRangePolicy.Rejection) : State
    }

    private val cancelled = AtomicBoolean(false)

    fun options(currentChapterIndex: Int, chapterCount: Int): List<Option> {
        return PRESETS.mapNotNull { selection ->
            val resolved = NovelAudioPinnedRangePolicy.resolve(
                selection = selection,
                currentChapterIndex = currentChapterIndex,
                chapterCount = chapterCount
            )
            (resolved as? NovelAudioPinnedRangePolicy.Result.Resolved)
                ?.let { Option(selection, it.chapters.size) }
        }
    }

    suspend fun start(
        selection: NovelAudioPinnedRangePolicy.Selection,
        currentChapterIndex: Int,
        chapterCount: Int
    ) {
        // 每次开始都重置取消位，否则上一轮的取消会让这一轮立刻停摆。
        cancelled.set(false)
        val resolved = NovelAudioPinnedRangePolicy.resolve(
            selection = selection,
            currentChapterIndex = currentChapterIndex,
            chapterCount = chapterCount
        )
        if (resolved is NovelAudioPinnedRangePolicy.Result.Rejected) {
            onState(State.Rejected(resolved.reason))
            return
        }
        val total = (resolved as NovelAudioPinnedRangePolicy.Result.Resolved).chapters.size
        var finished = 0
        onState(State.Running(total, finished))
        val batch = openBatch(total)
        try {
            val downloader = NovelAudioPinnedDownloader(
                prepare = { chapterIndex, retention ->
                    // 取消异常必须原样穿过，不能被进度推进掩盖。
                    (batch?.prepare(chapterIndex, retention)
                        ?: prepare(chapterIndex, retention)).also {
                        finished++
                        onState(State.Running(total, finished))
                    }
                },
                isCancelled = { cancelled.get() }
            )
            when (
                val result = downloader.start(
                    selection = selection,
                    currentChapterIndex = currentChapterIndex,
                    chapterCount = chapterCount
                )
            ) {
                is NovelAudioPinnedDownloader.Result.Completed ->
                    onState(State.Done(result.succeeded, result.failed))

                is NovelAudioPinnedDownloader.Result.Cancelled ->
                    onState(State.Cancelled(result.succeeded))

                is NovelAudioPinnedDownloader.Result.Rejected ->
                    onState(State.Rejected(result.reason))
            }
        } finally {
            batch?.close()
        }
    }

    /** 用户显式取消；只影响当前这一轮。 */
    fun cancel() {
        cancelled.set(true)
    }

    private companion object {
        val PRESETS = listOf(
            NovelAudioPinnedRangePolicy.Selection.CurrentChapter,
            NovelAudioPinnedRangePolicy.Selection.NextTen,
            NovelAudioPinnedRangePolicy.Selection.NextTwenty
        )
    }
}
