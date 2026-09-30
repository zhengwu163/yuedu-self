package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.server.NovelAudioServerException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NovelAudioBudgetLedgerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var caseNumber = 0

    @Test
    fun reservationPersistsUtf16CharactersAndVendorRequestCounts() {
        val stateFile = newStateFile()
        val ledger = NovelAudioBudgetLedger(
            stateFile = stateFile,
            limits = NovelAudioBudgetLedger.Limits(
                analysisRequests = 2,
                analysisUtf16Characters = 10,
                ttsVendorRequests = 4,
                ttsUtf16Characters = 10
            )
        )

        ledger.reserve(
            kind = NovelAudioBudgetLedger.Kind.ANALYSIS,
            utf16Characters = "😀a".length
        ).close()
        ledger.reserve(
            kind = NovelAudioBudgetLedger.Kind.TTS,
            utf16Characters = 3,
            vendorRequests = 2
        ).close()

        val restored = NovelAudioBudgetLedger(stateFile, ledger.limits)
        assertEquals(
            NovelAudioBudgetLedger.State(
                analysisRequests = 1,
                analysisUtf16Characters = 3,
                ttsVendorRequests = 2,
                ttsUtf16Characters = 3,
                cloudQuotaBlocked = false
            ),
            restored.snapshot()
        )
    }

    @Test
    fun defaultCombinedBudgetIs120RequestsAnd29000Utf16Characters() {
        val ledger = NovelAudioBudgetLedger(newStateFile())

        assertEquals(120, ledger.limits.totalRequests)
        assertEquals(29_000, ledger.limits.totalUtf16Characters)
    }

    @Test
    fun smallerCombinedBudgetAppliesAcrossAnalysisAndTts() {
        val limits = NovelAudioBudgetLedger.Limits(
            analysisRequests = 5,
            analysisUtf16Characters = 10,
            ttsVendorRequests = 5,
            ttsUtf16Characters = 10,
            totalRequests = 2,
            totalUtf16Characters = 3
        )
        val ledger = NovelAudioBudgetLedger(newStateFile(), limits)

        ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 2).close()
        ledger.reserve(NovelAudioBudgetLedger.Kind.TTS, 1).close()

        assertKind("LOCAL_BUDGET_EXHAUSTED") {
            ledger.reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
        }
    }

    @Test
    fun oversizedReservationCannotWrapCountersOrPartiallyCommit() {
        val ledger = NovelAudioBudgetLedger(
            newStateFile(),
            NovelAudioBudgetLedger.Limits(
                analysisRequests = Int.MAX_VALUE,
                analysisUtf16Characters = Int.MAX_VALUE,
                ttsVendorRequests = Int.MAX_VALUE,
                ttsUtf16Characters = Int.MAX_VALUE
            )
        )

        assertKind("LOCAL_BUDGET_EXHAUSTED") {
            ledger.reserve(
                kind = NovelAudioBudgetLedger.Kind.ANALYSIS,
                utf16Characters = Int.MAX_VALUE,
                vendorRequests = Int.MAX_VALUE
            )
        }
        assertEquals(
            NovelAudioBudgetLedger.State(),
            ledger.snapshot()
        )
    }

    @Test
    fun activeReservationRejectsASecondPhysicalGenerationWithoutWaiting() {
        val ledger = NovelAudioBudgetLedger(
            newStateFile(),
            NovelAudioBudgetLedger.Limits(
                analysisRequests = 2,
                analysisUtf16Characters = 10,
                ttsVendorRequests = 4,
                ttsUtf16Characters = 10
            )
        )
        val first = ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1)
        try {
            assertKind("CONCURRENCY_LIMIT") {
                ledger.reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
            }
        } finally {
            first.close()
        }
    }

    @Test
    fun reservationDoesNotRefundAfterFailureOrCancellation() {
        val stateFile = newStateFile()
        val limits = NovelAudioBudgetLedger.Limits(
            analysisRequests = 1,
            analysisUtf16Characters = 3,
            ttsVendorRequests = 1,
            ttsUtf16Characters = 3
        )
        val ledger = NovelAudioBudgetLedger(stateFile, limits)
        ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 3).close()

        assertEquals(1, NovelAudioBudgetLedger(stateFile, limits).snapshot().analysisRequests)
        assertKind("LOCAL_BUDGET_EXHAUSTED") {
            NovelAudioBudgetLedger(stateFile, limits)
                .reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1)
        }
    }

    @Test
    fun inFlightMarkerIsPersistedAndStaleMarkerFailsClosed() {
        val stateFile = newStateFile()
        val ledger = NovelAudioBudgetLedger(stateFile)
        val reservation = ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1)
        try {
            assertTrue(stateFile.readText().contains("\"inFlight\":true"))
        } finally {
            reservation.close()
        }
        assertTrue(stateFile.readText().contains("\"inFlight\":false"))

        stateFile.writeText(stateJson(inFlight = true))
        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).snapshot()
        }
        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
        }
    }

    @Test
    fun closeIsCrossThreadIdempotentAndThreadSafe() {
        val ledger = NovelAudioBudgetLedger(
            newStateFile(),
            NovelAudioBudgetLedger.Limits(
                analysisRequests = 3,
                analysisUtf16Characters = 10,
                ttsVendorRequests = 3,
                ttsUtf16Characters = 10
            )
        )
        val reservation = ledger.reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val finished = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                executor.execute {
                    try {
                        reservation.close()
                    } catch (error: Throwable) {
                        failures += error
                    } finally {
                        finished.countDown()
                    }
                }
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
            ledger.reserve(NovelAudioBudgetLedger.Kind.TTS, 1).close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun corruptedStateFailsClosedInsteadOfResettingTheBudget() {
        val stateFile = newStateFile()
        stateFile.writeText("{broken")

        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).snapshot()
        }
    }

    @Test
    fun duplicateJsonKeyFailsClosed() {
        assertUnavailable(
            """{"schemaVersion":2,"analysisRequests":0,"analysisRequests":0,"analysisUtf16Characters":0,"ttsVendorRequests":0,"ttsUtf16Characters":0,"cloudQuotaBlocked":false,"localBudgetBlocked":false,"inFlight":false}"""
        )
    }

    @Test
    fun trailingJsonFailsClosed() {
        assertUnavailable(stateJson() + stateJson())
    }

    @Test
    fun missingFieldFailsClosed() {
        assertUnavailable(stateJson().replace(",\"inFlight\":false", ""))
    }

    @Test
    fun negativeValueFailsClosed() {
        assertUnavailable(stateJson(analysisRequests = -1))
    }

    @Test
    fun overLimitStateFailsClosed() {
        val stateFile = newStateFile()
        stateFile.writeText(stateJson(analysisRequests = 2))

        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(
                stateFile,
                NovelAudioBudgetLedger.Limits(
                    analysisRequests = 1,
                    analysisUtf16Characters = 10,
                    ttsVendorRequests = 1,
                    ttsUtf16Characters = 10
                )
            ).snapshot()
        }
    }

    @Test
    fun oversizedStateFailsClosedWithoutReadingUnboundedInput() {
        val stateFile = newStateFile()
        stateFile.writeText(" ".repeat(20_000))

        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).snapshot()
        }
    }

    @Test
    fun deletingStateAfterInitializationCannotResetTheBudget() {
        val stateFile = newStateFile()
        val ledger = NovelAudioBudgetLedger(stateFile)
        ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1).close()
        assertTrue(stateFile.delete())

        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).snapshot()
        }
    }

    @Test
    fun localBudgetFusePersistsAndRejectsFurtherReservations() {
        val stateFile = newStateFile()
        NovelAudioBudgetLedger(stateFile).blockLocalBudget()

        val restored = NovelAudioBudgetLedger(stateFile)
        assertTrue(restored.snapshot().localBudgetBlocked)
        assertKind("LOCAL_BUDGET_EXHAUSTED") {
            restored.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1)
        }
    }

    @Test
    fun localBudgetFuseRejectsInMemoryAfterPersistenceFailure() {
        val stateFile = newStateFile()
        val ledger = NovelAudioBudgetLedger(stateFile)
        ledger.snapshot()
        assertTrue(stateFile.delete())
        assertTrue(stateFile.mkdir())

        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            ledger.blockLocalBudget()
        }
        assertKind("LOCAL_BUDGET_EXHAUSTED") {
            ledger.reserve(NovelAudioBudgetLedger.Kind.ANALYSIS, 1)
        }
    }

    @Test
    fun cloudQuotaBlockPersistsAcrossLedgerInstances() {
        val stateFile = newStateFile()
        val ledger = NovelAudioBudgetLedger(stateFile)
        ledger.blockCloudQuota()

        assertTrue(NovelAudioBudgetLedger(stateFile).snapshot().cloudQuotaBlocked)
        assertKind("FREE_QUOTA_EXHAUSTED") {
            NovelAudioBudgetLedger(stateFile).reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
        }
    }

    @Test
    fun concurrentReservationsAcrossLedgerInstancesHaveOneWinnerWithoutWorkerExceptions() {
        val stateFile = newStateFile()
        val limits = NovelAudioBudgetLedger.Limits(
            analysisRequests = 2,
            analysisUtf16Characters = 10,
            ttsVendorRequests = 1,
            ttsUtf16Characters = 10
        )
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val successes = AtomicInteger()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(2) {
                executor.execute {
                    try {
                        start.await(5, TimeUnit.SECONDS)
                        NovelAudioBudgetLedger(stateFile, limits)
                            .reserve(NovelAudioBudgetLedger.Kind.TTS, 1)
                            .close()
                        successes.incrementAndGet()
                    } catch (error: NovelAudioServerException) {
                        if (error.kind != "CONCURRENCY_LIMIT" &&
                            error.kind != "LOCAL_BUDGET_EXHAUSTED"
                        ) {
                            failures += error
                        }
                    } catch (error: Throwable) {
                        failures += error
                    } finally {
                        finished.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(failures.isEmpty())
            assertEquals(1, successes.get())
            assertEquals(
                1,
                NovelAudioBudgetLedger(stateFile, limits).snapshot().ttsVendorRequests
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertUnavailable(content: String) {
        val stateFile = newStateFile()
        stateFile.writeText(content)
        assertKind("LOCAL_BUDGET_UNAVAILABLE") {
            NovelAudioBudgetLedger(stateFile).snapshot()
        }
    }

    private fun assertKind(kind: String, action: () -> Unit) {
        try {
            action()
            fail("expected $kind")
        } catch (error: NovelAudioServerException) {
            assertEquals(kind, error.kind)
        }
    }

    private fun newStateFile(): File =
        File(temporaryFolder.newFolder("case-${caseNumber++}"), "budget.json")

    private fun stateJson(
        analysisRequests: Int = 0,
        analysisUtf16Characters: Int = 0,
        ttsVendorRequests: Int = 0,
        ttsUtf16Characters: Int = 0,
        cloudQuotaBlocked: Boolean = false,
        localBudgetBlocked: Boolean = false,
        inFlight: Boolean = false
    ): String = buildString {
        append('{')
        append("\"schemaVersion\":2,")
        append("\"analysisRequests\":").append(analysisRequests).append(',')
        append("\"analysisUtf16Characters\":").append(analysisUtf16Characters).append(',')
        append("\"ttsVendorRequests\":").append(ttsVendorRequests).append(',')
        append("\"ttsUtf16Characters\":").append(ttsUtf16Characters).append(',')
        append("\"cloudQuotaBlocked\":").append(cloudQuotaBlocked).append(',')
        append("\"localBudgetBlocked\":").append(localBudgetBlocked).append(',')
        append("\"inFlight\":").append(inFlight)
        append('}')
    }
}
