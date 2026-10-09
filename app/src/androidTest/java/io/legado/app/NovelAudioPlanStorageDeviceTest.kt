package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verify the real Room/Gson boundary in the minified debug APK without user data. */
@RunWith(AndroidJUnit4::class)
class NovelAudioPlanStorageDeviceTest {
    @Test
    fun persistedPlanRetainsTypedPlayableSegments() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        try {
            // Explicit arguments avoid depending on R8-pruned Kotlin default bridges.
            val segment = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 6)),
                text = "sample", speakerId = 0L, voiceAssetId = "test.narrator",
                bindingRevision = 1L, language = "zh-CN", speed = 1.0
            )
            val plan = NovelAudioChapterPlan(
                planId = "storage-device-plan", workKey = "storage-device-work",
                physicalBookUrl = "content://test/book", chapterIndex = 0,
                chapterUrl = "content://test/chapter", serverScope = "http://127.0.0.1:8787",
                generation = 1L, snapshotHash = "test-snapshot", rulesVersion = "test-rules",
                analysisVersion = "1", segments = listOf(segment), createdAt = 1L
            )
            assertNotNull(
                NovelAudioRepository(database).savePlanForExecution(
                    plan, NovelAudioRetention.PINNED, ""
                )
            )
            val entity = checkNotNull(database.novelAudioDao.chapterPlan(plan.planId))
            val decoded = checkNotNull(NovelAudioPlanJson.decode(entity.planJson))
            assertEquals(plan.planId, decoded.planId)
            assertEquals(segment.segmentId, decoded.playableSegments.single().segmentId)
        } finally {
            database.close()
        }
    }
}
