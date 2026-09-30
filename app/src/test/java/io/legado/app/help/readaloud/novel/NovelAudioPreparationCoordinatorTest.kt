package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioPreparationCoordinatorTest {

    @Test
    fun everyStartGetsANewTokenEvenWhenTheRequestIsIdentical() {
        val lifecycle = newLifecycle()
        val request = lifecycle.replaceRequest("book://same", 3, NovelAudioRetention.AUTO)

        val first = checkNotNull(lifecycle.start(request))
        val second = checkNotNull(lifecycle.start(request))

        assertNotEquals(first.id, second.id)
        assertFalse(lifecycle.isCurrent(first))
        assertTrue(lifecycle.isCurrent(second))
    }

    @Test
    fun cancelInvalidatesTheRunBeforeItsReleaseRuns() {
        val releaseEntered = CountDownLatch(1)
        val releaseCanFinish = CountDownLatch(1)
        val lifecycle = newLifecycle(
            releaseExecution = { _, _ ->
                releaseEntered.countDown()
                assertTrue(releaseCanFinish.await(5, TimeUnit.SECONDS))
            }
        )
        val request = lifecycle.replaceRequest("book://cancel", 1, NovelAudioRetention.AUTO)
        val token = checkNotNull(lifecycle.start(request))
        runBlocking {
            lifecycle.persist(token, plan("cancel-plan"), NovelAudioRetention.AUTO)
        }

        lifecycle.cancel()

        assertFalse(lifecycle.isCurrent(token))
        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
        releaseCanFinish.countDown()
    }

    @Test
    fun replacementReleasesTheOldExecutionBeforeTheNewRunCanSave() {
        val oldSaveEntered = CountDownLatch(1)
        val oldSaveCanFinish = CountDownLatch(1)
        val oldReleaseEntered = CountDownLatch(1)
        val oldReleaseFinished = CountDownLatch(1)
        val newSaveEntered = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val oldExecution = execution("old-execution")
        val newExecution = execution("new-execution")
        val lifecycle = newLifecycle(
            saveExecution = { plan, _ ->
                if (plan.planId == "old-plan") {
                    order += "old-save-start"
                    oldSaveEntered.countDown()
                    assertTrue(oldSaveCanFinish.await(5, TimeUnit.SECONDS))
                    order += "old-save-finish"
                    oldExecution
                } else {
                    newSaveEntered.countDown()
                    order += "new-save"
                    newExecution
                }
            },
            releaseExecution = { execution, _ ->
                assertSame(oldExecution, execution)
                order += "old-release"
                oldReleaseEntered.countDown()
                oldReleaseFinished.countDown()
            }
        )
        val oldRequest = lifecycle.replaceRequest("book://replace", 1, NovelAudioRetention.AUTO)
        val oldToken = checkNotNull(lifecycle.start(oldRequest))
        val oldPersist = thread(start = true) {
            runBlocking {
                assertNull(
                    lifecycle.persist(
                        oldToken,
                        plan("old-plan"),
                        NovelAudioRetention.AUTO
                    )
                )
            }
        }
        assertTrue(oldSaveEntered.await(5, TimeUnit.SECONDS))

        val newRequest = lifecycle.replaceRequest("book://replace", 1, NovelAudioRetention.AUTO)
        val newToken = checkNotNull(lifecycle.start(newRequest))
        val newPersist = thread(start = true) {
            runBlocking {
                assertSame(
                    newExecution,
                    lifecycle.persist(
                        newToken,
                        plan("new-plan"),
                        NovelAudioRetention.AUTO
                    )
                )
            }
        }

        assertFalse(newSaveEntered.await(200, TimeUnit.MILLISECONDS))
        oldSaveCanFinish.countDown()
        assertTrue(oldReleaseEntered.await(5, TimeUnit.SECONDS))
        assertTrue(oldReleaseFinished.await(5, TimeUnit.SECONDS))
        assertTrue(newSaveEntered.await(5, TimeUnit.SECONDS))

        oldPersist.join(5_000)
        newPersist.join(5_000)
        assertEquals(
            listOf("old-save-start", "old-save-finish", "old-release", "new-save"),
            order
        )
    }

    @Test
    fun stalePersistReleasesItsOwnExecutionAndCannotInstallItOnTheNewRun() {
        val saveEntered = CountDownLatch(1)
        val saveCanFinish = CountDownLatch(1)
        val released = Collections.synchronizedList(mutableListOf<NovelAudioRepository.Execution>())
        val staleExecution = execution("stale-execution")
        val lifecycle = newLifecycle(
            saveExecution = { _, _ ->
                saveEntered.countDown()
                assertTrue(saveCanFinish.await(5, TimeUnit.SECONDS))
                staleExecution
            },
            releaseExecution = { execution, _ -> released += execution }
        )
        val oldRequest = lifecycle.replaceRequest("book://stale", 1, NovelAudioRetention.AUTO)
        val oldToken = checkNotNull(lifecycle.start(oldRequest))
        val persist = thread(start = true) {
            runBlocking {
                assertNull(
                    lifecycle.persist(
                        oldToken,
                        plan("stale-plan"),
                        NovelAudioRetention.AUTO
                    )
                )
            }
        }
        assertTrue(saveEntered.await(5, TimeUnit.SECONDS))

        lifecycle.replaceRequest("book://stale", 2, NovelAudioRetention.AUTO)
        saveCanFinish.countDown()

        persist.join(5_000)
        assertEquals(listOf(staleExecution), released)
        assertNull(lifecycle.execution(oldToken))
    }

    @Test
    fun finallyReleasesPersistedExecutionWhenThePreparationFails() {
        val released = Collections.synchronizedList(mutableListOf<NovelAudioRepository.Execution>())
        val lifecycle = newLifecycle(
            saveExecution = { _, _ -> execution("finally-execution") },
            releaseExecution = { execution, _ -> released += execution }
        )
        val request = lifecycle.replaceRequest("book://finally", 1, NovelAudioRetention.AUTO)
        val token = checkNotNull(lifecycle.start(request))
        runBlocking {
            lifecycle.persist(token, plan("finally-plan"), NovelAudioRetention.AUTO)
            lifecycle.run(token, isSuccessful = { false }) {
                "failed"
            }
        }

        assertEquals(1, released.size)
        assertEquals("finally-execution", released.single().plan.planId)
    }

    @Test
    fun finallyDoesNotReleaseACompletedReadyExecution() {
        val released = Collections.synchronizedList(mutableListOf<NovelAudioRepository.Execution>())
        val lifecycle = newLifecycle(
            saveExecution = { _, _ ->
                execution("ready-execution", alreadyReady = true)
            },
            releaseExecution = { execution, _ -> released += execution }
        )
        val request = lifecycle.replaceRequest("book://ready", 1, NovelAudioRetention.PINNED)
        val token = checkNotNull(lifecycle.start(request))
        runBlocking {
            lifecycle.persist(token, plan("ready-plan"), NovelAudioRetention.PINNED)
            lifecycle.run(token, isSuccessful = { true }) {
                "ready"
            }
        }

        lifecycle.cancel()

        assertTrue(released.isEmpty())
    }

    @Test
    fun pinnedRunIgnoresAutomaticLeaseButOwnCancellationStillReleasesIt() {
        val releaseEntered = CountDownLatch(1)
        val released = Collections.synchronizedList(mutableListOf<NovelAudioRepository.Execution>())
        val lifecycle = newLifecycle(
            saveExecution = { _, _ -> execution("pinned-execution") },
            releaseExecution = { execution, _ ->
                released += execution
                releaseEntered.countDown()
            }
        )
        val request = lifecycle.replaceRequest("book://pinned", 1, NovelAudioRetention.PINNED)
        val token = checkNotNull(lifecycle.start(request))

        assertTrue(lifecycle.isCurrent(token))
        runBlocking {
            lifecycle.persist(token, plan("pinned-plan"), NovelAudioRetention.PINNED)
        }

        lifecycle.cancel()

        assertFalse(lifecycle.isCurrent(token))
        assertTrue(releaseEntered.await(5, TimeUnit.SECONDS))
        assertEquals(1, released.size)
        assertEquals("pinned-execution", released.single().plan.planId)
    }

    @Test
    fun staleCallbackCannotPublishOrClearTheNewRun() {
        val lifecycle = newLifecycle()
        val firstRequest = lifecycle.replaceRequest("book://callback", 1, NovelAudioRetention.AUTO)
        val first = checkNotNull(lifecycle.start(firstRequest))
        val secondRequest = lifecycle.replaceRequest("book://callback", 1, NovelAudioRetention.AUTO)
        val second = checkNotNull(lifecycle.start(secondRequest))
        var oldEvents = 0
        var newEvents = 0

        assertFalse(
            lifecycle.dispatchIfCurrent(first, isCurrent = { true }) {
                oldEvents++
            }
        )
        assertTrue(
            lifecycle.dispatchIfCurrent(second, isCurrent = { true }) {
                newEvents++
                lifecycle.clearRequestIfCurrent(second)
            }
        )

        assertEquals(0, oldEvents)
        assertEquals(1, newEvents)
        assertNull(lifecycle.currentRequest())
    }

    private fun newLifecycle(
        saveExecution: (NovelAudioChapterPlan, String) -> NovelAudioRepository.Execution? = { plan, retention ->
            execution(plan.planId, retention = retention)
        },
        releaseExecution: (NovelAudioRepository.Execution, String) -> Unit = { _, _ -> }
    ): NovelAudioPreparationLifecycle {
        return NovelAudioPreparationLifecycle(
            saveExecution = saveExecution,
            releaseExecution = releaseExecution,
            mutationDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        )
    }

    private fun plan(id: String): NovelAudioChapterPlan {
        return NovelAudioChapterPlan(
            planId = id,
            workKey = "work:$id",
            physicalBookUrl = "book://$id",
            chapterIndex = 1,
            generation = 1L
        )
    }

    private fun execution(
        id: String,
        retention: String = NovelAudioRetention.AUTO,
        alreadyReady: Boolean = false
    ): NovelAudioRepository.Execution {
        return NovelAudioRepository.Execution(
            plan = plan(id),
            executionAttempt = 0L,
            retention = retention,
            alreadyReady = alreadyReady
        )
    }
}
