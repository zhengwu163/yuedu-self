package io.legado.app.help.readaloud.server

import androidx.annotation.Keep

// v1 wire models；不承载模型名称、服务器磁盘路径或凭据。
@Keep
data class ServerHealth(
    val status: String = "",
    val apiVersion: String = "",
    val directorReady: Boolean = false,
    val ttsReady: Boolean = false,
    // 服务端声明会产生计费的操作；旧服务不声明时按全部计费处理。
    val meteredOperations: List<String> = ALL_METERED_OPERATIONS
)

const val METERED_ANALYSIS = "analysis"
const val METERED_TTS = "tts"
val ALL_METERED_OPERATIONS = listOf(METERED_ANALYSIS, METERED_TTS)

@Keep
data class AnalysisUnit(val unitId: String = "", val text: String = "")

@Keep
data class AnalysisCharacter(
    val characterId: String = "",
    val displayName: String = "",
    val stableAliases: List<String> = emptyList()
)

@Keep
data class SpeakerAssignment(val unitId: String = "", val speakerId: String = "")

@Keep
data class PreviousContext(val recentAssignments: List<SpeakerAssignment> = emptyList())

@Keep
data class ChapterAnalysisRequest(
    val bookId: String = "",
    val chapterId: String = "",
    val textHash: String = "",
    val analysisVersion: String = "1",
    val characters: List<AnalysisCharacter> = emptyList(),
    val units: List<AnalysisUnit> = emptyList(),
    val previousContext: PreviousContext = PreviousContext()
)

@Keep
data class VoicePersona(val traits: List<String> = emptyList())

@Keep
data class NewCharacter(
    val temporaryId: String = "",
    val displayName: String = "",
    val gender: String = "",
    val ageRange: String = "",
    val voicePersona: VoicePersona = VoicePersona()
)

@Keep
data class AliasUpdate(val characterId: String = "", val stableAliases: List<String> = emptyList())

@Keep
data class ChapterAnalysisResponse(
    val assignments: List<SpeakerAssignment> = emptyList(),
    val newCharacters: List<NewCharacter> = emptyList(),
    val aliasUpdates: List<AliasUpdate> = emptyList()
)

@Keep
data class VoiceAsset(
    val voiceAssetId: String = "",
    val displayName: String = "",
    val gender: String = "",
    val ageRange: String = "",
    val traits: List<String> = emptyList(),
    val previewAvailable: Boolean = false
)

@Keep
data class VoiceConstraints(val gender: String = "", val ageRange: String = "")

@Keep
data class VoiceMatchRequest(
    val voicePersona: VoicePersona = VoicePersona(),
    val alreadyUsedVoiceIds: List<String> = emptyList(),
    val optionalConstraints: VoiceConstraints? = null
)

@Keep
data class SynthesisRequest(
    val text: String = "",
    val voiceAssetId: String = "",
    val language: String = "zh-CN",
    val speed: Double = 1.0
)

/** 仅表示传输成功；解码/完整章校验通过后才能标记可离线。 */
class SynthesizedAudio(val bytes: ByteArray, val contentType: String, val ttsProfile: String)
