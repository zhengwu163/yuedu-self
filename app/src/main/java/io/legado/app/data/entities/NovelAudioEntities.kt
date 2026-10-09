package io.legado.app.data.entities

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.utils.GSON
import kotlinx.parcelize.Parcelize

object NovelAudioRetention {
    const val PINNED = "PINNED"
    const val AUTO = "AUTO"
}

object NovelAudioStates {
    const val PLANNED = "PLANNED"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val READY = "READY"
    const val PARTIAL = "PARTIAL"
    const val WAITING_NETWORK = "WAITING_NETWORK"
    const val PAUSED = "PAUSED"
    const val CANCELLED = "CANCELLED"
    const val EXPIRED = "EXPIRED"
    const val FAILED = "FAILED"
    const val MERGED = "MERGED"
}

object NovelAudioMergeStates {
    const val ACTIVE = "ACTIVE"
    const val REVOKED = "REVOKED"
}

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_aliases",
    primaryKeys = ["workKey", "normalizedAlias"],
    indices = [Index(value = ["workKey", "characterId"])]
)
data class NovelAudioAliasEntity(
    @ColumnInfo(defaultValue = "")
    val workKey: String = "",
    @ColumnInfo(defaultValue = "0")
    val characterId: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val alias: String = "",
    @ColumnInfo(defaultValue = "")
    val normalizedAlias: String = "",
    @ColumnInfo(defaultValue = "server")
    val source: String = "server",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_voice_bindings",
    primaryKeys = ["scope", "characterId"],
    indices = [Index(value = ["scope", "voiceAssetId"])]
)
data class NovelAudioVoiceBindingEntity(
    @ColumnInfo(defaultValue = "")
    val scope: String = "",
    @ColumnInfo(defaultValue = "0")
    val characterId: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val voiceAssetId: String = "",
    @ColumnInfo(defaultValue = "0")
    val revision: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_chapter_plans",
    indices = [
        Index(value = ["physicalBookUrl", "chapterIndex", "generation"]),
        Index(value = ["workKey", "chapterIndex"])
    ]
)
data class NovelAudioChapterPlanEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    val planId: String = "",
    @ColumnInfo(defaultValue = "")
    val workKey: String = "",
    @ColumnInfo(defaultValue = "")
    val physicalBookUrl: String = "",
    @ColumnInfo(defaultValue = "0")
    val chapterIndex: Int = 0,
    @ColumnInfo(defaultValue = "")
    val chapterUrl: String = "",
    @ColumnInfo(defaultValue = "")
    val scope: String = "",
    @ColumnInfo(defaultValue = "0")
    val generation: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val executionAttempt: Long = 0L,
    @ColumnInfo(defaultValue = NovelAudioStates.PLANNED)
    val state: String = NovelAudioStates.PLANNED,
    @ColumnInfo(defaultValue = NovelAudioRetention.AUTO)
    val retention: String = NovelAudioRetention.AUTO,
    @ColumnInfo(defaultValue = "")
    val snapshotHash: String = "",
    @ColumnInfo(defaultValue = "")
    val rulesVersion: String = "",
    @ColumnInfo(defaultValue = "")
    val planJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0,
    @ColumnInfo(defaultValue = "")
    val stateReason: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_segment_artifacts",
    primaryKeys = ["planId", "segmentId"],
    indices = [Index(value = ["planId", "state"])]
)
data class NovelAudioSegmentArtifactEntity(
    @ColumnInfo(defaultValue = "")
    val planId: String = "",
    @ColumnInfo(defaultValue = "")
    val segmentId: String = "",
    @ColumnInfo(defaultValue = "")
    val ttsProfile: String = "",
    @ColumnInfo(defaultValue = "")
    val contentType: String = "",
    @ColumnInfo(defaultValue = "")
    val sha256: String = "",
    @ColumnInfo(defaultValue = "0")
    val size: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val path: String = "",
    @ColumnInfo(defaultValue = "PENDING")
    val state: String = "PENDING",
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_download_tasks",
    indices = [
        Index(value = ["physicalBookUrl", "chapterIndex"]),
        Index(value = ["planId", "generation"]),
        Index(value = ["retention", "state"])
    ]
)
data class NovelAudioDownloadTaskEntity(
    @PrimaryKey
    @ColumnInfo(defaultValue = "")
    val taskId: String = "",
    @ColumnInfo(defaultValue = "")
    val planId: String = "",
    @ColumnInfo(defaultValue = "")
    val physicalBookUrl: String = "",
    @ColumnInfo(defaultValue = "0")
    val chapterIndex: Int = 0,
    @ColumnInfo(defaultValue = "")
    val scope: String = "",
    @ColumnInfo(defaultValue = "0")
    val generation: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val executionAttempt: Long = 0L,
    @ColumnInfo(defaultValue = NovelAudioStates.QUEUED)
    val state: String = NovelAudioStates.QUEUED,
    @ColumnInfo(defaultValue = NovelAudioRetention.AUTO)
    val retention: String = NovelAudioRetention.AUTO,
    @ColumnInfo(defaultValue = "")
    val taskJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val progress: Int = 0,
    @ColumnInfo(defaultValue = "")
    val stateReason: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

@Keep
@Parcelize
@Entity(
    tableName = "novel_audio_merge_records",
    indices = [
        Index(value = ["workKey", "createdAt"]),
        Index(value = ["workKey", "state"])
    ]
)
data class NovelAudioMergeRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val workKey: String = "",
    @ColumnInfo(defaultValue = "0")
    val primaryCharacterId: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    val absorbedCharacterId: Long = 0L,
    @ColumnInfo(defaultValue = "")
    val primaryNameBefore: String = "",
    @ColumnInfo(defaultValue = "")
    val absorbedNameBefore: String = "",
    @ColumnInfo(defaultValue = "[]")
    val primaryAliasesBeforeJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val absorbedAliasesBeforeJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val voiceBindingsBeforeJson: String = "[]",
    @ColumnInfo(defaultValue = NovelAudioMergeStates.ACTIVE)
    val state: String = NovelAudioMergeStates.ACTIVE,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val revokedAt: Long = 0L
) : Parcelable

object NovelAudioPlanJson {
    fun encode(plan: NovelAudioChapterPlan): String = GSON.toJson(plan)

    fun decode(json: String): NovelAudioChapterPlan? {
        return kotlin.runCatching {
            // 计划类的 init 会校验 planId 等必填项；Gson 优先走全默认值的无参构造，
            // 在填字段前就触发校验失败。先解到无校验的载体，再经主构造器完成校验。
            val raw = GSON.fromJson(json, StoredPlan::class.java)
            NovelAudioChapterPlan(
                planId = raw.planId,
                workKey = raw.workKey,
                physicalBookUrl = raw.physicalBookUrl,
                chapterIndex = raw.chapterIndex,
                chapterUrl = raw.chapterUrl,
                serverScope = raw.serverScope,
                generation = raw.generation,
                snapshotHash = raw.snapshotHash,
                rulesVersion = raw.rulesVersion,
                analysisVersion = raw.analysisVersion,
                segments = raw.segments,
                createdAt = raw.createdAt
            )
        }.getOrNull()
    }

    @Keep
    private class StoredPlan(
        val planId: String = "",
        val workKey: String = "",
        val physicalBookUrl: String = "",
        val chapterIndex: Int = 0,
        val chapterUrl: String = "",
        val serverScope: String = "",
        val generation: Long = 0L,
        val snapshotHash: String = "",
        val rulesVersion: String = "",
        val analysisVersion: String = "1",
        val segments: List<NovelAudioSegmentIntent> = emptyList(),
        val createdAt: Long = 0L
    )
}
