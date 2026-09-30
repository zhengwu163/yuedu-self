package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.NovelAudioRetention
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 后续章（AUTO）准备的独立入口。
 *
 * 关键不变量：AUTO 必须走自己的车道与自己的准备槽，绝不能占用当前章的请求槽。
 * 若占用，用户翻到新章时当前章的最终正文回调会因请求不匹配而被丢弃，
 * 导致用户正在等的那一章永远不被准备。
 */
class NovelAudioFollowingChapterPreparerTest {

    private val book = Book(bookUrl = "https://example.test/book", name = "测试书")
    private val rules = io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig().freeze()

    @Test
    fun `a following chapter is prepared with auto retention and releases its lane`() {
        val lane = NovelAudioPreparationLane()
        val retentions = log()
        val preparer = preparer(lane, retentions)

        assertTrue(runBlocking { preparer.prepare(book, 5, chapterCount = 100) })

        assertEquals(listOf(NovelAudioRetention.AUTO), retentions)
        assertTrue("车道必须释放", lane.beginAuto(book.bookUrl, 6))
    }

    @Test
    fun `the current chapter lane blocks following preparation entirely`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book.bookUrl, 4)
        val prepared = log()
        val preparer = preparer(lane, prepared)

        assertFalse(runBlocking { preparer.prepare(book, 5, chapterCount = 100) })

        assertTrue("当前章在途时不得准备后续章", prepared.isEmpty())
    }

    @Test
    fun `a chapter beyond the book is never prepared`() {
        val lane = NovelAudioPreparationLane()
        val prepared = log()
        val preparer = preparer(lane, prepared)

        assertFalse(runBlocking { preparer.prepare(book, 10, chapterCount = 10) })

        assertTrue(prepared.isEmpty())
    }

    @Test
    fun `a chapter without local content is skipped without calling the preparer`() {
        val lane = NovelAudioPreparationLane()
        val prepared = log()
        val preparer = preparer(lane, prepared, snapshot = { null })

        assertFalse(runBlocking { preparer.prepare(book, 5, chapterCount = 100) })

        assertTrue(prepared.isEmpty())
        assertTrue("跳过也必须释放车道", lane.beginAuto(book.bookUrl, 6))
    }

    @Test
    fun `a failed preparation still releases the lane`() {
        val lane = NovelAudioPreparationLane()
        val preparer = preparer(
            lane,
            log(),
            result = { NovelAudioChapterPreparer.Result.Failed("PLAN_NOT_READY", 1L) }
        )

        assertFalse(runBlocking { preparer.prepare(book, 5, chapterCount = 100) })

        assertTrue(lane.beginAuto(book.bookUrl, 6))
    }

    @Test
    fun `cancellation propagates and still releases the lane`() {
        val lane = NovelAudioPreparationLane()
        val preparer = preparer(
            lane,
            log(),
            result = { throw CancellationException("cancelled") }
        )

        try {
            runBlocking { preparer.prepare(book, 5, chapterCount = 100) }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue("取消也必须释放车道", lane.beginAuto(book.bookUrl, 6))
        }
    }

    @Test
    fun `an unexpected failure is contained and releases the lane`() {
        val lane = NovelAudioPreparationLane()
        val preparer = preparer(
            lane,
            log(),
            result = { throw IllegalStateException("boom") }
        )

        assertFalse(runBlocking { preparer.prepare(book, 5, chapterCount = 100) })

        assertTrue(lane.beginAuto(book.bookUrl, 6))
    }

    @Test
    fun `the generation is derived from the snapshot instead of the reader`() {
        val lane = NovelAudioPreparationLane()
        val generations = Collections.synchronizedList(mutableListOf<Long>())
        val preparer = NovelAudioFollowingChapterPreparer(
            lane = lane,
            snapshot = { _, index -> snapshotFor(index) },
            prepare = { _, generation, _ ->
                generations += generation
                NovelAudioChapterPreparer.Result.Ready(plan())
            }
        )

        runBlocking { preparer.prepare(book, 5, chapterCount = 100) }
        runBlocking { preparer.prepare(book, 6, chapterCount = 100) }

        // 后续章没有阅读页代次，必须用快照散列派生出稳定且互不相同的代次。
        assertEquals(2, generations.size)
        assertTrue(generations.all { it >= 0L })
        assertTrue(generations[0] != generations[1])
    }

    private fun log() = Collections.synchronizedList(mutableListOf<String>())

    private fun snapshotFor(index: Int) = NovelAudioChapterSnapshotFactory.fromStrings(
        bookUrl = book.bookUrl,
        chapterIndex = index,
        chapterUrl = "https://example.test/chapter/$index",
        strings = listOf("第${index}章正文"),
        title = book.name,
        rules = rules
    )

    private fun plan() = NovelAudioChapterPlan(
        planId = "plan",
        workKey = "work",
        physicalBookUrl = book.bookUrl,
        chapterIndex = 5,
        generation = 1L
    )

    private fun preparer(
        lane: NovelAudioPreparationLane,
        observed: MutableList<String>,
        snapshot: (Int) -> NovelAudioChapterSnapshot? = { snapshotFor(it) },
        result: () -> NovelAudioChapterPreparer.Result? = {
            NovelAudioChapterPreparer.Result.Ready(plan())
        }
    ) = NovelAudioFollowingChapterPreparer(
        lane = lane,
        snapshot = { _, index -> snapshot(index) },
        prepare = { _, _, retention ->
            observed += retention
            result()
        }
    )
}
