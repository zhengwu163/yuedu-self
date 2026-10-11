package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter

/** UI and player share the exact ordered, frozen segments, including synthesis splits. */
object NovelAudioPlaybackPresentation {
    data class Cue(
        val index: Int, val key: String, val text: String, val speakerId: Long,
        val speakerName: String, val avatar: String, val chapterPosition: Int
    )

    fun cues(
        plan: NovelAudioChapterPlan,
        paragraphs: List<NovelAudioParagraphCoordinate>,
        characters: List<BookCharacter>
    ): List<Cue> = plan.playableSegments.mapIndexed { index, segment ->
        val character = characters.firstOrNull { it.id == segment.speakerId }
        Cue(index, segment.segmentId, segment.text, segment.speakerId,
            if (segment.speakerId == 0L) "旁白" else character?.name ?: "未识别角色",
            character?.avatar.orEmpty(),
            segment.orderedRanges.firstOrNull()?.let {
                NovelAudioPositionMapper.chapterPosition(it, paragraphs)
            } ?: 0)
    }
}
