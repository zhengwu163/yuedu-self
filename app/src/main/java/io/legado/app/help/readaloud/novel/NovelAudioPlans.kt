package io.legado.app.help.readaloud.novel

import android.os.Parcelable
import androidx.annotation.Keep
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.analysis.TextUnitParser
import kotlinx.parcelize.Parcelize
import java.util.Collections

@Keep
@Parcelize
data class NovelAudioTextRange(
    val paragraphIndex: Int = 0,
    val start: Int = 0,
    val end: Int = 0
) : Parcelable

@Keep
@Parcelize
data class NovelAudioSegmentIntent(
    val orderedRanges: List<NovelAudioTextRange> = emptyList(),
    val text: String = "",
    val speakerId: Long = 0L,
    val voiceAssetId: String = "",
    val bindingRevision: Long = 0L,
    val language: String = "zh-CN",
    val speed: Double = 1.0,
    val segmentId: String = ""
) : Parcelable {
    init {
        require(speakerId >= 0L)
        require(speed.isFinite() && speed > 0.0)
    }

    override fun toString(): String {
        return "NovelAudioSegmentIntent(segmentId=$segmentId, textLength=${text.length}, " +
            "rangeCount=${orderedRanges.size}, speakerId=$speakerId, " +
            "voiceAssetId=$voiceAssetId, bindingRevision=$bindingRevision, " +
            "language=$language, speed=$speed)"
    }

    companion object {
        fun create(
            orderedRanges: List<NovelAudioTextRange>,
            text: String,
            speakerId: Long = 0L,
            voiceAssetId: String = "",
            bindingRevision: Long = 0L,
            language: String = "zh-CN",
            speed: Double = 1.0
        ): NovelAudioSegmentIntent {
            val frozenRanges = Collections.unmodifiableList(orderedRanges.toList())
            val id = NovelAudioIdentity.storageKey(
                text,
                frozenRanges.joinToString(";") { "${it.paragraphIndex},${it.start},${it.end}" },
                speakerId.toString(),
                voiceAssetId,
                bindingRevision.toString(),
                language,
                speed.toString()
            ).let { "segment:$it" }
            return NovelAudioSegmentIntent(
                orderedRanges = frozenRanges,
                text = text,
                speakerId = speakerId,
                voiceAssetId = voiceAssetId,
                bindingRevision = bindingRevision,
                language = language,
                speed = speed,
                segmentId = id
            )
        }
    }
}

@Keep
@Parcelize
data class NovelAudioChapterPlan(
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
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {
    val playableSegments: List<NovelAudioSegmentIntent>
        get() = segments.filter { it.text.isNotBlank() }

    init {
        require(planId.isNotBlank())
        require(workKey.isNotBlank())
        require(physicalBookUrl.isNotBlank())
        require(chapterIndex >= 0)
        require(generation >= 0)
    }

    override fun toString(): String {
        return "NovelAudioChapterPlan(planId=$planId, workKey=$workKey, " +
            "physicalBookUrl=$physicalBookUrl, chapterIndex=$chapterIndex, " +
            "serverScope=$serverScope, generation=$generation, " +
            "snapshotHash=$snapshotHash, rulesVersion=$rulesVersion, " +
            "analysisVersion=$analysisVersion, segmentCount=${segments.size})"
    }
}

object NovelAudioPlanFactory {

    fun create(
        snapshot: NovelAudioChapterSnapshot,
        serverScope: String,
        generation: Long,
        parsedUnits: List<ParsedTextUnit> = snapshot.parseUnits(),
        speakerIds: Map<String, Long> = emptyMap(),
        voiceBindings: Map<Long, NovelAudioVoiceBinding> = emptyMap(),
        language: String = "zh-CN",
        speed: Double = 1.0
    ): NovelAudioChapterPlan {
        val segments = parsedUnits.map { unit ->
            val speakerId = if (unit.roleType == "narrator") {
                0L
            } else {
                speakerIds[unit.unitId]
                    ?: throw IllegalArgumentException(
                        "missing formal speaker id for unit ${unit.unitId}"
                    )
            }
            val binding = voiceBindings[speakerId]
            NovelAudioSegmentIntent.create(
                orderedRanges = unit.ranges.map {
                    NovelAudioTextRange(it.paragraphIndex, it.start, it.end)
                },
                text = unit.text,
                speakerId = speakerId,
                voiceAssetId = binding?.voiceAssetId.orEmpty(),
                bindingRevision = binding?.revision ?: 0L,
                language = language,
                speed = speed
            )
        }
        return NovelAudioChapterPlan(
            planId = NovelAudioIdentity.storageKey(
                // A frozen plan also owns its server and voices. Rebinding a
                // backend or playback setting must not reuse another plan's rows.
                snapshot.workKey,
                snapshot.chapter.chapterKey,
                serverScope,
                generation.toString(),
                snapshot.snapshotHash,
                snapshot.rulesVersion,
                TextUnitParser.ANALYSIS_VERSION,
                segments.joinToString(";") { it.segmentId }
            ).let { "plan:$it" },
            workKey = snapshot.workKey,
            physicalBookUrl = snapshot.chapter.physicalBookUrl,
            chapterIndex = snapshot.chapter.index,
            chapterUrl = snapshot.chapter.url,
            serverScope = serverScope,
            generation = generation,
            snapshotHash = snapshot.snapshotHash,
            rulesVersion = snapshot.rulesVersion,
            analysisVersion = TextUnitParser.ANALYSIS_VERSION,
            segments = Collections.unmodifiableList(segments)
        )
    }
}
