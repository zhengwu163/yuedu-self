package io.legado.app.data.entities

import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAudioPlanJsonTest {
    @Test
    fun missingRequiredIdentityRemainsInvalid() {
        assertNull(NovelAudioPlanJson.decode("{}"))
        assertNull(NovelAudioPlanJson.decode("null"))
        assertNull(NovelAudioPlanJson.decode("{\"planId\":\"plan:test\"}"))
    }

    @Test
    fun invalidChapterIndexIsRejectedAfterStorageDecoding() {
        assertNull(
            NovelAudioPlanJson.decode(
                "{\"planId\":\"plan:test\",\"workKey\":\"work\"," +
                    "\"physicalBookUrl\":\"content://book\",\"chapterIndex\":-1}"
            )
        )
    }

    @Test
    fun storedPlanRoundTripRetainsPlayableSegments() {
        val plan = NovelAudioChapterPlan(
            planId = "plan:test", workKey = "physical:work",
            physicalBookUrl = "content://book", chapterIndex = 0,
            chapterUrl = "chapter", serverScope = "http://127.0.0.1:8787",
            generation = 1L,
            segments = listOf(
                NovelAudioSegmentIntent.create(
                    orderedRanges = listOf(NovelAudioTextRange(0, 0, 10)),
                    text = "sample0000", speakerId = 0L,
                    voiceAssetId = "local.narrator", bindingRevision = 1L,
                    language = "zh-CN", speed = 1.0
                )
            )
        )

        // Exercise the existing Room JSON boundary before porting the decoder fix.
        val decoded = NovelAudioPlanJson.decode(NovelAudioPlanJson.encode(plan))

        assertNotNull(decoded)
        assertEquals(plan.planId, decoded!!.planId)
        assertEquals(1, decoded.playableSegments.size)
        assertEquals(plan.segments.single().segmentId, decoded.segments.single().segmentId)
    }
}
