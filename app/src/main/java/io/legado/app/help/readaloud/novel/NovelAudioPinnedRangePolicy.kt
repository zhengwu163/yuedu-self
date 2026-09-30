package io.legado.app.help.readaloud.novel

/**
 * PINNED 手动下载的范围解析，一期只开放四种选择。
 *
 * 不做 IO、不创建任务，只把用户选择翻译成一段确定的章节序号。
 * 超出上限时明确拒绝而不是静默截断：截断会让用户以为已经排队的章节其实没排队。
 * 预设选择会被书末自然收窄；自定义区间必须完整落在书内，否则拒绝。
 */
object NovelAudioPinnedRangePolicy {

    /** 一期固定的单次手动下载章数上限，防止扩大成整本书。 */
    const val MAX_CHAPTERS = 20

    sealed interface Selection {
        data object CurrentChapter : Selection
        data object NextTen : Selection
        data object NextTwenty : Selection

        /** 闭区间，零基章节序号。 */
        data class Custom(val fromIndex: Int, val toIndex: Int) : Selection
    }

    enum class Rejection {
        /** 章节序号越界，或书籍章节数非法。 */
        OUT_OF_BOUNDS,

        /** 区间为空，例如已在书末或起止颠倒。 */
        EMPTY_RANGE,

        /** 超过一期允许的单次章数上限。 */
        TOO_MANY_CHAPTERS
    }

    sealed interface Result {
        data class Resolved(val chapters: List<Int>) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    fun resolve(
        selection: Selection,
        currentChapterIndex: Int,
        chapterCount: Int
    ): Result {
        if (chapterCount <= 0) return Result.Rejected(Rejection.OUT_OF_BOUNDS)
        val lastIndex = chapterCount - 1
        return when (selection) {
            Selection.CurrentChapter -> {
                if (currentChapterIndex !in 0..lastIndex) {
                    Result.Rejected(Rejection.OUT_OF_BOUNDS)
                } else {
                    Result.Resolved(listOf(currentChapterIndex))
                }
            }

            Selection.NextTen -> following(currentChapterIndex, lastIndex, 10)
            Selection.NextTwenty -> following(currentChapterIndex, lastIndex, 20)

            is Selection.Custom -> {
                if (selection.fromIndex < 0 || selection.toIndex > lastIndex) {
                    Result.Rejected(Rejection.OUT_OF_BOUNDS)
                } else if (selection.fromIndex > selection.toIndex) {
                    Result.Rejected(Rejection.EMPTY_RANGE)
                } else {
                    // 自定义区间是用户明确输入，超限必须让用户知道，不能悄悄少下几章。
                    val chapters = (selection.fromIndex..selection.toIndex).toList()
                    if (chapters.size > MAX_CHAPTERS) {
                        Result.Rejected(Rejection.TOO_MANY_CHAPTERS)
                    } else {
                        Result.Resolved(chapters)
                    }
                }
            }
        }
    }

    // 预设选择从当前章之后开始，并被书末自然收窄；书末无后续章即空区间。
    private fun following(currentChapterIndex: Int, lastIndex: Int, count: Int): Result {
        if (currentChapterIndex !in 0..lastIndex) {
            return Result.Rejected(Rejection.OUT_OF_BOUNDS)
        }
        val first = currentChapterIndex + 1
        val last = minOf(currentChapterIndex.toLong() + count, lastIndex.toLong()).toInt()
        if (first > last) return Result.Rejected(Rejection.EMPTY_RANGE)
        return Result.Resolved((first..last).toList())
    }
}
