package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudRoleRange
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import org.junit.Test

class NovelAudioPlanTest {

    @Test
    fun `plan identity isolates servers and frozen playback settings`() {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("旁白"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val original = NovelAudioPlanFactory.create(snapshot, "server-a", 3L)
        val same = NovelAudioPlanFactory.create(snapshot, "server-a", 3L)
        val otherServer = NovelAudioPlanFactory.create(snapshot, "server-b", 3L)
        val otherSpeed = NovelAudioPlanFactory.create(snapshot, "server-a", 3L, speed = 1.2)
        val voice = NovelAudioVoiceBinding("server-a", 0L, "narrator-a", 1L)
        val bound = NovelAudioPlanFactory.create(
            snapshot, "server-a", 3L, voiceBindings = mapOf(0L to voice)
        )
        val rebound = NovelAudioPlanFactory.create(
            snapshot, "server-a", 3L,
            voiceBindings = mapOf(0L to voice.copy(voiceAssetId = "narrator-b", revision = 2L))
        )

        assertEquals(original.planId, same.planId)
        assertNotEquals(original.planId, otherServer.planId)
        assertNotEquals(original.planId, otherSpeed.planId)
        assertNotEquals(bound.planId, rebound.planId)
    }

    @Test
    fun `plan factory does not use mutable character name as speaker identity`() {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("他说：你好"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val unit = ParsedTextUnit(
            unitId = "stable-unit-id",
            kind = "dialogue",
            roleType = "dialogue",
            characterName = "角色名",
            ranges = listOf(ReadAloudRoleRange(0, 0, 5)),
            text = "他说：你好",
            needsAi = true,
            confidence = 1.0,
            reason = "test"
        )

        assertThrows(IllegalArgumentException::class.java) {
            NovelAudioPlanFactory.create(
                snapshot = snapshot,
                serverScope = "scope",
                generation = 1L,
                parsedUnits = listOf(unit),
                speakerIds = mapOf("角色名" to 9L)
            )
        }
    }

    @Test
    fun `server segment limit splits long units into replayable segments`() {
        val paragraph = "甲乙丙丁戊己庚辛壬癸。".repeat(12)
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf(paragraph),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val whole = NovelAudioPlanFactory.create(snapshot, "server-a", 1L)
        val split = NovelAudioPlanFactory.create(snapshot, "server-a", 1L, maxSegmentChars = 50)

        assertEquals(1, whole.segments.size)
        // 每句 10 个发音字，50 字上限恰好 5 句一段。
        assertEquals(3, split.segments.size)
        assertNotEquals(whole.planId, split.planId)
        assertEquals(paragraph, split.segments.joinToString("") { it.text })
        split.segments.forEach { segment ->
            assertTrue(segment.text.count { Character.isLetterOrDigit(it) } <= 50)
            val replayed = segment.orderedRanges.joinToString("\n") {
                snapshot.textSnapshot.paragraphs[it.paragraphIndex].text.substring(it.start, it.end)
            }
            assertEquals(segment.text, replayed)
        }
    }

    @Test
    fun `segment id hashes text ranges and playback settings`() {
        val base = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
            text = "你好",
            speakerId = 12L,
            voiceAssetId = "voice-a",
            bindingRevision = 1,
            language = "zh-CN",
            speed = 1.0
        )
        val changedText = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
            text = "再见",
            speakerId = 12L,
            voiceAssetId = "voice-a",
            bindingRevision = 1,
            language = "zh-CN",
            speed = 1.0
        )
        val changedSettings = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
            text = "你好",
            speakerId = 12L,
            voiceAssetId = "voice-a",
            bindingRevision = 1,
            language = "zh-CN",
            speed = 1.1
        )

        assertNotEquals(base.segmentId, changedText.segmentId)
        assertNotEquals(base.segmentId, changedSettings.segmentId)
        assertFalse(base.toString().contains("你好"))
        assertTrue(base.toString().contains("textLength=2"))
    }

    @Test
    fun `chapter plan is immutable and diagnostic output omits segment text`() {
        val segment = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
            text = "正文",
            speakerId = 0L
        )
        val plan = NovelAudioChapterPlan(
            planId = "plan-1",
            workKey = "work:author/title",
            physicalBookUrl = "book://one",
            chapterIndex = 0,
            snapshotHash = "hash",
            rulesVersion = "rules",
            segments = listOf(segment)
        )

        assertFalse(plan.toString().contains("正文"))
        assertTrue(plan.segments.isNotEmpty())
    }

    @Test
    fun `playable segments exclude blank text without creating placeholder audio`() {
        val blank = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 0)),
            text = ""
        )
        val playable = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
            text = "正文"
        )
        val plan = NovelAudioChapterPlan(
            planId = "plan-blank",
            workKey = "work:blank",
            physicalBookUrl = "book://blank",
            chapterIndex = 0,
            segments = listOf(blank, playable)
        )

        assertEquals(listOf(playable.segmentId), plan.playableSegments.map { it.segmentId })
    }
}
