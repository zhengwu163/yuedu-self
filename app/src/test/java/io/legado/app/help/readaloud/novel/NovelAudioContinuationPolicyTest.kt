package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨章连播在下一章尚未就绪时的补救判定。
 *
 * 实测缺口：章节播完后走 `nextChapter()` → `moveToNextChapter(fromReadAloud=true)`
 * → `readAloud(userInitiated=false, prefetchRequest=null)`，此时
 * [NovelAudioPreparationPolicy.shouldPrepare] 必然返回 false，不会发起准备。
 * 播放服务随后读本地计划，若 AUTO 预取未覆盖该章，就会停在「准备中」且永远
 * 收不到准备完成事件——静默卡死。因此阻塞时必须主动补一次准备。
 */
class NovelAudioContinuationPolicyTest {

    @Test
    fun `a missing plan during continuation triggers preparation`() {
        assertTrue(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = true,
                hasCurrentWork = true
            )
        )
    }

    @Test
    fun `a not ready plan during continuation triggers preparation`() {
        assertTrue(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = true,
                hasCurrentWork = true
            )
        )
    }

    @Test
    fun `without a playback authorization nothing is prepared`() {
        // 没有有效 Work 说明这不是用户发起的播放会话，不得借机发起云调用。
        assertFalse(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = true,
                hasCurrentWork = false
            )
        )
    }

    @Test
    fun `other block reasons never trigger preparation`() {
        // 损坏、校验失败等应走失效与报错路径，重新准备会掩盖问题并重复扣额度。
        assertFalse(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = false,
                hasCurrentWork = true
            )
        )
        assertFalse(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = false,
                hasCurrentWork = false
            )
        )
    }

    @Test
    fun `an in-flight preparation for the same chapter is not restarted`() {
        // 真机实测：开播后服务端立即查到「计划未就绪」又补发准备，取消了进行中的分析
        // 并重发一次，同一章分析被请求两次，第二次撞单生成槽返回「服务繁忙」。
        assertFalse(
            NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                missingOrNotReady = true,
                hasCurrentWork = true,
                alreadyPreparing = true
            )
        )
    }
}
