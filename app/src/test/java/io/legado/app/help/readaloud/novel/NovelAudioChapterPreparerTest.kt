package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 单章准备的固定顺序：产出计划 → 取得执行令牌 → 下载缺段。
 *
 * 当前章与 AUTO 后续章共用这一段顺序，因此它必须能在纯 JVM 下完整验证：
 * 任一前置步骤缺失都不得进入下载，取消不得被伪装成失败。
 */
class NovelAudioChapterPreparerTest {

    private val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
        bookUrl = "https://example.test/book",
        chapterIndex = 3,
        chapterUrl = "https://example.test/chapter/3",
        strings = listOf("正文"),
        title = "测试书",
        rules = io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig().freeze()
    )

    @Test
    fun `ready download yields a ready result carrying the produced plan`() {
        val plan = plan()
        val preparer = preparer(
            produce = { plan },
            download = { _, _ -> NovelAudioDownloadCoordinator.Result.Ready }
        )

        val result = runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { true } }

        assertEquals(NovelAudioChapterPreparer.Result.Ready(plan), result)
    }

    @Test
    fun `no plan means no download attempt`() {
        var downloads = 0
        val preparer = preparer(
            produce = { null },
            download = { _, _ ->
                downloads++
                NovelAudioDownloadCoordinator.Result.Ready
            }
        )

        val result = runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { true } }

        assertNull(result)
        assertEquals(0, downloads)
    }

    @Test
    fun `a plan without an execution token never downloads`() {
        var downloads = 0
        val preparer = preparer(
            produce = { plan() },
            execution = { null },
            download = { _, _ ->
                downloads++
                NovelAudioDownloadCoordinator.Result.Ready
            }
        )

        val result = runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { true } }

        assertNull(result)
        assertEquals(0, downloads)
    }

    @Test
    fun `cancelled download is not reported as a failure`() {
        val preparer = preparer(
            produce = { plan() },
            download = { _, _ -> NovelAudioDownloadCoordinator.Result.Cancelled }
        )

        assertNull(runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { true } })
    }

    @Test
    fun `failed download keeps its reason and the requested generation`() {
        val preparer = preparer(
            produce = { plan() },
            download = { _, _ -> NovelAudioDownloadCoordinator.Result.Failed("PLAN_NOT_READY") }
        )

        val result = runBlocking { preparer.prepare(snapshot, 9L, NovelAudioRetention.AUTO) { true } }

        assertEquals(
            NovelAudioChapterPreparer.Result.Failed("PLAN_NOT_READY", 9L),
            result
        )
    }

    @Test
    fun `retention and generation reach the producer unchanged`() {
        val seen = mutableListOf<Pair<Long, String>>()
        val preparer = preparer(
            produce = { plan() },
            download = { _, _ -> NovelAudioDownloadCoordinator.Result.Ready },
            record = { generation, retention -> seen += generation to retention }
        )

        runBlocking { preparer.prepare(snapshot, 11L, NovelAudioRetention.PINNED) { true } }

        assertEquals(listOf(11L to NovelAudioRetention.PINNED), seen)
    }

    @Test
    fun `the auto guard is forwarded to the download stage`() {
        var observed: Boolean? = null
        val preparer = preparer(
            produce = { plan() },
            download = { _, isAutoAllowed ->
                observed = isAutoAllowed()
                NovelAudioDownloadCoordinator.Result.Ready
            }
        )

        runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { false } }

        assertEquals(false, observed)
    }

    @Test
    fun `cancellation during production propagates instead of becoming a failure`() {
        val preparer = preparer(
            produce = { throw CancellationException("cancelled") },
            download = { _, _ -> NovelAudioDownloadCoordinator.Result.Ready }
        )

        try {
            runBlocking { preparer.prepare(snapshot, 5L, NovelAudioRetention.AUTO) { true } }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    private fun plan() = NovelAudioChapterPlan(
        planId = "plan-3",
        workKey = "work",
        physicalBookUrl = snapshot.chapter.physicalBookUrl,
        chapterIndex = 3,
        generation = 5L
    )

    private fun preparer(
        produce: suspend () -> NovelAudioChapterPlan?,
        execution: () -> NovelAudioRepository.Execution? = {
            NovelAudioRepository.Execution(plan(), 0L, NovelAudioRetention.AUTO)
        },
        download: suspend (
            NovelAudioRepository.Execution,
            () -> Boolean
        ) -> NovelAudioDownloadCoordinator.Result,
        record: (Long, String) -> Unit = { _, _ -> }
    ) = NovelAudioChapterPreparer(
        produce = { _, generation, retention, _ ->
            record(generation, retention)
            produce()
        },
        execution = execution,
        download = download
    )
}
