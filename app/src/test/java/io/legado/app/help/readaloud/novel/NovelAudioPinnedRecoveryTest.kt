package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioStates
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 重启后的 PINNED 任务恢复。
 *
 * 只恢复用户手动固定的任务：AUTO 任务是播放授权的副产物，
 * 进程重建后授权已失效，自动复活会在用户没有听书时偷偷消耗不可退款的云额度。
 */
class NovelAudioPinnedRecoveryTest {

    private val book = "https://example.test/book"

    @Test
    fun `pinned tasks are resumed in chapter order`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(5), task(3), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(3, 4, 5), resumed)
    }

    @Test
    fun `an auto task is never resumed`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(3, retention = NovelAudioRetention.AUTO), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(4), resumed)
    }

    @Test
    fun `an already ready task is not resumed again`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(3, state = NovelAudioStates.READY), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(4), resumed)
    }

    @Test
    fun `cancelled and expired tasks stay untouched`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(
                task(3, state = NovelAudioStates.CANCELLED),
                task(4, state = NovelAudioStates.EXPIRED),
                task(5)
            ),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(5), resumed)
    }

    @Test
    fun `a user paused task is never auto resumed`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(3, state = NovelAudioStates.PAUSED), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        // 用户显式暂停必须被尊重：自动重启会违背用户意图并消耗不可退款额度。
        assertEquals(listOf(4), resumed)
    }

    @Test
    fun `a failed task is never auto resumed`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(3, state = NovelAudioStates.FAILED), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        // 每次启动都重试已失败章节，会在同一章上反复消耗不可退款额度。
        // 重试必须由用户显式发起，与 AUTO「失败只前进不重试」同源。
        assertEquals(listOf(4), resumed)
    }

    @Test
    fun `a failed task does not stop the remaining queue`() {
        val attempted = log()
        val recovery = recovery(
            tasks = listOf(task(3), task(4), task(5)),
            resume = { _, index -> attempted += index; index != 4 }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(3, 4, 5), attempted)
    }

    @Test
    fun `an unexpected failure is contained and the queue continues`() {
        val attempted = log()
        val recovery = recovery(
            tasks = listOf(task(3), task(4), task(5)),
            resume = { _, index ->
                attempted += index
                if (index == 4) throw IllegalStateException("boom")
                true
            }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(3, 4, 5), attempted)
    }

    @Test
    fun `cancellation stops the queue and propagates`() {
        val attempted = log()
        val recovery = recovery(
            tasks = listOf(task(3), task(4), task(5)),
            resume = { _, index ->
                attempted += index
                if (index == 4) throw CancellationException("cancelled")
                true
            }
        )

        try {
            runBlocking { recovery.recover() }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertEquals(listOf(3, 4), attempted)
        }
    }

    @Test
    fun `recovery without any pinned task does nothing`() {
        val resumed = log()
        val recovery = recovery(tasks = emptyList(), resume = { _, i -> resumed += i; true })

        runBlocking { recovery.recover() }

        assertTrue(resumed.isEmpty())
    }

    @Test
    fun `a task for a blank book is skipped`() {
        val resumed = log()
        val recovery = recovery(
            tasks = listOf(task(3).copy(bookUrl = ""), task(4)),
            resume = { _, index -> resumed += index; true }
        )

        runBlocking { recovery.recover() }

        assertEquals(listOf(4), resumed)
    }

    private fun log() = Collections.synchronizedList(mutableListOf<Int>())

    private fun task(
        chapterIndex: Int,
        retention: String = NovelAudioRetention.PINNED,
        state: String = NovelAudioStates.QUEUED
    ) = NovelAudioPinnedRecovery.PendingTask(
        bookUrl = book,
        chapterIndex = chapterIndex,
        retention = retention,
        state = state
    )

    private fun recovery(
        tasks: List<NovelAudioPinnedRecovery.PendingTask>,
        resume: suspend (String, Int) -> Boolean
    ) = NovelAudioPinnedRecovery(pending = { tasks }, resume = resume)
}
