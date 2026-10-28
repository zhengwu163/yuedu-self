package io.legado.app.help.readaloud.server

import androidx.annotation.Keep

/** 整本生成（/v1/book-jobs）的请求与响应；字段名与电脑端 book_jobs_api.py 一致。 */
@Keep
data class BookJobCharacter(val characterId: String, val displayName: String, val gender: String)

@Keep
data class BookJobAlias(val characterId: String, val alias: String, val normalizedAlias: String)

@Keep
data class BookJobMerge(val absorbedCharacterId: String, val primaryCharacterId: String)

@Keep
data class BookJobBinding(val characterId: String, val voiceAssetId: String, val revision: Long)

@Keep
data class BookJobCreateRequest(
    val bookId: String,
    val title: String,
    val expectedChapterCount: Int,
    val characters: List<BookJobCharacter>,
    val aliases: List<BookJobAlias>,
    val merges: List<BookJobMerge>,
    val bindings: List<BookJobBinding>
)

data class BookJobCreated(val jobId: String, val maxSegmentChars: Int, val state: String)

@Keep
data class BookJobUnit(
    val unitId: String,
    val text: String,
    val roleType: String,
    val pieces: List<String>
)

@Keep
data class BookJobChapterUpload(
    val chapterIndex: Int,
    val snapshotHash: String,
    val units: List<BookJobUnit>
)

@Keep
data class BookJobBindingsUpdate(val bindings: List<BookJobBinding>)

data class BookJobChapterState(
    val chapterId: String,
    val chapterIndex: Int,
    val snapshotHash: String,
    val state: String,
    val failure: String?,
    val downloaded: Boolean
)

data class BookJobDetail(
    val jobId: String,
    val state: String,
    val uploaded: Boolean,
    val expectedChapterCount: Int,
    /** 最近几章「分析 + 合成」的平均秒数；电脑还没完成任何一章时为 null。 */
    val recentChapterSeconds: Double?,
    val chapters: List<BookJobChapterState>
)

data class BookJobPiece(
    val index: Int,
    val textSha256: String,
    val audioId: String,
    val sha256: String,
    val size: Long,
    val contentType: String,
    val ttsProfile: String
)

data class BookJobResultUnit(val unitId: String, val speakerId: Long, val pieces: List<BookJobPiece>)

data class BookJobResultCharacter(
    val characterId: Long,
    val displayName: String,
    val gender: String,
    /** 上传时手机已有该人物；false 表示电脑在分析中新建，需要走手机的人物登记。 */
    val phoneKnown: Boolean,
    val stableAliases: List<String>
)

data class BookJobChapterResult(
    val chapterId: String,
    val chapterIndex: Int,
    val snapshotHash: String,
    val units: List<BookJobResultUnit>,
    val characters: List<BookJobResultCharacter>,
    val bindings: List<BookJobBinding>
)

object BookJobStates {
    const val UPLOADING = "uploading"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val CANCELLED = "cancelled"
    val ALL = setOf(UPLOADING, RUNNING, PAUSED, DONE, CANCELLED)
}

object BookJobChapterStates {
    const val PENDING = "pending"
    const val ANALYZED = "analyzed"
    const val DONE = "done"
    const val FAILED = "failed"
    const val PURGED = "purged"
    val ALL = setOf(PENDING, ANALYZED, DONE, FAILED, PURGED)
}
