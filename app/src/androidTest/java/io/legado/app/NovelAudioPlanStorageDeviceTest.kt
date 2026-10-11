package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioTextRange
import io.legado.app.help.readaloud.offline.NovelAudioArtifactStore
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import io.legado.app.help.readaloud.offline.NovelAudioRetryPolicy
import io.legado.app.help.readaloud.server.NovelAudioServerException
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verify the real Room/Gson boundary in the minified debug APK without user data. */
@RunWith(AndroidJUnit4::class)
class NovelAudioPlanStorageDeviceTest {
    @Test fun readyReplacementLeavesOldTaskIntactButDoesNotRecoverIt() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java
        ).build()
        try {
            val dao = database.novelAudioDao
            dao.insertChapterPlan(io.legado.app.data.entities.NovelAudioChapterPlanEntity(
                planId = "old", physicalBookUrl = "book", chapterIndex = 0, createdAt = 1L,
                state = NovelAudioStates.PARTIAL, retention = NovelAudioRetention.PINNED))
            dao.insertDownloadTask(io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "old-task", planId = "old", physicalBookUrl = "book", chapterIndex = 0,
                state = NovelAudioStates.PARTIAL, retention = NovelAudioRetention.PINNED))
            dao.insertChapterPlan(io.legado.app.data.entities.NovelAudioChapterPlanEntity(
                planId = "remaining", physicalBookUrl = "book", chapterIndex = 1, createdAt = 1L,
                state = NovelAudioStates.PARTIAL, retention = NovelAudioRetention.PINNED))
            dao.insertDownloadTask(io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "remaining-task", planId = "remaining", physicalBookUrl = "book", chapterIndex = 1,
                state = NovelAudioStates.PARTIAL, retention = NovelAudioRetention.PINNED))
            assertEquals(2, dao.recoverablePinnedTasks().size)
            dao.insertChapterPlan(io.legado.app.data.entities.NovelAudioChapterPlanEntity(
                planId = "new", physicalBookUrl = "book", chapterIndex = 0, createdAt = 2L,
                state = NovelAudioStates.READY, retention = NovelAudioRetention.PINNED))
            assertEquals(listOf("remaining-task"), dao.recoverablePinnedTasks().map { it.taskId })
            assertEquals(NovelAudioStates.PARTIAL, dao.downloadTasksForPlan("old").single().state)
        } finally { database.close() }
    }
    @Test fun latestPinnedPlanWinsOverOldLargerSnapshotHash() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java
        ).build()
        try {
            val older = io.legado.app.data.entities.NovelAudioChapterPlanEntity(
                planId = "old", physicalBookUrl = "book", chapterIndex = 0,
                generation = 999L, retention = NovelAudioRetention.PINNED, createdAt = 1L
            )
            val newer = older.copy(
                planId = "new", workKey = older.workKey, physicalBookUrl = older.physicalBookUrl,
                chapterIndex = older.chapterIndex, chapterUrl = older.chapterUrl, scope = older.scope,
                generation = 1L, executionAttempt = older.executionAttempt, state = older.state,
                retention = older.retention, snapshotHash = older.snapshotHash, rulesVersion = older.rulesVersion,
                planJson = older.planJson, progress = older.progress, stateReason = older.stateReason,
                createdAt = 2L, updatedAt = older.updatedAt
            )
            database.novelAudioDao.insertChapterPlan(older)
            database.novelAudioDao.insertChapterPlan(newer)
            assertEquals("new", database.novelAudioDao.currentChapterPlan("book", 0)?.planId)
        } finally { database.close() }
    }
    @Test
    fun failedDownloadPersistsOnlyItsFixedReason() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val segment = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 6)), text = "sample",
                speakerId = 0L, voiceAssetId = "test.narrator", bindingRevision = 1L,
                language = "zh-CN", speed = 1.0
            )
            val plan = NovelAudioChapterPlan(
                planId = "failure-device-plan", workKey = "failure-device-work",
                physicalBookUrl = "content://test/failure", chapterIndex = 0,
                chapterUrl = "content://test/chapter", serverScope = "http://127.0.0.1:8787",
                generation = 1L, snapshotHash = "test-snapshot", rulesVersion = "test-rules",
                analysisVersion = "1", segments = listOf(segment), createdAt = 1L
            )
            val repository = NovelAudioRepository(database)
            val execution = checkNotNull(repository.savePlanForExecution(plan,
                NovelAudioRetention.PINNED, ""))
            val root = File(context.cacheDir, "failure-storage-test")
            val downloader = NovelAudioDownloadCoordinator(
                repository = repository, artifactStore = NovelAudioArtifactStore(root, { true }),
                filesRoot = root, synthesize = { throw NovelAudioServerException("INVALID_LEASE") },
                retryPolicy = NovelAudioRetryPolicy(1, {}), decoder = { true },
                synthesizeWithLease = null, leaseId = null
            )
            val result = downloader.downloadPlan(execution, { true })
            assertEquals("NovelAudioServerException:INVALID_LEASE",
                (result as NovelAudioDownloadCoordinator.Result.Failed).reason)
            assertEquals(result.reason, database.novelAudioDao.chapterPlan(plan.planId)?.stateReason)
            assertEquals(result.reason, database.novelAudioDao.downloadTasksForPlan(plan.planId)
                .single().stateReason)
        } finally {
            database.close()
        }
    }

    @Test
    fun readyPlanBackfillsBindingsWithoutReplacingManualVoice() {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        try {
            val segment = NovelAudioSegmentIntent.create(
                orderedRanges = listOf(NovelAudioTextRange(0, 0, 6)),
                text = "sample", speakerId = 19L, voiceAssetId = "test.speaker",
                bindingRevision = 1L, language = "zh-CN", speed = 1.0
            )
            val plan = NovelAudioChapterPlan(
                planId = "binding-device-plan", workKey = "binding-device-work",
                physicalBookUrl = "content://test/bindings", chapterIndex = 0,
                chapterUrl = "content://test/chapter", serverScope = "http://127.0.0.1:8787",
                generation = 1L, snapshotHash = "test-snapshot", rulesVersion = "test-rules",
                analysisVersion = "1", segments = listOf(segment), createdAt = 1L
            )
            val repository = NovelAudioRepository(database)
            assertNotNull(repository.savePlanForExecution(plan, NovelAudioRetention.PINNED, ""))
            val dao = database.novelAudioDao
            val entity = checkNotNull(dao.chapterPlan(plan.planId))
            assertEquals(1, dao.compareAndSetPlanState(plan.planId, plan.generation,
                entity.executionAttempt, NovelAudioStates.READY, 100, "", 2L))
            assertNotNull(repository.currentPlan(plan.physicalBookUrl, plan.chapterIndex))
            val remembered = checkNotNull(dao.voiceBinding(plan.serverScope, 19L))
            assertEquals("test.speaker", remembered.voiceAssetId)
            dao.upsertVoiceBinding(remembered.copy(voiceAssetId = "test.manual", revision = 2L))
            assertNotNull(repository.currentPlan(plan.physicalBookUrl, plan.chapterIndex))
            assertEquals("test.manual", dao.voiceBinding(plan.serverScope, 19L)?.voiceAssetId)
            assertEquals(1, dao.voiceBindings(plan.serverScope).size)
        } finally {
            database.close()
        }
    }

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
