package io.legado.app.service

import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.NovelAudioPreparationState
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioParagraphCoordinate
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class NovelAudioReadAloudServiceTest {

    @Test
    fun readyEventPlanWinsOverStaleHigherGenerationPlanFromEarlierProcess() {
        // 真机实测：代际号随进程重启归 1，旧进程留下的 generation=4 失败计划被当作当前计划，
        // 播放判定未就绪后反复重新准备，直至耗尽本地分析额度。
        val ready = NovelAudioChapterPlanEntity(
            planId = "plan:ready", workKey = "work", physicalBookUrl = "book", chapterIndex = 0,
            generation = 1L, state = NovelAudioStates.READY
        )
        val stale = ready.copy(planId = "plan:stale", generation = 4L, state = NovelAudioStates.FAILED)
        val plans = listOf(ready, stale).associateBy { it.planId }

        assertEquals(
            "plan:ready",
            NovelAudioReadAloudService.selectPlaybackPlan(
                "plan:ready", "book", 0, plans::get
            ) { stale }?.planId
        )
        // 事件 planId 属于别的章节或为空时，仍按原有「本章最新计划」查询。
        assertEquals(
            "plan:stale",
            NovelAudioReadAloudService.selectPlaybackPlan("plan:ready", "book", 1, plans::get) { stale }?.planId
        )
        assertEquals(
            "plan:stale",
            NovelAudioReadAloudService.selectPlaybackPlan(null, "book", 0, plans::get) { stale }?.planId
        )
    }

    @Test
    fun preparationReadyEventRequiresCurrentChapterGenerationAndActivePlaybackWork() {
        val ready = NovelAudioPreparationState(
            bookUrl = "book://test",
            chapterIndex = 3,
            planId = "plan:test",
            generation = 7L,
            ready = true
        )

        assertTrue(
            NovelAudioReadAloudService.shouldResumeAfterPreparation(
                state = ready,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldResumeAfterPreparation(
                state = ready,
                currentBookUrl = "book://other",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldResumeAfterPreparation(
                state = ready,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 8L,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldResumeAfterPreparation(
                state = ready,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = false
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldResumeAfterPreparation(
                state = ready.copy(ready = false),
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = true
            )
        )
    }

    @Test
    fun preparationFailureEventRequiresCurrentChapterGenerationAndActivePlaybackWork() {
        val failed = NovelAudioPreparationState(
            bookUrl = "book://test",
            chapterIndex = 3,
            generation = 7L,
            ready = false,
            reason = "MISSING_CREDENTIALS"
        )

        assertTrue(
            NovelAudioReadAloudService.shouldHandlePreparationFailure(
                state = failed,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldHandlePreparationFailure(
                state = failed,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 8L,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioReadAloudService.shouldHandlePreparationFailure(
                state = failed,
                currentBookUrl = "book://test",
                currentChapterIndex = 3,
                currentGeneration = 7L,
                hasCurrentWork = false
            )
        )
    }

    @Test
    fun playbackMapsSegmentStartToFinalLayoutChapterPosition() {
        val segment = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(4, 3, 8)),
            text = "片段"
        )

        assertEquals(
            203,
            NovelAudioReadAloudService.chapterPositionForSegment(
                segment = segment,
                paragraphs = listOf(
                    NovelAudioParagraphCoordinate(
                        sourceIndex = 4,
                        chapterPosition = 200,
                        textLength = 12
                    )
                )
            )
        )
    }

    @Test
    fun playbackMapsInsertedParagraphByFinalLayoutOrdinal() {
        val segment = NovelAudioSegmentIntent.create(
            orderedRanges = listOf(NovelAudioTextRange(1, 2, 6)),
            text = "新增段落"
        )

        assertEquals(
            302,
            NovelAudioReadAloudService.chapterPositionForSegment(
                segment = segment,
                paragraphs = listOf(
                    NovelAudioParagraphCoordinate(
                        sourceIndex = 7,
                        chapterPosition = 100,
                        textLength = 8
                    ),
                    NovelAudioParagraphCoordinate(
                        sourceIndex = -1,
                        chapterPosition = 300,
                        textLength = 8
                    )
                )
            )
        )
    }

    @Test
    fun playbackStartsAtSegmentForCurrentReaderPosition() {
        val segments = listOf(
            NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 4)),
                text = "第一段"
            ),
            NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 25, 30)),
                text = "第二段"
            ),
            NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 60, 66)),
                text = "第三段"
            )
        )

        assertEquals(
            1,
            NovelAudioReadAloudService.segmentIndexForChapterPosition(
                chapterPosition = 130,
                segments = segments,
                paragraphs = listOf(
                    NovelAudioParagraphCoordinate(
                        sourceIndex = 0,
                        chapterPosition = 100,
                        textLength = 80
                    )
                )
            )
        )
    }

    @Test
    fun preparationUsesFrozenSegmentOrderAndOnlyReadyVerifiedArtifacts() {
        val root = Files.createTempDirectory("novel-audio-playback").toFile()
        try {
            val first = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
                text = "第一段"
            )
            val second = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 2, 4)),
                text = "第二段"
            )
            val plan = testPlan(listOf(first, second))
            val secondFile = writeArtifact(root, plan.planId, second.segmentId, "second")
            val firstFile = writeArtifact(root, plan.planId, first.segmentId, "first")
            val artifacts = listOf(
                artifact(plan.planId, second.segmentId, secondFile),
                artifact(plan.planId, first.segmentId, firstFile)
            )

            val result = NovelAudioReadAloudService.preparePlayback(
                plan = plan,
                artifacts = artifacts,
                filesRoot = root,
                decoder = { true }
            )

            assertTrue(result is NovelAudioReadAloudService.PlaybackPreparation.Ready)
            assertEquals(
                listOf(firstFile.canonicalPath, secondFile.canonicalPath),
                (result as NovelAudioReadAloudService.PlaybackPreparation.Ready)
                    .files.map(File::getCanonicalPath)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparationRejectsMissingOrInvalidArtifactWithoutSilentFallback() {
        val root = Files.createTempDirectory("novel-audio-playback-missing").toFile()
        try {
            val segment = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
                text = "缺失音频"
            )
            val plan = testPlan(listOf(segment))
            val result = NovelAudioReadAloudService.preparePlayback(
                plan = plan,
                artifacts = emptyList(),
                filesRoot = root,
                decoder = { true }
            )

            assertTrue(result is NovelAudioReadAloudService.PlaybackPreparation.Blocked)
            assertEquals(
                NovelAudioReadAloudService.BlockReason.MISSING_ARTIFACT,
                (result as NovelAudioReadAloudService.PlaybackPreparation.Blocked).reason
            )
            assertEquals(
                segment.segmentId,
                (result as NovelAudioReadAloudService.PlaybackPreparation.Blocked).segmentId
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparationRejectsCorruptedBytesAndDecoderFailure() {
        val root = Files.createTempDirectory("novel-audio-playback-corrupt").toFile()
        try {
            val segment = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
                text = "损坏音频"
            )
            val plan = testPlan(listOf(segment))
            val file = writeArtifact(root, plan.planId, segment.segmentId, "actual")
            val corruptedHash = sha256("expected")
            val result = NovelAudioReadAloudService.preparePlayback(
                plan = plan,
                artifacts = listOf(
                    artifact(plan.planId, segment.segmentId, file, sha256 = corruptedHash)
                ),
                filesRoot = root,
                decoder = { false }
            )

            assertTrue(result is NovelAudioReadAloudService.PlaybackPreparation.Blocked)
            assertEquals(
                NovelAudioReadAloudService.BlockReason.CHECKSUM_MISMATCH,
                (result as NovelAudioReadAloudService.PlaybackPreparation.Blocked).reason
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun preparationSkipsBlankSegmentsWithoutRequiringPlaceholderAudio() {
        val root = Files.createTempDirectory("novel-audio-playback-blank").toFile()
        try {
            val blank = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 0)),
                text = ""
            )
            val playable = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 2)),
                text = "可播放段"
            )
            val plan = testPlan(listOf(blank, playable))
            val playableFile = writeArtifact(root, plan.planId, playable.segmentId, "playable")

            val result = NovelAudioReadAloudService.preparePlayback(
                plan = plan,
                artifacts = listOf(artifact(plan.planId, playable.segmentId, playableFile)),
                filesRoot = root,
                decoder = { true }
            )

            assertTrue(result is NovelAudioReadAloudService.PlaybackPreparation.Ready)
            assertEquals(
                listOf(playableFile.canonicalPath),
                (result as NovelAudioReadAloudService.PlaybackPreparation.Ready)
                    .files.map(File::getCanonicalPath)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testPlan(segments: List<NovelAudioSegmentIntent>) = NovelAudioChapterPlan(
        planId = "plan:test",
        workKey = "work:test",
        physicalBookUrl = "book://test",
        chapterIndex = 0,
        chapterUrl = "chapter://test",
        serverScope = "https://tts.example",
        generation = 1,
        snapshotHash = "snapshot",
        rulesVersion = "rules",
        segments = segments
    )

    private fun writeArtifact(
        root: File,
        planId: String,
        segmentId: String,
        content: String
    ): File {
        // Logical ids contain ':'; like the real artifact store, keep them out of paths.
        val file = File(root, "${sha256(planId)}/${sha256(segmentId)}.ogg")
        file.parentFile!!.mkdirs()
        file.writeText(content)
        return file
    }

    private fun artifact(
        planId: String,
        segmentId: String,
        file: File,
        sha256: String = sha256(file.readText())
    ) = NovelAudioSegmentArtifactEntity(
        planId = planId,
        segmentId = segmentId,
        ttsProfile = "profile:test",
        contentType = "audio/ogg",
        sha256 = sha256,
        size = file.length(),
        path = file.relativeTo(file.parentFile!!.parentFile!!).path,
        state = NovelAudioStates.READY
    )

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
