package io.legado.app.help.readaloud.novel

/**
 * Maps frozen snapshot paragraph coordinates to positions from the completed
 * reader layout. The two coordinate systems are intentionally kept separate.
 */
data class NovelAudioParagraphCoordinate(
    val sourceIndex: Int,
    val chapterPosition: Int,
    val textLength: Int
)

object NovelAudioPositionMapper {

    fun chapterPosition(
        range: NovelAudioTextRange,
        paragraphs: List<NovelAudioParagraphCoordinate>
    ): Int? {
        // Frozen ranges use the final BookContent paragraph ordinal. The
        // source index remains a compatibility fallback for older fixtures
        // and layouts that do not expose the corresponding ordinal.
        val paragraph = paragraphs.getOrNull(range.paragraphIndex)
            ?: paragraphs.firstOrNull { it.sourceIndex == range.paragraphIndex }
            ?: return null
        val offset = range.start.coerceIn(0, paragraph.textLength)
        return paragraph.chapterPosition + offset
    }

    fun segmentIndexAtOrBefore(
        chapterPosition: Int,
        segmentPositions: List<Int?>
    ): Int {
        return segmentPositions
            .mapIndexedNotNull { index, position ->
                position?.takeIf { it <= chapterPosition }?.let { index to it }
            }
            .lastOrNull()
            ?.first
            ?: segmentPositions.indexOfFirst { it != null }
    }
}
