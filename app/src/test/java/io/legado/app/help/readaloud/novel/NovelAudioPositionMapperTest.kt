package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAudioPositionMapperTest {

    @Test
    fun `maps snapshot paragraph offset through final layout coordinate`() {
        val position = NovelAudioPositionMapper.chapterPosition(
            range = NovelAudioTextRange(paragraphIndex = 3, start = 4, end = 8),
            paragraphs = listOf(
                NovelAudioParagraphCoordinate(
                    sourceIndex = 3,
                    chapterPosition = 120,
                    textLength = 10
                )
            )
        )

        assertEquals(124, position)
    }

    @Test
    fun `does not guess a chapter position when paragraph mapping is absent`() {
        assertNull(
            NovelAudioPositionMapper.chapterPosition(
                range = NovelAudioTextRange(paragraphIndex = 9, start = 0, end = 1),
                paragraphs = emptyList()
            )
        )
    }

    @Test
    fun `finds the last segment at or before requested position`() {
        assertEquals(
            1,
            NovelAudioPositionMapper.segmentIndexAtOrBefore(
                chapterPosition = 130,
                segmentPositions = listOf(100, 125, 160)
            )
        )
    }
}
