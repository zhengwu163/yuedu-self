package io.legado.app.help.readaloud.novel

/**
 * 固定下载入口的面向用户文案。
 *
 * 与界面分离，因此文案规则可在纯 JVM 下验证：章数必须显示真实值而非选项名义值，
 * 拒绝原因必须给出用户能据此行动的说明，不把内部枚举名抛给用户。
 * 章号对用户按 1 起编号，与阅读界面一致；内部仍是零基序号。
 */
internal object NovelAudioPinnedDownloadLabels {

    fun option(option: NovelAudioPinnedDownloadPresenter.Option): String {
        val name = when (val selection = option.selection) {
            NovelAudioPinnedRangePolicy.Selection.CurrentChapter -> "当前章节"
            NovelAudioPinnedRangePolicy.Selection.NextTen -> "后续 10 章"
            NovelAudioPinnedRangePolicy.Selection.NextTwenty -> "后续 20 章"
            is NovelAudioPinnedRangePolicy.Selection.Custom ->
                "第 ${selection.fromIndex + 1} 至 ${selection.toIndex + 1} 章"
        }
        // 预设可能被书末收窄，名义章数与真实章数不一致时必须以真实值为准。
        return "$name（${option.chapters} 章）"
    }

    fun state(state: NovelAudioPinnedDownloadPresenter.State): String = when (state) {
        is NovelAudioPinnedDownloadPresenter.State.Running ->
            "正在下载 ${state.finished}/${state.total} 章"

        is NovelAudioPinnedDownloadPresenter.State.Done ->
            if (state.failed > 0) {
                "已下载 ${state.succeeded} 章，${state.failed} 章失败"
            } else {
                "已下载 ${state.succeeded} 章"
            }

        is NovelAudioPinnedDownloadPresenter.State.Cancelled ->
            "已取消，已下载 ${state.succeeded} 章"

        is NovelAudioPinnedDownloadPresenter.State.Rejected -> when (state.reason) {
            NovelAudioPinnedRangePolicy.Rejection.OUT_OF_BOUNDS ->
                "选择的章节超出本书范围"

            NovelAudioPinnedRangePolicy.Rejection.EMPTY_RANGE ->
                "没有可下载的后续章节"

            NovelAudioPinnedRangePolicy.Rejection.TOO_MANY_CHAPTERS ->
                "选择的章节范围过大，请缩小范围后重试"
        }
    }
}
