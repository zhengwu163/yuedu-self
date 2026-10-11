package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAudioPlaybackPresentationTest {
    @Test fun synthesisSplitsKeepExactAudioOrderAndSpeakerLabels() {
        val plan = NovelAudioChapterPlan("plan", "work", "book", segments = listOf(
            NovelAudioSegmentIntent.create(listOf(NovelAudioTextRange(0, 0, 2)), "旁白"),
            NovelAudioSegmentIntent.create(listOf(NovelAudioTextRange(0, 2, 4)), "前半", 12, "fixed"),
            NovelAudioSegmentIntent.create(listOf(NovelAudioTextRange(0, 4, 6)), "后半", 12, "fixed")
        ))
        val cues = NovelAudioPlaybackPresentation.cues(plan,
            listOf(NovelAudioParagraphCoordinate(0, 100, 6)),
            listOf(BookCharacter(id = 12, name = "陈远")))
        assertEquals(listOf(0, 1, 2), cues.map { it.index })
        assertEquals(listOf("旁白", "陈远", "陈远"), cues.map { it.speakerName })
        assertEquals(listOf(100, 102, 104), cues.map { it.chapterPosition })
        assertEquals(plan.playableSegments.map { it.segmentId }, cues.map { it.key })
    }
}
