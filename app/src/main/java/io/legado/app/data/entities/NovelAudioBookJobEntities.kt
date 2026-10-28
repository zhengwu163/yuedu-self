package io.legado.app.data.entities

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 手机端整本任务状态：上传中 → 已上传（等电脑生成并同步）→ 完成；可暂停或取消。 */
object NovelAudioBookJobStates {
    const val UPLOADING = "UPLOADING"
    const val UPLOADED = "UPLOADED"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
    const val DONE = "DONE"
    val ACTIVE = setOf(UPLOADING, UPLOADED, PAUSED)
}

object NovelAudioBookJobUploadStates {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    /** 正文取不到（未缓存或书源失败）；之后重新上传时再试。 */
    const val MISSING = "MISSING"
}

object NovelAudioBookJobImportStates {
    const val NONE = "NONE"
    const val IMPORTED = "IMPORTED"
    /** 导入时正文已与上传时不同，该章需要重新生成。 */
    const val STALE = "STALE"
    const val FAILED = "FAILED"
}

@Keep
@Entity(
    tableName = "novel_audio_book_jobs",
    indices = [Index(value = ["physicalBookUrl"]), Index(value = ["state"])]
)
data class NovelAudioBookJobEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    val jobId: String = "",
    /** 生成音色绑定所用的服务端身份，与逐章模式的 scope 相同。 */
    @ColumnInfo(defaultValue = "")
    val serverScope: String = "",
    @ColumnInfo(defaultValue = "")
    val workKey: String = "",
    @ColumnInfo(defaultValue = "")
    val physicalBookUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val title: String = "",
    @ColumnInfo(defaultValue = NovelAudioBookJobStates.UPLOADING)
    val state: String = NovelAudioBookJobStates.UPLOADING,
    @ColumnInfo(defaultValue = "0")
    val maxSegmentChars: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val chapterCount: Int = 0,
    @ColumnInfo(defaultValue = "")
    val stateReason: String = "",
    @ColumnInfo(defaultValue = "0")
    val lastSyncAt: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
)

@Keep
@Entity(
    tableName = "novel_audio_book_job_chapters",
    primaryKeys = ["jobId", "chapterIndex"],
    indices = [Index(value = ["jobId", "position"])]
)
data class NovelAudioBookJobChapterEntity(
    @ColumnInfo(defaultValue = "")
    val jobId: String = "",
    @ColumnInfo(defaultValue = "0")
    val chapterIndex: Int = 0,
    /** 发给电脑的章节 ID：章节 key 的散列，可直接放进 URL 路径。 */
    @ColumnInfo(defaultValue = "")
    val chapterId: String = "",
    /** 上传顺序：当前章 → 书末 → 第 1 章 → 当前章前一章。 */
    @ColumnInfo(defaultValue = "0")
    val position: Int = 0,
    @ColumnInfo(defaultValue = "")
    val snapshotHash: String = "",
    @ColumnInfo(defaultValue = NovelAudioBookJobUploadStates.PENDING)
    val uploadState: String = NovelAudioBookJobUploadStates.PENDING,
    @ColumnInfo(defaultValue = NovelAudioBookJobImportStates.NONE)
    val importState: String = NovelAudioBookJobImportStates.NONE,
    @ColumnInfo(defaultValue = "")
    val planId: String = "",
    @ColumnInfo(defaultValue = "")
    val failure: String = "",
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
)
