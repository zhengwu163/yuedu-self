package io.legado.app.help.readaloud.novel

import com.google.gson.Gson
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.server.AnalysisCharacter
import io.legado.app.help.readaloud.server.AnalysisUnit
import io.legado.app.help.readaloud.server.ChapterAnalysisRequest
import io.legado.app.help.readaloud.server.ChapterAnalysisResponse
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.PreviousContext
import io.legado.app.help.readaloud.server.SpeakerAssignment
import io.legado.app.help.readaloud.server.VoiceAsset
import io.legado.app.help.readaloud.server.VoiceMatchRequest
import io.legado.app.help.readaloud.server.VoicePersona

class NovelAudioAnalysisException(message: String) : IllegalStateException(message)

class NovelAudioAnalysisCoordinator(
    private val analyze: suspend (ChapterAnalysisRequest) -> ChapterAnalysisResponse,
    private val registry: CharacterRegistry? = null,
    private val voices: (suspend () -> List<VoiceAsset>)? = null,
    private val match: (suspend (VoiceMatchRequest) -> List<VoiceAsset>)? = null,
    private val analyzeWithLease: (suspend (
        ChapterAnalysisRequest,
        String
    ) -> ChapterAnalysisResponse)? = null,
    private val leaseId: String? = null,
    /** 0 表示服务端未声明上限，按解析单元整段合成。 */
    private val maxSegmentChars: Int = 0
) {

    /** 返回绑定同一 Runtime Lease 的分析器，避免调用方逐请求拼接 header。 */
    fun withLease(leaseId: String, maxSegmentChars: Int = 0): NovelAudioAnalysisCoordinator {
        require(leaseId.isNotBlank())
        require(maxSegmentChars >= 0)
        return NovelAudioAnalysisCoordinator(
            analyze = analyze,
            registry = registry,
            voices = voices,
            match = match,
            analyzeWithLease = analyzeWithLease,
            leaseId = leaseId,
            maxSegmentChars = maxSegmentChars
        )
    }

    suspend fun analyze(
        snapshot: NovelAudioChapterSnapshot,
        scope: String,
        generation: Long = 0L,
        existingCharacters: List<BookCharacter> = emptyList(),
        parsedUnits: List<ParsedTextUnit> = snapshot.parseUnits(),
        existingBindings: List<NovelAudioVoiceBinding> = emptyList()
    ): NovelAudioChapterPlan {
        require(scope.isNotBlank())
        require(generation >= 0L)
        val nonBlank = parsedUnits.filter { it.text.isNotBlank() }
        val batches = partition(nonBlank)
        val assignmentByUnit = linkedMapOf<String, String>()
        val knownCharacters = existingCharacters.toMutableList()
        val temporaryToFormal = mutableMapOf<String, Long>()
        var previousAssignments = emptyList<SpeakerAssignment>()

        batches.forEach { batch ->
            val request = buildRequest(
                snapshot = snapshot,
                scope = scope,
                existingCharacters = knownCharacters,
                units = batch,
                previousAssignments = previousAssignments
            )
            val response = if (leaseId == null) {
                analyze(request)
            } else {
                analyzeWithLease?.invoke(request, leaseId)
                    ?: throw NovelAudioAnalysisException(
                        "lease-aware analysis is not configured"
                    )
            }
            validateResponse(request, response, knownCharacters)

            val resolved = if (response.newCharacters.isNotEmpty() || response.aliasUpdates.isNotEmpty()) {
                val characterRegistry = registry
                    ?: throw NovelAudioAnalysisException(
                        "server introduced characters but no character registry is configured"
                    )
                characterRegistry.resolve(
                    workKey = snapshot.workKey,
                    newCharacters = response.newCharacters,
                    aliasUpdates = response.aliasUpdates
                )
            } else {
                CharacterRegistryResult()
            }
            temporaryToFormal.putAll(resolved.temporaryToCharacterId)
            knownCharacters += resolved.created

            response.assignments.forEach { assignment ->
                val formalId = when {
                    assignment.speakerId == "narrator" -> "0"
                    assignment.speakerId.toLongOrNull() != null -> assignment.speakerId
                    else -> temporaryToFormal[assignment.speakerId]?.toString()
                        ?: throw NovelAudioAnalysisException("unresolved temporary character")
                }
                assignmentByUnit[assignment.unitId] = formalId
            }
            previousAssignments = (previousAssignments + response.assignments)
                .map { assignment ->
                    assignment.copy(
                        speakerId = when {
                            assignment.speakerId == "narrator" -> "narrator"
                            assignment.speakerId.toLongOrNull() != null -> assignment.speakerId
                            else -> temporaryToFormal[assignment.speakerId]?.toString()
                                ?: throw NovelAudioAnalysisException("unresolved context character")
                        }
                    )
                }
                .takeLast(MAX_PREVIOUS_ASSIGNMENTS)
        }

        val bindings = bindVoices(
            scope = scope,
            speakerIds = assignmentByUnit.values.mapNotNull { it.toLongOrNull() }.distinct(),
            existingBindings = existingBindings
        )
        val normalizedUnits = parsedUnits.map { unit ->
            unit.copy(characterName = assignmentByUnit[unit.unitId].orEmpty())
        }
        val speakerIds = normalizedUnits.associate { unit ->
            unit.unitId to (assignmentByUnit[unit.unitId]?.toLongOrNull() ?: 0L)
        }
        return NovelAudioPlanFactory.create(
            snapshot = snapshot,
            serverScope = scope,
            generation = generation,
            parsedUnits = normalizedUnits,
            speakerIds = speakerIds,
            voiceBindings = bindings,
            maxSegmentChars = maxSegmentChars
        )
    }

    private suspend fun bindVoices(
        scope: String,
        speakerIds: List<Long>,
        existingBindings: List<NovelAudioVoiceBinding>
    ): Map<Long, NovelAudioVoiceBinding> {
        if (voices == null && match == null) return emptyMap()
        val allVoices = voices?.invoke().orEmpty()
        val narratorVoices = allVoices.filter { it.narrator }
        val result = mutableMapOf<Long, NovelAudioVoiceBinding>()
        val orderedSpeakers = listOf(0L) + speakerIds.filter { it != 0L }
        orderedSpeakers.forEach { speakerId ->
            val existing = existingBindings + result.values
            // Local match deliberately excludes reserved narrator assets; do not consume a dialogue voice.
            // An older server without this optional metadata keeps its existing matching behavior.
            val candidates = if (speakerId == 0L && narratorVoices.isNotEmpty()) {
                narratorVoices
            } else if (match != null) {
                match.invoke(
                    VoiceMatchRequest(
                        voicePersona = VoicePersona(),
                        alreadyUsedVoiceIds = existing.map { it.voiceAssetId }.distinct()
                    )
                )
            } else {
                allVoices
            }
            val binding = NovelAudioVoiceBindingRegistry.bind(
                scope = scope,
                characterId = speakerId,
                existing = existing,
                candidates = candidates
            )
            result[speakerId] = binding
        }
        return result
    }

    private fun partition(units: List<ParsedTextUnit>): List<List<ParsedTextUnit>> {
        val batches = mutableListOf<MutableList<ParsedTextUnit>>()
        var current = mutableListOf<ParsedTextUnit>()
        var currentLength = 0
        units.forEach { unit ->
            require(unit.text.length <= MAX_TEXT_UTF16) {
                "one text unit exceeds the server UTF-16 budget"
            }
            val wouldExceed = current.isNotEmpty() &&
                (current.size >= MAX_UNITS_PER_BATCH ||
                    currentLength + unit.text.length > MAX_TEXT_UTF16)
            if (wouldExceed) {
                batches += current
                current = mutableListOf()
                currentLength = 0
            }
            current += unit
            currentLength += unit.text.length
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    private fun buildRequest(
        snapshot: NovelAudioChapterSnapshot,
        scope: String,
        existingCharacters: List<BookCharacter>,
        units: List<ParsedTextUnit>,
        previousAssignments: List<SpeakerAssignment>
    ): ChapterAnalysisRequest {
        val normalizedCharacters = existingCharacters.takeLast(MAX_CHARACTERS).map { character ->
            AnalysisCharacter(
                characterId = character.id.toString(),
                displayName = character.name,
                stableAliases = listOf(character.name).take(MAX_ALIASES_PER_CHARACTER)
                    .map { it.take(MAX_ALIAS_LENGTH) }
            )
        }
        val normalizedUnits = units.map { AnalysisUnit(it.unitId, it.text) }
        val contextCandidates = (MAX_CHARACTERS downTo 0).map { count ->
            normalizedCharacters.takeLast(count)
        }
        val previousCandidates = (MAX_PREVIOUS_ASSIGNMENTS downTo 0).map { count ->
            previousAssignments.takeLast(count)
        }
        for (characters in contextCandidates) {
            for (previous in previousCandidates) {
                val request = ChapterAnalysisRequest(
                    bookId = snapshot.workKey,
                    chapterId = snapshot.chapter.chapterKey,
                    textHash = snapshot.textSnapshot.textHash,
                    analysisVersion = "1",
                    characters = characters,
                    units = normalizedUnits,
                    previousContext = PreviousContext(previous)
                )
                if (Gson().toJson(request).toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
                    return request
                }
            }
        }
        throw NovelAudioAnalysisException("analysis request exceeds JSON budget")
    }

    private fun validateResponse(
        request: ChapterAnalysisRequest,
        response: ChapterAnalysisResponse,
        knownCharacters: List<BookCharacter>
    ) {
        val expected = request.units.map { it.unitId }.toSet()
        val actual = response.assignments.map { it.unitId }
        require(actual.size == expected.size && actual.toSet() == expected) {
            "analysis response must cover each unit exactly once"
        }
        val known = knownCharacters.map { it.id.toString() }.toSet() + "narrator"
        val temporary = response.newCharacters.map { it.temporaryId }
        require(temporary.size == temporary.toSet().size && temporary.none { it in known }) {
            "analysis response contains duplicate or colliding temporary ids"
        }
        val allowed = known + temporary
        require(response.assignments.all { it.speakerId in allowed }) {
            "analysis response contains an unknown speaker"
        }
        require(response.aliasUpdates.all { it.characterId in allowed && it.characterId != "narrator" }) {
            "analysis response contains an unknown alias owner"
        }
        require(response.newCharacters.all { it.displayName.isNotBlank() }) {
            "analysis response contains a blank character name"
        }
    }

    companion object {
        const val MAX_UNITS_PER_BATCH = 64
        const val MAX_TEXT_UTF16 = 4000
        const val MAX_JSON_BYTES = 32 * 1024
        const val MAX_CHARACTERS = 64
        const val MAX_ALIASES_PER_CHARACTER = 32
        const val MAX_ALIAS_LENGTH = 128
        const val MAX_PREVIOUS_ASSIGNMENTS = 32
    }
}
