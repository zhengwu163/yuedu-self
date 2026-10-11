package io.legado.app.help.readaloud.novel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioRuntimeBatchLaneTest {
    @Test
    fun `cancelled predecessor still owns lane until release finishes`() = runBlocking {
        withTimeout(2000) {
            val lane = NovelAudioRuntimeBatchLane()
            val permit = lane.enter()
            val releasing = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            val old = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally {
                    withContext(NonCancellable) {
                        releasing.complete(Unit)
                        released.await()
                        permit.close()
                    }
                }
            }
            old.cancel()
            releasing.await()
            val next = async(start = CoroutineStart.UNDISPATCHED) { lane.enter() }
            assertFalse(next.isCompleted)
            released.complete(Unit)
            old.join()
            next.await().close()
            assertTrue(next.isCompleted)
        }
    }

    @Test
    fun `cancelled waiter does not strand lane and close is idempotent`() = runBlocking {
        withTimeout(2000) {
            val lane = NovelAudioRuntimeBatchLane()
            val first = lane.enter()
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { lane.enter() }
            assertFalse(waiting.isCompleted)
            waiting.cancelAndJoin()
            first.close()
            first.close()
            lane.enter().close()
        }
    }
}
