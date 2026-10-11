package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.help.readaloud.server.RuntimeStatus

/** Caller bounds cleanup time. Never acquires or releases an unknown lease. */
internal object NovelAudioRuntimeReleaseRecovery {
    suspend fun finish(
        release: suspend () -> Unit,
        status: suspend () -> RuntimeStatus,
        pause: suspend () -> Unit
    ) {
        var failure = try {
            release()
            return
        } catch (error: NovelAudioServerException) {
            if (!recoverable(error)) throw error
            error
        }
        while (true) {
            val runtime = status()
            if (runtime.state == "idle" && !runtime.activeLease) return
            if (runtime.state == "ready" && runtime.activeLease) {
                // An invalid old lease must never be used to claim a new owner.
                if (failure.kind == "INVALID_LEASE") throw failure
                try {
                    release()
                    return
                } catch (error: NovelAudioServerException) {
                    if (!recoverable(error)) throw error
                    failure = error
                }
            } else if (runtime.state !in setOf("starting", "generating", "unloading")) {
                throw failure
            }
            pause()
        }
    }

    /** A cancelled acquire has no opaque lease yet; only observe cleanup. */
    suspend fun awaitIdle(status: suspend () -> RuntimeStatus, pause: suspend () -> Unit) {
        while (true) {
            val runtime = status()
            if (runtime.state == "idle" && !runtime.activeLease) return
            if (runtime.state == "failed") throw NovelAudioServerException("UNAVAILABLE")
            pause()
        }
    }

    private fun recoverable(error: NovelAudioServerException): Boolean =
        error.kind == "CONCURRENCY_LIMIT" || error.kind == "INVALID_LEASE"
}
