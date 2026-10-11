package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.help.readaloud.server.RuntimeStatus
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NovelAudioRuntimeReleaseRecoveryTest {
    @Test
    fun `busy release waits for generation then retries only owned lease`() = runBlocking {
        var releases = 0
        var pauses = 0
        val statuses = ArrayDeque(listOf(RuntimeStatus("generating", true), RuntimeStatus("ready", true)))
        NovelAudioRuntimeReleaseRecovery.finish(
            release = { if (++releases == 1) throw NovelAudioServerException("CONCURRENCY_LIMIT") },
            status = { statuses.removeFirst() },
            pause = { pauses++ }
        )
        assertEquals(2, releases)
        assertEquals(1, pauses)
    }

    @Test
    fun `cancelled lease invalidation drains unloading without another release`() = runBlocking {
        var releases = 0
        val statuses = ArrayDeque(listOf(RuntimeStatus("unloading", false), RuntimeStatus("idle", false)))
        NovelAudioRuntimeReleaseRecovery.finish(
            release = { releases++; throw NovelAudioServerException("INVALID_LEASE") },
            status = { statuses.removeFirst() },
            pause = { }
        )
        assertEquals(1, releases)
    }

    @Test
    fun `a new owner's ready lease is never released`() = runBlocking {
        var releases = 0
        try {
            NovelAudioRuntimeReleaseRecovery.finish(
                release = { releases++; throw NovelAudioServerException("INVALID_LEASE") },
                status = { RuntimeStatus("ready", true) },
                pause = { fail("must not keep polling another owner") }
            )
            fail("expected invalid lease")
        } catch (error: NovelAudioServerException) { assertEquals("INVALID_LEASE", error.kind) }
        assertEquals(1, releases)
    }

    @Test
    fun `authentication failure is not retried`() = runBlocking {
        try {
            NovelAudioRuntimeReleaseRecovery.finish(
                release = { throw NovelAudioServerException("AUTH") },
                status = { fail("must not poll"); RuntimeStatus() },
                pause = { fail("must not retry") }
            )
            fail("expected authentication error")
        } catch (error: NovelAudioServerException) { assertEquals("AUTH", error.kind) }
    }

    @Test
    fun `cancelled startup only waits for idle and remains bounded`() = runBlocking {
        var pauses = 0
        val statuses = ArrayDeque(listOf(RuntimeStatus("starting", false), RuntimeStatus("unloading", false), RuntimeStatus("idle", false)))
        NovelAudioRuntimeReleaseRecovery.awaitIdle(
            status = { statuses.removeFirst() }, pause = { pauses++ }
        )
        assertEquals(2, pauses)
        try {
            withTimeout(20) {
                NovelAudioRuntimeReleaseRecovery.awaitIdle(
                    status = { RuntimeStatus("unloading", false) }, pause = { awaitCancellation() }
                )
            }
            fail("expected bounded cleanup timeout")
        } catch (_: TimeoutCancellationException) { }
    }
}
