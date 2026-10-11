package io.legado.app.help.readaloud.novel

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** Keep replacement batches behind the preceding batch's network cleanup. */
internal class NovelAudioRuntimeBatchLane {
    private val mutex = Mutex()

    suspend fun enter(): Permit {
        val owner = Any()
        mutex.lock(owner)
        return Permit { mutex.unlock(owner) }
    }

    internal class Permit(private val unlock: () -> Unit) {
        private val closed = AtomicBoolean(false)
        fun close() {
            if (closed.compareAndSet(false, true)) unlock()
        }
    }
}
