package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.NovelAudioDownloadTaskEntity
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioIdentity
import io.legado.app.help.readaloud.novel.NovelAudioPinnedRecovery
import io.legado.app.help.readaloud.novel.NovelAudioPreparationLifecycle
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.novel.toEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class NovelAudioRecoveryDeviceTest {
    // A private database avoids changing the installed app's library or downloads.
    private val database = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,
        AppDatabase::class.java
    ).build()
    private val dao = database.novelAudioDao
    private val repository = NovelAudioRepository(database)
    private val lifecycleExecutors = Collections.synchronizedList(
        mutableListOf<ExecutorService>()
    )
    private val plan = NovelAudioChapterPlan(
        planId = "recovery-plan",
        workKey = "recovery-work",
        physicalBookUrl = "recovery-book",
        chapterIndex = 1,
        generation = 7L
    )
    private val taskId = NovelAudioIdentity.storageKey(plan.planId, "download")

    @After
    fun closeDatabase() {
        lifecycleExecutors.forEach { executor ->
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
        database.close()
    }

    @Test
    fun stalePlayerFailureDoesNotModifyPlanArtifactOrDownloadTask() {
        seedReadyPlan()
        val beforePlan = dao.chapterPlan(plan.planId)
        val beforeArtifacts = dao.segmentArtifacts(plan.planId)
        val beforeTasks = dao.downloadTasksForPlan(plan.planId)

        assertFalse(
            repository.invalidateReadyState(
                planId = plan.planId,
                expectedGeneration = 6L,
                segmentId = "damaged",
                expectedExecutionAttempt = 0L
            )
        )
        assertEquals(beforePlan, dao.chapterPlan(plan.planId))
        assertEquals(beforeArtifacts, dao.segmentArtifacts(plan.planId))
        assertEquals(beforeTasks, dao.downloadTasksForPlan(plan.planId))
    }

    @Test
    fun damagedCompletedDownloadCanBeRequeuedWithoutLosingPinnedRetention() {
        seedReadyPlan()

        assertTrue(
            repository.invalidateReadyState(
                planId = plan.planId,
                expectedGeneration = plan.generation,
                segmentId = "damaged",
                expectedExecutionAttempt = 0L
            )
        )
        assertEquals(NovelAudioStates.PARTIAL, dao.chapterPlan(plan.planId)?.state)
        assertEquals("ARTIFACT_INVALID", dao.chapterPlan(plan.planId)?.stateReason)
        assertEquals(NovelAudioStates.PARTIAL, dao.downloadTasksForPlan(plan.planId).single().state)
        assertEquals("ARTIFACT_INVALID", dao.downloadTasksForPlan(plan.planId).single().stateReason)
        assertEquals(listOf("intact"), dao.readySegmentIds(plan.planId, listOf("damaged", "intact")))
        assertTrue(
            repository.savePlanForExecution(
                plan,
                retention = NovelAudioRetention.AUTO,
                taskJson = ""
            ) != null
        )
        assertEquals(NovelAudioStates.PLANNED, dao.chapterPlan(plan.planId)?.state)
        val task = dao.downloadTasksForPlan(plan.planId).single()
        assertEquals(NovelAudioStates.QUEUED, task.state)
        assertEquals(NovelAudioRetention.PINNED, task.retention)
        assertEquals(NovelAudioRetention.PINNED, dao.chapterPlan(plan.planId)?.retention)
        assertFalse(
            repository.savePlanForExecution(
                plan,
                retention = NovelAudioRetention.AUTO,
                taskJson = ""
            ) != null
        ) // One user retry creates only one queued task.
    }

    @Test
    fun staleArtifactCommitCannotReplaceNewerReadyAudio() {
        seedReadyPlan()
        val beforeArtifacts = dao.segmentArtifacts(plan.planId)
        assertFalse(
            dao.saveArtifactAndUpdatePlan(
                beforeArtifacts.first().copy(path = "old-generation.ogg"),
                expectedGeneration = 6L,
                expectedSegmentIds = listOf("damaged", "intact")
            )
        )
        assertEquals(beforeArtifacts, dao.segmentArtifacts(plan.planId))
        assertEquals(NovelAudioStates.READY, dao.chapterPlan(plan.planId)?.state)
    }

    @Test
    fun damagedPlanLeavesActiveAndOtherGenerationTasksUntouched() {
        seedReadyPlan()
        val ready = dao.downloadTasksForPlan(plan.planId).single()
        val protectedTasks = listOf(
            ready.copy(taskId = "old-task", generation = 6L),
            ready.copy(taskId = "active-task", state = NovelAudioStates.RUNNING),
            ready.copy(taskId = "other-plan-task", planId = "other-plan")
        )
        protectedTasks.forEach { dao.insertDownloadTask(it) }

        assertTrue(
            repository.invalidateReadyState(
                planId = plan.planId,
                expectedGeneration = plan.generation,
                segmentId = "damaged",
                expectedExecutionAttempt = 0L
            )
        )

        val after = dao.downloadTasks(plan.physicalBookUrl).associateBy { it.taskId }
        protectedTasks.forEach { assertEquals(it, after[it.taskId]) }
        assertEquals(NovelAudioStates.PARTIAL, after[taskId]?.state)
        assertEquals("ARTIFACT_INVALID", after[taskId]?.stateReason)
    }

    @Test
    fun damagedExecutionOnlyInvalidatesItsOwnedRunningTask() {
        seedReadyPlan()
        val ready = dao.downloadTasksForPlan(plan.planId).single()
        dao.updateDownloadTask(ready.copy(state = NovelAudioStates.RUNNING))
        val execution = checkNotNull(
            repository.savePlanForExecution(plan, NovelAudioRetention.AUTO, "")
        )
        val protectedTasks = listOf(
            ready.copy(taskId = "another-ready-task"),
            ready.copy(taskId = "another-running-task", state = NovelAudioStates.RUNNING),
            ready.copy(taskId = "old-generation-task", generation = 6L),
            ready.copy(taskId = "other-attempt-task", executionAttempt = 1L),
            ready.copy(taskId = "other-plan-task", planId = "other-plan")
        )
        protectedTasks.forEach { dao.insertDownloadTask(it) }

        assertTrue(
            repository.invalidateReadyExecution(execution, listOf("damaged"), "ARTIFACT_INVALID")
        )

        val after = dao.downloadTasks(plan.physicalBookUrl).associateBy { it.taskId }
        protectedTasks.forEach { assertEquals(it, after[it.taskId]) }
        assertEquals(NovelAudioStates.PARTIAL, after[taskId]?.state)
        assertEquals(NovelAudioRetention.PINNED, after[taskId]?.retention)
        assertEquals(NovelAudioStates.PARTIAL, dao.chapterPlan(plan.planId)?.state)
        assertEquals(listOf("intact"), dao.readySegmentIds(plan.planId, listOf("damaged", "intact")))
    }

    @Test
    fun damagedExecutionRollsBackWhenItsOwnedTaskAttemptDoesNotMatch() {
        seedReadyPlan()
        val execution = checkNotNull(
            repository.savePlanForExecution(plan, NovelAudioRetention.AUTO, "")
        )
        val ready = dao.downloadTasksForPlan(plan.planId).single()
        dao.updateDownloadTask(ready.copy(executionAttempt = 1L))
        // An unrelated task cannot stand in for the execution's mismatched owner.
        dao.insertDownloadTask(ready.copy(taskId = "another-ready-task"))
        val beforePlan = dao.chapterPlan(plan.planId)
        val beforeArtifacts = dao.segmentArtifacts(plan.planId)
        val beforeTasks = dao.downloadTasksForPlan(plan.planId)

        val result = kotlin.runCatching {
            repository.invalidateReadyExecution(execution, listOf("damaged"), "ARTIFACT_INVALID")
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(beforePlan, dao.chapterPlan(plan.planId))
        assertEquals(beforeArtifacts, dao.segmentArtifacts(plan.planId))
        assertEquals(beforeTasks, dao.downloadTasksForPlan(plan.planId))
    }

    @Test
    fun staleExecutionRepairDoesNotChangeArtifactsAfterPlayerDemotesPlan() {
        seedReadyPlan()
        val execution = checkNotNull(
            repository.savePlanForExecution(plan, NovelAudioRetention.AUTO, "")
        )
        // The player's repair wins while the worker still holds its earlier READY execution.
        assertTrue(
            repository.invalidateReadyState(plan.planId, plan.generation, "damaged", 0L)
        )
        val beforePlan = dao.chapterPlan(plan.planId)
        val beforeArtifacts = dao.segmentArtifacts(plan.planId)
        val beforeTasks = dao.downloadTasksForPlan(plan.planId)

        assertFalse(
            repository.invalidateReadyExecution(execution, listOf("intact"), "ARTIFACT_INVALID")
        )

        assertEquals(beforePlan, dao.chapterPlan(plan.planId))
        assertEquals(beforeArtifacts, dao.segmentArtifacts(plan.planId))
        assertEquals(beforeTasks, dao.downloadTasksForPlan(plan.planId))
    }

    @Test
    fun lifecyclePersistsAndReleasesOnlyItsOwnRoomExecution() {
        val oldReleased = CountDownLatch(1)
        val newReleased = CountDownLatch(1)
        val released = Collections.synchronizedList(
            mutableListOf<NovelAudioRepository.Execution>()
        )
        val lifecycle = newRoomLifecycle { execution, reason ->
            assertTrue(repository.releaseExecution(execution, reason))
            released += execution
            if (execution.executionAttempt == 0L) {
                oldReleased.countDown()
            } else {
                newReleased.countDown()
            }
        }

        val request = lifecycle.replaceRequest(
            plan.physicalBookUrl,
            plan.chapterIndex,
            NovelAudioRetention.AUTO
        )
        val oldRun = checkNotNull(lifecycle.start(request))
        val oldExecution = checkNotNull(
            runBlocking {
                lifecycle.persist(
                    oldRun,
                    plan,
                    NovelAudioRetention.AUTO,
                    isAutoAllowed = { true }
                )
            }
        )
        assertRoomExecution(
            expectedGeneration = plan.generation,
            expectedAttempt = 0L,
            expectedPlanState = NovelAudioStates.PLANNED,
            expectedTaskState = NovelAudioStates.QUEUED
        )

        val replacementRequest = lifecycle.replaceRequest(
            plan.physicalBookUrl,
            plan.chapterIndex,
            NovelAudioRetention.AUTO
        )
        val newRun = checkNotNull(lifecycle.start(replacementRequest))
        val newExecution = checkNotNull(
            runBlocking {
                lifecycle.persist(
                    newRun,
                    plan,
                    NovelAudioRetention.AUTO,
                    isAutoAllowed = { true }
                )
            }
        )

        assertNotSame(oldExecution, newExecution)
        assertEquals(0L, oldExecution.executionAttempt)
        assertEquals(2L, newExecution.executionAttempt)
        assertTrue(oldReleased.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(oldExecution), released.take(1))
        assertRoomExecution(
            expectedGeneration = plan.generation,
            expectedAttempt = newExecution.executionAttempt,
            expectedPlanState = NovelAudioStates.PLANNED,
            expectedTaskState = NovelAudioStates.QUEUED
        )

        lifecycle.cancel()

        assertTrue(newReleased.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(oldExecution, newExecution), released)
        assertRoomExecution(
            expectedGeneration = plan.generation,
            expectedAttempt = newExecution.executionAttempt + 1L,
            expectedPlanState = NovelAudioStates.PARTIAL,
            expectedTaskState = NovelAudioStates.PARTIAL
        )
        assertEquals("CANCELLED", dao.chapterPlan(plan.planId)?.stateReason)
        assertEquals(
            "CANCELLED",
            dao.downloadTasksForPlan(plan.planId).single().stateReason
        )
    }

    @Test
    fun newLifecyclePersistWaitsForOldRoomCleanupBeforeSavingReplacement() {
        val releaseEntered = CountDownLatch(1)
        val releaseCanFinish = CountDownLatch(1)
        val releaseFinished = CountDownLatch(1)
        val newPersistFinished = CountDownLatch(1)
        val newExecution = AtomicReference<NovelAudioRepository.Execution?>()
        val lifecycle = newRoomLifecycle { execution, reason ->
            releaseEntered.countDown()
            assertTrue(releaseCanFinish.await(5, TimeUnit.SECONDS))
            assertTrue(repository.releaseExecution(execution, reason))
            releaseFinished.countDown()
        }

        val request = lifecycle.replaceRequest(
            plan.physicalBookUrl,
            plan.chapterIndex,
            NovelAudioRetention.AUTO
        )
        val oldRun = checkNotNull(lifecycle.start(request))
        assertNotNull(
            runBlocking {
                lifecycle.persist(
                    oldRun,
                    plan,
                    NovelAudioRetention.AUTO,
                    isAutoAllowed = { true }
                )
            }
        )

        val replacementRequest = lifecycle.replaceRequest(
            plan.physicalBookUrl,
            plan.chapterIndex,
            NovelAudioRetention.AUTO
        )
        val newRun = checkNotNull(lifecycle.start(replacementRequest))
        val persistThread = thread(start = true) {
            runBlocking {
                newExecution.set(
                    lifecycle.persist(
                        newRun,
                        plan,
                        NovelAudioRetention.AUTO,
                        isAutoAllowed = { true }
                    )
                )
                newPersistFinished.countDown()
            }
        }

        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
        assertFalse(newPersistFinished.await(200, TimeUnit.MILLISECONDS))
        assertRoomExecution(
            expectedGeneration = plan.generation,
            expectedAttempt = 0L,
            expectedPlanState = NovelAudioStates.PLANNED,
            expectedTaskState = NovelAudioStates.QUEUED
        )

        releaseCanFinish.countDown()
        assertTrue(releaseFinished.await(5, TimeUnit.SECONDS))
        assertTrue(newPersistFinished.await(5, TimeUnit.SECONDS))
        persistThread.join(5_000)
        assertNotNull(newExecution.get())
        assertEquals(2L, newExecution.get()!!.executionAttempt)
        assertRoomExecution(
            expectedGeneration = plan.generation,
            expectedAttempt = 2L,
            expectedPlanState = NovelAudioStates.PLANNED,
            expectedTaskState = NovelAudioStates.QUEUED
        )
    }

    @Test
    fun readyPinnedExecutionStaysReadyWhenPreparationCleanupFails() {
        seedReadyPlan()
        var cleanupCalls = 0
        val lifecycle = newRoomLifecycle { execution, reason ->
            cleanupCalls++
            repository.releaseExecution(execution, reason)
        }
        val request = lifecycle.replaceRequest(
            plan.physicalBookUrl,
            plan.chapterIndex,
            NovelAudioRetention.PINNED
        )
        val run = checkNotNull(lifecycle.start(request))
        val execution = checkNotNull(
            runBlocking {
                lifecycle.persist(
                    run,
                    plan,
                    NovelAudioRetention.PINNED,
                    isAutoAllowed = { true }
                )
            }
        )

        assertTrue(execution.alreadyReady)
        runBlocking {
            lifecycle.run(run, isSuccessful = { false }) {
                "ordinary cleanup failure"
            }
        }

        assertEquals(0, cleanupCalls)
        assertEquals(NovelAudioStates.READY, dao.chapterPlan(plan.planId)?.state)
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.chapterPlan(plan.planId)?.retention
        )
        assertEquals(
            NovelAudioStates.READY,
            dao.downloadTasksForPlan(plan.planId).single().state
        )
        assertEquals(
            NovelAudioRetention.PINNED,
            dao.downloadTasksForPlan(plan.planId).single().retention
        )
    }

    private fun seedReadyPlan() {
        dao.insertChapterPlan(
            plan.toEntity(NovelAudioRetention.PINNED)
                .copy(state = NovelAudioStates.READY, progress = 100)
        )
        dao.insertDownloadTask(
            NovelAudioDownloadTaskEntity(
                taskId = taskId,
                planId = plan.planId,
                physicalBookUrl = plan.physicalBookUrl,
                chapterIndex = plan.chapterIndex,
                generation = plan.generation,
                retention = NovelAudioRetention.PINNED,
                state = NovelAudioStates.READY,
                progress = 100
            )
        )
        listOf("damaged", "intact").forEach { segmentId ->
            dao.upsertSegmentArtifact(
                NovelAudioSegmentArtifactEntity(
                    planId = plan.planId,
                    segmentId = segmentId,
                    path = "$segmentId.ogg",
                    state = NovelAudioStates.READY
                )
            )
        }
    }

    /**
     * 真实 Room 上验证「暂停/取消不自动重启」：
     * 恢复队列只应取走非用户中断的 PINNED 任务。
     */
    @Test
    fun startupRecoveryOnlyTakesPinnedTasksNotInterruptedByTheUser() {
        val expected = mutableListOf<Int>()
        listOf(
            NovelAudioStates.QUEUED to NovelAudioRetention.PINNED,
            NovelAudioStates.RUNNING to NovelAudioRetention.PINNED,
            NovelAudioStates.PARTIAL to NovelAudioRetention.PINNED,
            NovelAudioStates.WAITING_NETWORK to NovelAudioRetention.PINNED,
            NovelAudioStates.FAILED to NovelAudioRetention.PINNED,
            NovelAudioStates.PAUSED to NovelAudioRetention.PINNED,
            NovelAudioStates.CANCELLED to NovelAudioRetention.PINNED,
            NovelAudioStates.READY to NovelAudioRetention.PINNED,
            NovelAudioStates.QUEUED to NovelAudioRetention.AUTO
        ).forEachIndexed { offset, (state, retention) ->
            val chapterIndex = 100 + offset
            dao.insertDownloadTask(
                NovelAudioDownloadTaskEntity(
                    taskId = "startup-task-$chapterIndex",
                    planId = "startup-plan-$chapterIndex",
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = chapterIndex,
                    generation = plan.generation,
                    retention = retention,
                    state = state
                )
            )
            val resumable = retention == NovelAudioRetention.PINNED &&
                state != NovelAudioStates.PAUSED &&
                state != NovelAudioStates.CANCELLED &&
                state != NovelAudioStates.FAILED &&
                state != NovelAudioStates.READY
            if (resumable) expected += chapterIndex
        }

        val resumed = Collections.synchronizedList(mutableListOf<Int>())
        val recovery = NovelAudioPinnedRecovery(
            pending = {
                repository.recoverablePinnedTasks().map { task ->
                    NovelAudioPinnedRecovery.PendingTask(
                        bookUrl = task.physicalBookUrl,
                        chapterIndex = task.chapterIndex,
                        retention = task.retention,
                        state = task.state
                    )
                }
            },
            resume = { _, chapterIndex -> resumed += chapterIndex; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(expected.sorted(), resumed)
    }

    private fun newRoomLifecycle(
        releaseExecution: (NovelAudioRepository.Execution, String) -> Unit
    ): NovelAudioPreparationLifecycle {
        val executor = Executors.newSingleThreadExecutor()
        lifecycleExecutors += executor
        return NovelAudioPreparationLifecycle(
            saveExecution = { nextPlan, retention ->
                repository.savePlanForExecution(nextPlan, retention, "")
            },
            releaseExecution = releaseExecution,
            mutationDispatcher = executor.asCoroutineDispatcher()
        )
    }

    private fun assertRoomExecution(
        expectedGeneration: Long,
        expectedAttempt: Long,
        expectedPlanState: String,
        expectedTaskState: String
    ) {
        val storedPlan = checkNotNull(dao.chapterPlan(plan.planId))
        val storedTask = dao.downloadTasksForPlan(plan.planId).single()
        assertEquals(expectedGeneration, storedPlan.generation)
        assertEquals(expectedGeneration, storedTask.generation)
        assertEquals(expectedAttempt, storedPlan.executionAttempt)
        assertEquals(expectedAttempt, storedTask.executionAttempt)
        assertEquals(expectedPlanState, storedPlan.state)
        assertEquals(expectedTaskState, storedTask.state)
    }
}
