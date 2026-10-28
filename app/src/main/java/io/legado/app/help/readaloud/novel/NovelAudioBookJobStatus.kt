package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobImportStates
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.readaloud.server.BookJobChapterStates
import io.legado.app.help.readaloud.server.BookJobDetail
import kotlin.math.roundToInt

/** 书籍详情里「整本生成」的文案；纯函数，便于对照验收描述。 */
object NovelAudioBookJobStatus {

    /** 约 48 kbps Opus：每小时音频约 22 MB。 */
    private const val MB_PER_AUDIO_HOUR = 22.0
    /** 中文朗读约每分钟 250 字。 */
    private const val CHARS_PER_AUDIO_MINUTE = 250.0

    fun confirmMessage(chapterCount: Int, currentIndex: Int, totalChars: Long?): String {
        val lines = mutableListOf(
            "共 $chapterCount 章，从第 ${currentIndex.coerceAtLeast(0) + 1} 章开始，到书末后再从第 1 章补齐。",
            "上传正文时需要连着家里的 Wi‑Fi，传完即可离开；电脑会继续生成，回家后自动下载到手机。",
            "生成期间电脑不接受逐章生成请求。"
        )
        if (totalChars != null && totalChars > 0) {
            val hours = totalChars / CHARS_PER_AUDIO_MINUTE / 60.0
            lines += "预计音频约 ${formatHours(hours)}，占用手机空间约 ${(hours * MB_PER_AUDIO_HOUR).roundToInt()} MB。"
        }
        return lines.joinToString("\n")
    }

    fun progress(
        job: NovelAudioBookJobEntity,
        rows: List<NovelAudioBookJobChapterEntity>,
        host: BookJobDetail?
    ): String {
        val total = rows.size
        val uploaded = rows.count { it.uploadState == NovelAudioBookJobUploadStates.UPLOADED }
        val missing = rows.count { it.uploadState == NovelAudioBookJobUploadStates.MISSING }
        val imported = rows.count { it.importState == NovelAudioBookJobImportStates.IMPORTED }
        val stale = rows.count { it.importState == NovelAudioBookJobImportStates.STALE }
        val lines = mutableListOf(stateText(job.state))
        lines += "已上传 $uploaded / $total 章" + if (missing > 0) "（$missing 章暂时取不到正文）" else ""
        if (host == null) {
            lines += "电脑：暂时连不上（不在家或电脑未开）"
        } else {
            val done = host.chapters.count {
                it.state == BookJobChapterStates.DONE || it.state == BookJobChapterStates.PURGED
            }
            val failed = host.chapters.count { it.state == BookJobChapterStates.FAILED }
            lines += "电脑已生成 $done 章" + if (failed > 0) "，$failed 章失败（点「继续」会重试）" else ""
            host.recentChapterSeconds?.let { seconds ->
                val remaining = host.chapters.count {
                    it.state == BookJobChapterStates.PENDING || it.state == BookJobChapterStates.ANALYZED
                }
                if (remaining > 0) lines += "按最近速度，剩余约 ${formatHours(remaining * seconds / 3600.0)}"
            }
        }
        lines += "已下载到手机 $imported 章" + if (stale > 0) "，$stale 章正文已变化需重新生成" else ""
        return lines.joinToString("\n")
    }

    private fun stateText(state: String) = when (state) {
        NovelAudioBookJobStates.UPLOADING -> "正在上传正文"
        NovelAudioBookJobStates.UPLOADED -> "正文已全部交给电脑"
        NovelAudioBookJobStates.PAUSED -> "已暂停"
        NovelAudioBookJobStates.CANCELLED -> "已取消"
        NovelAudioBookJobStates.DONE -> "已完成"
        else -> state
    }

    private fun formatHours(hours: Double): String = when {
        hours < 1.0 -> "${(hours * 60).roundToInt().coerceAtLeast(1)} 分钟"
        else -> "%.1f 小时".format(hours)
    }
}
