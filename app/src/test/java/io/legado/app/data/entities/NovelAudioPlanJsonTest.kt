package io.legado.app.data.entities

import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NovelAudioPlanJsonTest {

    @Test
    fun `stored plan json decodes back into a playable plan`() {
        // 真机实测：播放端从 Room 读回计划时解码失败，整章已合成却报 INVALID_PLAN 无法播放。
        val plan = NovelAudioChapterPlan(
            planId = "plan:test",
            workKey = "physical:work",
            physicalBookUrl = "content://book",
            chapterIndex = 0,
            chapterUrl = "chapter",
            serverScope = "http://127.0.0.1:8788",
            generation = 1L,
            segments = listOf(
                NovelAudioSegmentIntent.create(
                    orderedRanges = listOf(NovelAudioTextRange(0, 0, 10)),
                    text = "　　第一章 灯塔来信",
                    speakerId = 0L,
                    voiceAssetId = "voicestudio.profile.narrator",
                    bindingRevision = 1L,
                    language = "zh-CN",
                    speed = 1.0
                )
            )
        )

        val decoded = NovelAudioPlanJson.decode(NovelAudioPlanJson.encode(plan))

        assertNotNull(decoded)
        assertEquals(plan.planId, decoded!!.planId)
        assertEquals(1, decoded.playableSegments.size)
        assertEquals(plan.segments.single().segmentId, decoded.segments.single().segmentId)
    }
}
