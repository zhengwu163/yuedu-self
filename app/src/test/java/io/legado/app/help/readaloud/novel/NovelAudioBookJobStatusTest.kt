package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobImportStates
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.readaloud.server.BookJobChapterState
import io.legado.app.help.readaloud.server.BookJobDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioBookJobStatusTest {
    @Test fun confirmMentionsOrderWifiAndSpaceWhenLengthIsKnown() {
        val message = NovelAudioBookJobStatus.confirmMessage(300, 41, totalChars = 1_500_000)
        assertTrue(message.contains("共 300 章，从第 42 章开始"))
        assertTrue(message.contains("Wi‑Fi"))
        // 150 万字 ≈ 100 小时 ≈ 2200 MB
        assertTrue(message.contains("100.0 小时"))
        assertTrue(message.contains("2200 MB"))
        assertFalse(NovelAudioBookJobStatus.confirmMessage(3, 0, null).contains("MB"))
    }

    @Test fun progressCountsEachStageAndRemainingTime() {
        val job = NovelAudioBookJobEntity(jobId = "j", state = NovelAudioBookJobStates.UPLOADED)
        val rows = listOf(
            row(0, NovelAudioBookJobUploadStates.UPLOADED, NovelAudioBookJobImportStates.IMPORTED),
            row(1, NovelAudioBookJobUploadStates.UPLOADED, NovelAudioBookJobImportStates.NONE),
            row(2, NovelAudioBookJobUploadStates.MISSING, NovelAudioBookJobImportStates.NONE),
        )
        val host = BookJobDetail("j", "running", true, 3, 1800.0, listOf(
            BookJobChapterState("c0", 0, "h", "done", null, true),
            BookJobChapterState("c1", 1, "h", "pending", null, false),
        ))
        val text = NovelAudioBookJobStatus.progress(job, rows, host)
        assertEquals(listOf(
            "正文已全部交给电脑",
            "已上传 2 / 3 章（1 章暂时取不到正文）",
            "电脑已生成 1 章",
            "按最近速度，剩余约 30 分钟",
            "已下载到手机 1 章"
        ), text.lines())
        assertTrue(NovelAudioBookJobStatus.progress(job, rows, null).contains("暂时连不上"))
    }

    private fun row(index: Int, upload: String, import: String) = NovelAudioBookJobChapterEntity(
        jobId = "j", chapterIndex = index, chapterId = "c$index", position = index,
        uploadState = upload, importState = import
    )
}
