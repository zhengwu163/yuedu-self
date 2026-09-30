package io.legado.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.DatabaseMigrations
import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioDownloadTaskEntity
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.offline.NovelAudioArtifactStore
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelAudioMigrationTest {

    private val databaseName = "novel-audio-migration-test"
    private val openDatabases = linkedMapOf<String, AppDatabase>()
    private val databaseNames = linkedSetOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @After
    fun cleanupNamedDatabases() {
        openDatabases.values.forEach { database ->
            runCatching { database.close() }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseNames.forEach { name ->
            context.deleteDatabase(name)
        }
        openDatabases.clear()
        databaseNames.clear()
    }

    @Test
    fun roomMigrationSerializationRuntimeIsPackaged() {
        // Shared core types must survive shrinking of the host APK, even if
        // only the separate test APK uses them. Resolve by name on the device.
        assertTrue(Class.forName("kotlinx.serialization.StringFormat").isInterface)
        val resolveFileName = Class.forName("androidx.room.BaseRoomConnectionManager")
            .getDeclaredMethod("resolveFileName\$room_runtime_release", String::class.java)
        assertFalse(java.lang.reflect.Modifier.isAbstract(resolveFileName.modifiers))
    }

    @Test
    fun migration110ToCurrentPreservesBooksProgressCharactersAndLegacyRoleCache() {
        val name = "$databaseName-legacy-data"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = """{"type":"systemTts","voice":"legacy-voice"}"""
        val segments = """[{"characterId":42,"text":"原创迁移测试"}]"""
        helper.createDatabase(name, 110).use { legacy ->
            // Insert against the historical schema, not today's entity defaults.
            legacy.execSQL(
                """INSERT INTO books (bookUrl, name, author, durChapterIndex, durChapterPos)
                    VALUES (?, ?, ?, ?, ?)""",
                arrayOf<Any>("legacy-book", "迁移测试书", "测试作者", 12, 345)
            )
            legacy.execSQL(
                """INSERT INTO book_characters (id, bookUrl, name, speechRouteJson)
                    VALUES (?, ?, ?, ?)""",
                arrayOf<Any>(42L, "legacy-book", "测试人物", route)
            )
            legacy.execSQL(
                """INSERT INTO ai_read_aloud_role_caches
                    (cacheKey, bookUrl, chapterIndex, contentHash, segmentsJson)
                    VALUES (?, ?, ?, ?, ?)""",
                arrayOf<Any>("legacy-cache", "legacy-book", 12, "legacy-hash", segments)
            )
        }
        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations)
            .build()
        try {
            // Opening through Room executes the production migration and validates its schema.
            val migrated = database.openHelper.writableDatabase
            migrated.query(
                "SELECT name, author, durChapterIndex, durChapterPos FROM books WHERE bookUrl = 'legacy-book'"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("迁移测试书", cursor.getString(0))
                assertEquals("测试作者", cursor.getString(1))
                assertEquals(12, cursor.getInt(2))
                assertEquals(345, cursor.getInt(3))
            }
            migrated.query(
                "SELECT bookUrl, name, speechRouteJson FROM book_characters WHERE id = 42"
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-book", cursor.getString(0))
                assertEquals("测试人物", cursor.getString(1))
                assertEquals(route, cursor.getString(2))
            }
            migrated.query(
                """SELECT bookUrl, chapterIndex, contentHash, segmentsJson
                    FROM ai_read_aloud_role_caches WHERE cacheKey = 'legacy-cache'"""
            ).use { cursor ->
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy-book", cursor.getString(0))
                assertEquals(12, cursor.getInt(1))
                assertEquals("legacy-hash", cursor.getString(2))
                assertEquals(segments, cursor.getString(3))
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migration110ToCurrentCreatesNovelAudioTablesWithoutDestructiveMigration() {
        databaseNames += databaseName
        helper.createDatabase(databaseName, 110).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseNames += databaseName
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(*DatabaseMigrations.migrations)
            .build()
        openDatabases[databaseName] = database

        val names = database.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'novel_audio_%'"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

        assertEquals(6, names.size)
        assertTrue(names.contains("novel_audio_aliases"))
        assertTrue(names.contains("novel_audio_voice_bindings"))
        assertTrue(names.contains("novel_audio_chapter_plans"))
        assertTrue(names.contains("novel_audio_segment_artifacts"))
        assertTrue(names.contains("novel_audio_download_tasks"))
        assertTrue(names.contains("novel_audio_merge_records"))

        val mergeColumns = database.openHelper.writableDatabase.query(
            "PRAGMA table_info(novel_audio_merge_records)"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue(mergeColumns.contains("primaryCharacterId"))
        assertTrue(mergeColumns.contains("absorbedCharacterId"))
        assertTrue(mergeColumns.contains("absorbedAliasesBeforeJson"))
        assertTrue(mergeColumns.contains("voiceBindingsBeforeJson"))
        assertTrue(mergeColumns.contains("revokedAt"))
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migration111To112PreservesBooksAndAddsNovelAudioReasons() {
        val name = "$databaseName-state-reasons"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        helper.createDatabase(name, 111).use { legacy ->
            legacy.execSQL(
                """
                INSERT INTO books (bookUrl, name, author, origin, originName)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>("book://old", "旧书", "", "", "")
            )
            legacy.execSQL(
                """
                INSERT INTO novel_audio_chapter_plans (
                    planId, workKey, physicalBookUrl, chapterIndex, chapterUrl, scope,
                    generation, state, retention, snapshotHash, rulesVersion, planJson,
                    progress, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "plan-legacy", "work", "book://old", 3, "chapter://3", "scope",
                    7L, NovelAudioStates.PARTIAL, NovelAudioRetention.PINNED,
                    "snapshot", "rules", "{}", 42, 11L, 12L
                )
            )
            legacy.execSQL(
                """
                INSERT INTO novel_audio_download_tasks (
                    taskId, planId, physicalBookUrl, chapterIndex, scope, generation,
                    state, retention, taskJson, progress, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "task-legacy", "plan-legacy", "book://old", 3, "scope", 7L,
                    NovelAudioStates.PARTIAL, NovelAudioRetention.PINNED, "{}", 42, 11L, 12L
                )
            )
        }

        databaseNames += name
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.migrations)
            .allowMainThreadQueries()
            .build()
        try {
            val migrated = database.openHelper.writableDatabase
            migrated.query("SELECT name FROM books WHERE bookUrl = 'book://old'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧书", cursor.getString(0))
            }
            migrated.query("PRAGMA table_info(novel_audio_chapter_plans)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                assertTrue(columns.contains("stateReason"))
            }
            migrated.query("PRAGMA table_info(novel_audio_download_tasks)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                assertTrue(columns.contains("stateReason"))
            }
            migrated.query(
                "SELECT planId, generation, state, retention, progress, stateReason " +
                    "FROM novel_audio_chapter_plans WHERE planId = 'plan-legacy'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("plan-legacy", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
                assertEquals(NovelAudioStates.PARTIAL, cursor.getString(2))
                assertEquals(NovelAudioRetention.PINNED, cursor.getString(3))
                assertEquals(42, cursor.getInt(4))
                assertEquals("", cursor.getString(5))
            }
            migrated.query(
                "SELECT taskId, generation, state, retention, progress, stateReason " +
                    "FROM novel_audio_download_tasks WHERE taskId = 'task-legacy'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("task-legacy", cursor.getString(0))
                assertEquals(7L, cursor.getLong(1))
                assertEquals(NovelAudioStates.PARTIAL, cursor.getString(2))
                assertEquals(NovelAudioRetention.PINNED, cursor.getString(3))
                assertEquals(42, cursor.getInt(4))
                assertEquals("", cursor.getString(5))
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun planQueryPrioritizesPinnedAndCompareAndSetChecksGeneration() {
        val name = "$databaseName-cas"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        dao.insertChapterPlan(
            NovelAudioChapterPlanEntity(
                planId = "plan-auto",
                workKey = "work",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 5L,
                retention = NovelAudioRetention.AUTO
            )
        )
        dao.insertChapterPlan(
            NovelAudioChapterPlanEntity(
                planId = "plan-pinned",
                workKey = "work",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 1L,
                retention = NovelAudioRetention.PINNED
            )
        )

        assertEquals("plan-pinned", dao.currentChapterPlan("book", 1)?.planId)
        assertEquals(
            1,
            dao.compareAndSetPlanState(
                planId = "plan-pinned",
                expectedGeneration = 1L,
                state = NovelAudioStates.RUNNING,
                progress = 10,
                reason = "TEST_RUNNING"
            )
        )
        assertEquals("TEST_RUNNING", dao.chapterPlan("plan-pinned")?.stateReason)
        val repository = NovelAudioRepository(database)
        assertTrue(
            repository.updatePlan(
                planId = "plan-pinned",
                generation = 1L,
                executionAttempt = 0L,
                state = NovelAudioStates.PARTIAL,
                progress = 101,
                reason = "TEST_CLAMP"
            )
        )
        assertEquals(100, dao.chapterPlan("plan-pinned")?.progress)
        assertEquals("TEST_CLAMP", dao.chapterPlan("plan-pinned")?.stateReason)
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = "task-pinned",
                planId = "plan-pinned",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 1L
            )
        )
        assertTrue(
            repository.updateTask(
                taskId = "task-pinned",
                generation = 1L,
                executionAttempt = 0L,
                state = NovelAudioStates.RUNNING,
                progress = -1,
                reason = "TEST_TASK"
            )
        )
        assertEquals(0, dao.downloadTasksForPlan("plan-pinned").single().progress)
        assertEquals("TEST_TASK", dao.downloadTasksForPlan("plan-pinned").single().stateReason)
        assertEquals(
            0,
            dao.compareAndSetPlanState(
                planId = "plan-pinned",
                expectedGeneration = 2L,
                state = NovelAudioStates.FAILED,
                progress = 0
            )
        )
        closeDatabase(database, name)
    }

    @Test
    fun migration112To113DefaultsBothAttemptsWithoutChangingTaskProgress() {
        val name = "$databaseName-execution-attempt-defaults"
        databaseNames += name
        helper.createDatabase(name, 112).use { legacy ->
            legacy.execSQL(
                """
                INSERT INTO novel_audio_chapter_plans (
                    planId, workKey, physicalBookUrl, chapterIndex, chapterUrl, scope,
                    generation, state, stateReason, retention, snapshotHash, rulesVersion,
                    planJson, progress, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "plan-attempt-legacy", "work", "book://attempt-legacy", 2, "chapter://2",
                    "scope", 7L, NovelAudioStates.PARTIAL, "NETWORK_REQUIRED",
                    NovelAudioRetention.PINNED, "snapshot", "rules", "{}", 42, 11L, 12L
                )
            )
            legacy.execSQL(
                """
                INSERT INTO novel_audio_download_tasks (
                    taskId, planId, physicalBookUrl, chapterIndex, scope, generation,
                    state, stateReason, retention, taskJson, progress, createdAt, updatedAt
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "task-attempt-legacy", "plan-attempt-legacy", "book://attempt-legacy", 2,
                    "scope", 7L, NovelAudioStates.PARTIAL, "NETWORK_REQUIRED",
                    NovelAudioRetention.PINNED, "{}", 42, 11L, 12L
                )
            )
        }
        val database = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            name
        ).addMigrations(*DatabaseMigrations.migrations)
            .allowMainThreadQueries()
            .build()
        openDatabases[name] = database
        val plan = checkNotNull(database.novelAudioDao.chapterPlan("plan-attempt-legacy"))
        val task = database.novelAudioDao.downloadTasksForPlan(plan.planId).single()
        assertEquals(0L, plan.executionAttempt)
        assertEquals(0L, task.executionAttempt)
        assertEquals(7L, plan.generation)
        assertEquals(7L, task.generation)
        assertEquals(42, plan.progress)
        assertEquals(42, task.progress)
        assertEquals("NETWORK_REQUIRED", plan.stateReason)
        assertEquals("NETWORK_REQUIRED", task.stateReason)
        assertEquals(NovelAudioRetention.PINNED, plan.retention)
        assertEquals(NovelAudioRetention.PINNED, task.retention)
        closeDatabase(database, name)
    }

    @Test
    fun concurrentClaimsOfTheSameExecutionHaveOnlyOneWinner() {
        val name = "$databaseName-concurrent-claim"
        val database = openMigratedDatabase(name)
        val repository = NovelAudioRepository(database)
        val plan = testPlan("plan-concurrent-claim", 17L)
        val execution = checkNotNull(repository.savePlanForExecution(plan))
        val workers = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val claims = (1..2).map {
                workers.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    repository.startExecution(execution)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, claims.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(NovelAudioStates.RUNNING, database.novelAudioDao.chapterPlan(plan.planId)?.state)
            val task = database.novelAudioDao.downloadTasksForPlan(plan.planId).single()
            assertEquals(NovelAudioStates.RUNNING, task.state)
            assertEquals(execution.executionAttempt, task.executionAttempt)
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS))
            closeDatabase(database, name)
        }
    }

    @Test
    fun pinnedRecoveryAndBookQueriesReturnOnlyRecoverableTasksInStableOrder() {
        val name = "$databaseName-recovery-queries"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        listOf(
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "pinned-running",
                planId = "plan-running",
                physicalBookUrl = "book",
                chapterIndex = 2,
                retention = NovelAudioRetention.PINNED,
                state = NovelAudioStates.RUNNING,
                createdAt = 1L
            ),
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "pinned-paused",
                planId = "plan-paused",
                physicalBookUrl = "book",
                chapterIndex = 1,
                retention = NovelAudioRetention.PINNED,
                state = NovelAudioStates.PAUSED,
                createdAt = 2L
            ),
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "pinned-newer",
                planId = "plan-newer",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 4L,
                retention = NovelAudioRetention.PINNED,
                state = NovelAudioStates.PARTIAL,
                createdAt = 3L
            ),
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "pinned-ready",
                planId = "plan-ready",
                physicalBookUrl = "book",
                chapterIndex = 0,
                retention = NovelAudioRetention.PINNED,
                state = NovelAudioStates.READY
            ),
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = "auto-queued",
                planId = "plan-auto",
                physicalBookUrl = "book",
                chapterIndex = 3,
                retention = NovelAudioRetention.AUTO,
                state = NovelAudioStates.QUEUED
            )
        ).forEach(dao::insertDownloadTask)

        assertEquals(
            listOf("pinned-running", "pinned-paused", "pinned-newer"),
            dao.recoverablePinnedTasks().map { it.taskId }
        )
        assertEquals(
            listOf("pinned-ready", "pinned-newer", "pinned-paused", "pinned-running", "auto-queued"),
            dao.tasksForBook("book").map { it.taskId }
        )
        closeDatabase(database, name)
    }

    @Test
    fun sameGenerationLateArtifactCannotReviveFailedPlan() {
        val name = "$databaseName-late-artifact"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        dao.insertChapterPlan(
            NovelAudioChapterPlanEntity(
                planId = "plan-failed",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 4L,
                state = NovelAudioStates.FAILED,
                stateReason = "ARTIFACT_INVALID"
            )
        )

        assertFalse(
            dao.saveArtifactAndUpdatePlan(
                artifact = NovelAudioSegmentArtifactEntity(
                    planId = "plan-failed",
                    segmentId = "segment",
                    ttsProfile = "profile",
                    contentType = "audio/ogg",
                    sha256 = "sha256",
                    size = 1L,
                    path = "segment.ogg",
                    state = NovelAudioStates.READY
                ),
                expectedGeneration = 4L,
                expectedSegmentIds = listOf("segment")
            )
        )
        assertEquals(NovelAudioStates.FAILED, dao.chapterPlan("plan-failed")?.state)
        assertEquals("ARTIFACT_INVALID", dao.chapterPlan("plan-failed")?.stateReason)
        assertTrue(dao.segmentArtifacts("plan-failed").isEmpty())
        closeDatabase(database, name)
    }

    @Test
    fun readyAndMissingSegmentQueriesOnlyUseExpectedSegmentIds() {
        val name = "$databaseName-segments"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        dao.upsertSegmentArtifact(
            NovelAudioSegmentArtifactEntity(
                planId = "plan",
                segmentId = "ready",
                ttsProfile = "profile",
                contentType = "audio/ogg",
                sha256 = "hash-ready",
                path = "/data/ready",
                state = NovelAudioStates.READY
            )
        )
        dao.upsertSegmentArtifact(
            NovelAudioSegmentArtifactEntity(
                planId = "plan",
                segmentId = "unready",
                state = NovelAudioStates.FAILED
            )
        )

        assertEquals(
            listOf("ready"),
            dao.readySegmentIds("plan", listOf("missing", "ready", "ready"))
        )
        assertEquals(
            listOf("missing", "unready"),
            dao.missingOrUnreadySegmentIds(
                "plan",
                listOf("missing", "ready", "unready", "ready")
            )
        )
        closeDatabase(database, name)
    }

    @Test
    fun savingANewPlanDoesNotOverwriteAnExistingGeneration() {
        val name = "$databaseName-repository"
        val database = openMigratedDatabase(name)
        val repository = NovelAudioRepository(database)
        val first = NovelAudioChapterPlan(
            planId = "plan-same",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 1,
            generation = 1L
        )
        val newer = first.copy(generation = 2L)

        assertTrue(
            repository.savePlanForExecution(
                first,
                retention = NovelAudioRetention.AUTO,
                taskJson = "first"
            ) != null
        )
        assertFalse(
            repository.savePlanForExecution(
                newer,
                retention = NovelAudioRetention.AUTO,
                taskJson = "newer"
            ) != null
        )
        assertEquals(
            1L,
            database.novelAudioDao.chapterPlan("plan-same")?.generation
        )
        assertEquals(1, database.novelAudioDao.downloadTasks("book").size)
        closeDatabase(database, name)
    }

    @Test
    fun invalidatingAReadyArtifactDemotesOnlyTheMatchingPlanGeneration() {
        val name = "$databaseName-invalidate"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        dao.insertChapterPlan(
            NovelAudioChapterPlanEntity(
                planId = "plan-invalidated",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 7L,
                state = NovelAudioStates.READY,
                retention = NovelAudioRetention.PINNED,
                progress = 100
            )
        )
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = "task-invalidated",
                planId = "plan-invalidated",
                physicalBookUrl = "book",
                chapterIndex = 1,
                generation = 7L,
                state = NovelAudioStates.READY,
                retention = NovelAudioRetention.PINNED,
                progress = 100
            )
        )
        dao.upsertSegmentArtifact(
            NovelAudioSegmentArtifactEntity(
                planId = "plan-invalidated",
                segmentId = "segment",
                state = NovelAudioStates.READY
            )
        )

        assertFalse(
            repository.invalidateReadyState(
                planId = "plan-invalidated",
                expectedGeneration = 8L,
                segmentId = "segment",
                expectedExecutionAttempt = 0L
            )
        )
        assertEquals(
            NovelAudioStates.READY,
            dao.chapterPlan("plan-invalidated")?.state
        )
        assertEquals(
            NovelAudioStates.READY,
            dao.segmentArtifacts("plan-invalidated").single().state
        )
        assertTrue(
            repository.invalidateReadyState(
                planId = "plan-invalidated",
                expectedGeneration = 7L,
                segmentId = "segment",
                expectedExecutionAttempt = 0L
            )
        )
        assertEquals(
            NovelAudioStates.PARTIAL,
            dao.chapterPlan("plan-invalidated")?.state
        )
        assertEquals(
            "ARTIFACT_INVALID",
            dao.chapterPlan("plan-invalidated")?.stateReason
        )
        assertEquals(
            NovelAudioStates.FAILED,
            dao.segmentArtifacts("plan-invalidated").single().state
        )
        assertEquals(
            NovelAudioStates.PARTIAL,
            dao.downloadTasksForPlan("plan-invalidated").single().state
        )
        assertEquals(
            "ARTIFACT_INVALID",
            dao.downloadTasksForPlan("plan-invalidated").single().stateReason
        )
        assertEquals(
            listOf("task-invalidated"),
            dao.recoverablePinnedTasks().map { it.taskId }
        )

        closeDatabase(database, name)
    }

    @Test
    fun savingAFailedPlanRequeuesTheSameGenerationForRepair() {
        val name = "$databaseName-requeue"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        val failedPlan = NovelAudioChapterPlanEntity(
            planId = "plan-requeue",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 1,
            generation = 7L,
            state = NovelAudioStates.FAILED,
            progress = 0
        )
        dao.insertChapterPlan(failedPlan)
        dao.insertDownloadTask(
            io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
                taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
                    .storageKey(failedPlan.planId, "download"),
                planId = failedPlan.planId,
                physicalBookUrl = failedPlan.physicalBookUrl,
                chapterIndex = failedPlan.chapterIndex,
                generation = failedPlan.generation,
                state = NovelAudioStates.FAILED
            )
        )

        assertTrue(
            repository.savePlanForExecution(
                NovelAudioChapterPlan(
                    planId = failedPlan.planId,
                    workKey = failedPlan.workKey,
                    physicalBookUrl = failedPlan.physicalBookUrl,
                    chapterIndex = failedPlan.chapterIndex,
                    generation = failedPlan.generation
                ),
                retention = NovelAudioRetention.AUTO,
                taskJson = ""
            ) != null
        )
        assertEquals(
            NovelAudioStates.PLANNED,
            dao.chapterPlan(failedPlan.planId)?.state
        )
        assertEquals(
            NovelAudioStates.QUEUED,
            dao.downloadTasksForPlan(failedPlan.planId).single().state
        )

        closeDatabase(database, name)
    }

    @Test
    fun retryingPartialAutoPlanAsPinnedPromotesTheExistingRetention() {
        val name = "$databaseName-auto-to-pinned"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        val plan = NovelAudioChapterPlanEntity(
            planId = "plan-auto-to-pinned",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 2,
            generation = 9L,
            state = NovelAudioStates.PARTIAL,
            retention = NovelAudioRetention.AUTO,
            progress = 40
        )
        val task = io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
            taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
                .storageKey(plan.planId, "download"),
            planId = plan.planId,
            physicalBookUrl = plan.physicalBookUrl,
            chapterIndex = plan.chapterIndex,
            generation = plan.generation,
            state = NovelAudioStates.PARTIAL,
            retention = NovelAudioRetention.AUTO,
            progress = 40
        )
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(task)

        assertTrue(
            repository.savePlanForExecution(
                NovelAudioChapterPlan(
                    planId = plan.planId,
                    workKey = plan.workKey,
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = plan.chapterIndex,
                    generation = plan.generation
                ),
                retention = NovelAudioRetention.PINNED,
                taskJson = ""
            ) != null
        )
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.chapterPlan(plan.planId)?.retention
        )
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.downloadTasksForPlan(plan.planId).single().retention
        )

        closeDatabase(database, name)
    }

    @Test
    fun retryingFailedPlanRepairsAnInterruptedRunningTask() {
        val name = "$databaseName-interrupted-task"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        val plan = NovelAudioChapterPlanEntity(
            planId = "plan-interrupted-task",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 3,
            generation = 10L,
            state = NovelAudioStates.FAILED,
            progress = 60
        )
        val task = io.legado.app.data.entities.NovelAudioDownloadTaskEntity(
            taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
                .storageKey(plan.planId, "download"),
            planId = plan.planId,
            physicalBookUrl = plan.physicalBookUrl,
            chapterIndex = plan.chapterIndex,
            generation = plan.generation,
            state = NovelAudioStates.RUNNING,
            progress = 60
        )
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(task)

        assertTrue(
            repository.savePlanForExecution(
                NovelAudioChapterPlan(
                    planId = plan.planId,
                    workKey = plan.workKey,
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = plan.chapterIndex,
                    generation = plan.generation
                ),
                retention = NovelAudioRetention.AUTO,
                taskJson = ""
            ) != null
        )
        assertEquals(
            NovelAudioStates.QUEUED,
            dao.downloadTasksForPlan(plan.planId).single().state
        )
        assertEquals(
            0,
            dao.downloadTasksForPlan(plan.planId).single().progress
        )

        closeDatabase(database, name)
    }

    @Test
    fun lateCallbackFromRequeuedAttemptCannotWriteTheSameGeneration() {
        val name = "$databaseName-requeued-late-callback"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        val plan = NovelAudioChapterPlanEntity(
            planId = "plan-requeued-late-callback",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 4,
            generation = 11L,
            state = NovelAudioStates.FAILED
        )
        val taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
            .storageKey(plan.planId, "download")
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = taskId,
                planId = plan.planId,
                physicalBookUrl = plan.physicalBookUrl,
                chapterIndex = plan.chapterIndex,
                generation = plan.generation,
                state = NovelAudioStates.RUNNING
            )
        )

        assertTrue(
            repository.savePlanForExecution(
                NovelAudioChapterPlan(
                    planId = plan.planId,
                    workKey = plan.workKey,
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = plan.chapterIndex,
                    generation = plan.generation
                ),
                retention = NovelAudioRetention.AUTO,
                taskJson = ""
            ) != null
        )
        assertEquals(
            NovelAudioStates.QUEUED,
            dao.downloadTasksForPlan(plan.planId).single().state
        )
        assertEquals(
            0,
            dao.compareAndSetDownloadTask(
                taskId = taskId,
                expectedGeneration = plan.generation,
                state = NovelAudioStates.RUNNING,
                progress = 60
            )
        )
        assertFalse(
            dao.saveArtifactAndUpdatePlan(
                artifact = NovelAudioSegmentArtifactEntity(
                    planId = plan.planId,
                    segmentId = "late-segment",
                    ttsProfile = "profile",
                    contentType = "audio/ogg",
                    sha256 = "sha256",
                    size = 1L,
                    path = "late.ogg",
                    state = NovelAudioStates.READY
                ),
                expectedGeneration = plan.generation,
                expectedSegmentIds = listOf("late-segment")
            )
        )
        assertEquals(NovelAudioStates.PLANNED, dao.chapterPlan(plan.planId)?.state)
        assertTrue(dao.segmentArtifacts(plan.planId).isEmpty())

        closeDatabase(database, name)
    }

    @Test
    fun finalArtifactDoesNotPromotePlanBeforeDownloadTaskFinishes() {
        val name = "$databaseName-final-artifact-state-window"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val planId = "plan-final-artifact-state-window"
        val taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
            .storageKey(planId, "download")
        val plan = NovelAudioChapterPlanEntity(
            planId = planId,
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 20,
            generation = 20L,
            state = NovelAudioStates.RUNNING,
            progress = 50
        )
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = taskId,
                planId = planId,
                physicalBookUrl = plan.physicalBookUrl,
                chapterIndex = plan.chapterIndex,
                generation = plan.generation,
                state = NovelAudioStates.RUNNING,
                progress = 50
            )
        )
        dao.upsertSegmentArtifact(
            NovelAudioSegmentArtifactEntity(
                planId = planId,
                segmentId = "first",
                ttsProfile = "profile",
                contentType = "audio/ogg",
                sha256 = "first",
                size = 1L,
                path = "first.ogg",
                state = NovelAudioStates.READY
            )
        )

        assertTrue(
            dao.saveArtifactAndUpdatePlan(
                artifact = NovelAudioSegmentArtifactEntity(
                    planId = planId,
                    segmentId = "second",
                    ttsProfile = "profile",
                    contentType = "audio/ogg",
                    sha256 = "second",
                    size = 1L,
                    path = "second.ogg",
                    state = NovelAudioStates.READY
                ),
                expectedGeneration = plan.generation,
                expectedExecutionAttempt = 0L,
                expectedSegmentIds = listOf("first", "second")
            )
        )

        assertEquals(NovelAudioStates.RUNNING, dao.chapterPlan(planId)?.state)
        assertEquals(
            NovelAudioStates.RUNNING,
            dao.downloadTasksForPlan(planId).single().state
        )
        assertEquals(
            listOf("first", "second"),
            dao.readySegmentIds(planId, listOf("first", "second"))
        )

        closeDatabase(database, name)
    }

    @Test
    fun pausedPlanAndTaskRejectLateWorkerStateUpdates() {
        val name = "$databaseName-paused-late-callback"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val plan = NovelAudioChapterPlanEntity(
            planId = "plan-paused-late-callback",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 5,
            generation = 12L,
            state = NovelAudioStates.PAUSED
        )
        val taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
            .storageKey(plan.planId, "download")
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = taskId,
                planId = plan.planId,
                physicalBookUrl = plan.physicalBookUrl,
                chapterIndex = plan.chapterIndex,
                generation = plan.generation,
                state = NovelAudioStates.PAUSED
            )
        )

        assertEquals(
            0,
            dao.compareAndSetPlanState(
                planId = plan.planId,
                expectedGeneration = plan.generation,
                state = NovelAudioStates.RUNNING,
                progress = 10
            )
        )
        assertEquals(
            0,
            dao.compareAndSetDownloadTask(
                taskId = taskId,
                expectedGeneration = plan.generation,
                state = NovelAudioStates.RUNNING,
                progress = 10
            )
        )
        assertEquals(NovelAudioStates.PAUSED, dao.chapterPlan(plan.planId)?.state)
        assertEquals(
            NovelAudioStates.PAUSED,
            dao.downloadTasksForPlan(plan.planId).single().state
        )

        closeDatabase(database, name)
    }

    @Test
    fun readyAutoPlanCanBePromotedToPinnedWithoutRegeneration() {
        val name = "$databaseName-ready-auto-to-pinned"
        val database = openMigratedDatabase(name)
        val dao = database.novelAudioDao
        val repository = NovelAudioRepository(database)
        val plan = NovelAudioChapterPlanEntity(
            planId = "plan-ready-auto-to-pinned",
            workKey = "work",
            physicalBookUrl = "book",
            chapterIndex = 6,
            generation = 13L,
            state = NovelAudioStates.READY,
            retention = NovelAudioRetention.AUTO,
            progress = 100
        )
        val task = NovelAudioDownloadTaskEntity(
            taskId = io.legado.app.help.readaloud.novel.NovelAudioIdentity
                .storageKey(plan.planId, "download"),
            planId = plan.planId,
            physicalBookUrl = plan.physicalBookUrl,
            chapterIndex = plan.chapterIndex,
            generation = plan.generation,
            state = NovelAudioStates.READY,
            retention = NovelAudioRetention.AUTO,
            progress = 100
        )
        dao.insertChapterPlan(plan)
        dao.insertDownloadTask(task)

        assertTrue(
            repository.savePlanForExecution(
                NovelAudioChapterPlan(
                    planId = plan.planId,
                    workKey = plan.workKey,
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = plan.chapterIndex,
                    generation = plan.generation
                ),
                retention = NovelAudioRetention.PINNED,
                taskJson = ""
            ) != null
        )
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.chapterPlan(plan.planId)?.retention
        )
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.downloadTasksForPlan(plan.planId).single().retention
        )
        assertEquals(NovelAudioStates.READY, dao.chapterPlan(plan.planId)?.state)
        assertEquals(
            NovelAudioStates.READY,
            dao.downloadTasksForPlan(plan.planId).single().state
        )

        closeDatabase(database, name)
    }

    @Test
    fun cancelledBeforeClaimRequeuesPlanAndTaskWithAFreshAttempt() = runBlocking {
        val name = "$databaseName-cancelled-execution"
        val database = openMigratedDatabase(name)
        val repository = NovelAudioRepository(database)
        val plan = testPlan("plan-cancelled-execution", 14L)
        val execution = checkNotNull(repository.savePlanForExecution(plan))
        val coordinator = NovelAudioDownloadCoordinator(
            repository = repository,
            artifactStore = NovelAudioArtifactStore(
                rootDirectory = InstrumentationRegistry.getInstrumentation()
                    .targetContext.cacheDir,
                decoder = { true }
            ),
            filesRoot = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            synthesize = { error("cancelled-before-claim must not synthesize") },
            decoder = { true }
        )

        assertEquals(
            NovelAudioDownloadCoordinator.Result.Cancelled,
            coordinator.downloadPlan(execution, isAutoAllowed = { false })
        )

        assertEquals(NovelAudioStates.PARTIAL, database.novelAudioDao.chapterPlan(plan.planId)?.state)
        assertEquals(1L, database.novelAudioDao.chapterPlan(plan.planId)?.executionAttempt)
        val task = database.novelAudioDao.downloadTasksForPlan(plan.planId).single()
        assertEquals(NovelAudioStates.PARTIAL, task.state)
        assertEquals(1L, task.executionAttempt)
        assertEquals(
            2L,
            checkNotNull(repository.savePlanForExecution(plan)).executionAttempt
        )
        closeDatabase(database, name)
    }

    @Test
    fun cancelledAfterClaimRequeuesPlanAndTaskWithAFreshAttempt() = runBlocking {
        val name = "$databaseName-cancelled-after-claim"
        val database = openMigratedDatabase(name)
        val repository = NovelAudioRepository(database)
        val plan = testPlan("plan-cancelled-after-claim", 16L)
        val execution = checkNotNull(repository.savePlanForExecution(plan))
        val coordinator = NovelAudioDownloadCoordinator(
            repository = repository,
            artifactStore = NovelAudioArtifactStore(
                rootDirectory = InstrumentationRegistry.getInstrumentation()
                    .targetContext.cacheDir,
                decoder = { true }
            ),
            filesRoot = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            synthesize = {
                throw kotlinx.coroutines.CancellationException("test cancellation")
            },
            decoder = { true }
        )

        val result = kotlin.runCatching {
            coordinator.downloadPlan(execution, isAutoAllowed = { true })
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(NovelAudioStates.PARTIAL, database.novelAudioDao.chapterPlan(plan.planId)?.state)
        assertEquals(1L, database.novelAudioDao.chapterPlan(plan.planId)?.executionAttempt)
        assertEquals(1L, database.novelAudioDao.downloadTasksForPlan(plan.planId).single().executionAttempt)
        closeDatabase(database, name)
    }

    @Test
    fun corruptReadyReuseIsInvalidatedForRepairInsteadOfRemainingReady() = runBlocking {
        assertCorruptReadyReuseIsRepairable(NovelAudioStates.READY)
    }

    @Test
    fun corruptReadyReuseWithUnfinishedTaskIsRepairable() = runBlocking {
        assertCorruptReadyReuseIsRepairable(NovelAudioStates.RUNNING)
    }

    private suspend fun assertCorruptReadyReuseIsRepairable(taskState: String) {
        val name = "$databaseName-corrupt-ready-reuse-$taskState"
        val database = openMigratedDatabase(name)
        val repository = NovelAudioRepository(database)
        val plan = testPlan("plan-corrupt-ready-reuse", 15L)
        val execution = repository.savePlanForExecution(plan)!!
        val root = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "novel-audio-corrupt-ready"
        ).also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        try {
            val file = File(root, "segment.ogg").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val artifact = NovelAudioSegmentArtifactEntity(
                planId = plan.planId,
                segmentId = plan.playableSegments.single().segmentId,
                ttsProfile = "profile",
                contentType = "audio/ogg",
                sha256 = sha256(byteArrayOf(9, 9, 9)),
                size = 3L,
                path = file.name,
                state = NovelAudioStates.READY
            )
            database.novelAudioDao.upsertSegmentArtifact(artifact)
            database.novelAudioDao.updateDownloadTask(
                database.novelAudioDao.downloadTasksForPlan(plan.planId).single()
                    .copy(state = taskState, progress = 100)
            )
            database.novelAudioDao.compareAndSetPlanState(
                planId = plan.planId,
                expectedGeneration = plan.generation,
                expectedExecutionAttempt = execution.executionAttempt,
                state = NovelAudioStates.RUNNING,
                progress = 100
            )
            database.novelAudioDao.compareAndSetPlanState(
                planId = plan.planId,
                expectedGeneration = plan.generation,
                expectedExecutionAttempt = execution.executionAttempt,
                state = NovelAudioStates.READY,
                progress = 100
            )
            val readyExecution = repository.savePlanForExecution(plan)!!
            val coordinator = NovelAudioDownloadCoordinator(
                repository = repository,
                artifactStore = NovelAudioArtifactStore(root, decoder = { true }),
                filesRoot = root,
                synthesize = { error("corrupt READY path must not synthesize") },
                decoder = { true }
            )

            assertTrue(readyExecution.alreadyReady)
            assertTrue(
                coordinator.downloadPlan(readyExecution, isAutoAllowed = { true }) is
                    NovelAudioDownloadCoordinator.Result.Failed
            )
            assertEquals(
                NovelAudioStates.PARTIAL,
                database.novelAudioDao.chapterPlan(plan.planId)?.state
            )
            assertEquals(
                NovelAudioStates.FAILED,
                database.novelAudioDao.segmentArtifacts(plan.planId).single().state
            )
            assertEquals(
                NovelAudioStates.PARTIAL,
                database.novelAudioDao.downloadTasksForPlan(plan.planId).single().state
            )
            val repaired = checkNotNull(repository.savePlanForExecution(plan))
            assertEquals(readyExecution.executionAttempt + 1, repaired.executionAttempt)
            assertEquals(
                repaired.executionAttempt,
                database.novelAudioDao.downloadTasksForPlan(plan.planId).single().executionAttempt
            )
        } finally {
            root.deleteRecursively()
            closeDatabase(database, name)
        }
    }

    private fun testPlan(planId: String, generation: Long): NovelAudioChapterPlan {
        return NovelAudioChapterPlan(
            planId = planId,
            workKey = "work-$planId",
            physicalBookUrl = "book://$planId",
            chapterIndex = generation.toInt(),
            chapterUrl = "chapter://$planId",
            serverScope = "scope",
            generation = generation,
            segments = listOf(
                NovelAudioSegmentIntent.create(
                    orderedRanges = emptyList(),
                    text = "测试音频",
                    speakerId = 0L,
                    voiceAssetId = "",
                    bindingRevision = 0L,
                    language = "zh-CN",
                    speed = 1.0
                )
            )
        )
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun openMigratedDatabase(name: String): AppDatabase {
        databaseNames += name
        helper.createDatabase(name, 110).close()
        return Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            name
        ).addMigrations(*DatabaseMigrations.migrations)
            .allowMainThreadQueries()
            .build()
            .also { openDatabases[name] = it }
    }

    private fun closeDatabase(database: AppDatabase, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database.close()
        context.deleteDatabase(name)
    }
}
