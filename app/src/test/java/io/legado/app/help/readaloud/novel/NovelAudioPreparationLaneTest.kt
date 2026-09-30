package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当前章准备与 AUTO 预取之间的车道仲裁。
 *
 * 协调器只有一个请求槽，预算账本对同一账本文件也只放行一个生成请求，
 * 因此两者必须互斥且有固定优先级：当前章永远优先，AUTO 只在空闲时补后续章。
 * 没有这层仲裁，AUTO 预取会把用户正在等待的当前章请求挤掉，或撞并发上限失败。
 */
class NovelAudioPreparationLaneTest {

    private val book = "https://example.test/book"

    @Test
    fun `current chapter is always admitted and becomes the active lane`() {
        val lane = NovelAudioPreparationLane()

        assertTrue(lane.beginCurrent(book, 4))
        assertTrue(lane.isCurrentActive)
    }

    @Test
    fun `auto prefetch is refused while the current chapter is preparing`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book, 4)

        assertFalse(lane.beginAuto(book, 5))
    }

    @Test
    fun `auto prefetch is admitted once the current chapter finishes`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book, 4)
        lane.finishCurrent(book, 4)

        assertTrue(lane.beginAuto(book, 5))
        assertFalse(lane.isCurrentActive)
    }

    @Test
    fun `a new current chapter preempts an in-flight auto prefetch`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book, 4)
        lane.finishCurrent(book, 4)
        assertTrue(lane.beginAuto(book, 5))

        assertTrue(lane.beginCurrent(book, 6))

        assertTrue(lane.isCurrentActive)
        // 被抢占的 AUTO 目标不再是活动车道，其迟到结果必须被丢弃。
        assertFalse(lane.isAutoActive(book, 5))
    }

    @Test
    fun `only one auto target may be in flight at a time`() {
        val lane = NovelAudioPreparationLane()
        assertTrue(lane.beginAuto(book, 5))

        assertFalse(lane.beginAuto(book, 6))
    }

    @Test
    fun `finishing an auto target frees the lane for the next one`() {
        val lane = NovelAudioPreparationLane()
        lane.beginAuto(book, 5)
        lane.finishAuto(book, 5)

        assertTrue(lane.beginAuto(book, 6))
    }

    @Test
    fun `a stale auto completion does not free a newer lane`() {
        val lane = NovelAudioPreparationLane()
        lane.beginAuto(book, 5)
        lane.finishAuto(book, 5)
        lane.beginAuto(book, 6)

        lane.finishAuto(book, 5)

        assertFalse("旧目标完成不得释放新目标", lane.beginAuto(book, 7))
        assertTrue(lane.isAutoActive(book, 6))
    }

    @Test
    fun `a stale current completion does not release a newer current chapter`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book, 4)
        lane.beginCurrent(book, 6)

        lane.finishCurrent(book, 4)

        assertTrue(lane.isCurrentActive)
        assertFalse(lane.beginAuto(book, 7))
    }

    @Test
    fun `switching books clears both lanes`() {
        val lane = NovelAudioPreparationLane()
        lane.beginAuto(book, 5)

        assertTrue(lane.beginCurrent("https://example.test/other", 0))
        lane.finishCurrent("https://example.test/other", 0)

        assertFalse(lane.isAutoActive(book, 5))
        assertTrue(lane.beginAuto("https://example.test/other", 1))
    }

    @Test
    fun `revoke stops both lanes and refuses auto until a new current chapter`() {
        val lane = NovelAudioPreparationLane()
        lane.beginAuto(book, 5)

        lane.revoke()

        assertFalse(lane.isAutoActive(book, 5))
        assertFalse(lane.isCurrentActive)
        assertTrue(lane.beginAuto(book, 5))
    }

    @Test
    fun `blank book or negative chapter is never admitted`() {
        val lane = NovelAudioPreparationLane()

        assertFalse(lane.beginCurrent("", 0))
        assertFalse(lane.beginCurrent(book, -1))
        assertFalse(lane.beginAuto("", 1))
        assertFalse(lane.beginAuto(book, -1))
    }

    @Test
    fun `auto never targets the chapter that is already current`() {
        val lane = NovelAudioPreparationLane()
        lane.beginCurrent(book, 4)
        lane.finishCurrent(book, 4)

        assertFalse(lane.beginAuto(book, 4))
        assertEquals(true, lane.beginAuto(book, 5))
    }
}
