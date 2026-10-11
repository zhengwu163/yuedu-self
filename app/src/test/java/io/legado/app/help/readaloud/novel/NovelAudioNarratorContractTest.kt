package io.legado.app.help.readaloud.novel

import com.google.gson.Gson
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudRoleRange
import io.legado.app.help.readaloud.server.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NovelAudioNarratorContractTest {
    private fun voice(id: String, narrator: String? = null): String =
        """{"voiceAssetId":"$id","displayName":"sample","gender":"unknown","ageRange":"adult","traits":[],"previewAvailable":true${narrator?.let { ",\"narrator\":$it" }.orEmpty()}}"""

    @Test fun `narrator metadata survives wire decoding and Gson serialization`() {
        val parsed = NovelAudioJson.voices("""{"voices":[${voice("n", "true")}] }""", "voices")
        assertTrue(Gson().toJson(parsed.single()).contains("\"narrator\":true"))
    }

    @Test fun `legacy voice catalog without narrator metadata remains readable`() {
        val parsed = NovelAudioJson.voices("""{"voices":[${voice("legacy")}] }""", "voices")
        assertEquals("legacy", parsed.single().voiceAssetId)
    }

    @Test fun `narrator metadata rejects strings numbers and null`() {
        listOf("\"true\"", "1", "null").forEach { invalid ->
            try {
                NovelAudioJson.voices("""{"voices":[${voice("n", invalid)}]}""", "voices")
                fail("invalid narrator flag must fail")
            } catch (error: NovelAudioServerException) {
                assertEquals("PROTOCOL", error.kind)
            }
        }
    }

    @Test fun `reserved narrator does not consume either dialogue voice`() = runBlocking {
        // A real local catalog advertises three voices but its matcher excludes the narrator.
        val catalog = NovelAudioJson.voices(
            """{"voices":[${voice("a", "false")},${voice("b", "false")},${voice("n", "true")}]}""",
            "voices"
        )
        val texts = listOf("sample narration", "sample first", "sample second")
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://narrator-contract", chapterIndex = 0, chapterUrl = "chapter",
            strings = texts, rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val units = texts.mapIndexed { index, text ->
            val kind = if (index == 0) "narrator" else "dialogue"
            ParsedTextUnit("u$index", kind, kind, "", listOf(ReadAloudRoleRange(index, 0, text.length)),
                text, true, 1.0, "test")
        }
        var matchCalls = 0
        val matchedGenders = mutableListOf<String>()
        val coordinator = NovelAudioAnalysisCoordinator(
            registry = CharacterRegistry(MemoryCharacterStore()),
            voices = { catalog },
            match = { request ->
                matchCalls++
                matchedGenders += request.optionalConstraints?.gender.orEmpty()
                catalog.filter { it.voiceAssetId != "n" }
                    .sortedBy { it.voiceAssetId in request.alreadyUsedVoiceIds }
            },
            analyze = {
                ChapterAnalysisResponse(
                    assignments = listOf(SpeakerAssignment("u0", "narrator"),
                        SpeakerAssignment("u1", "tmp-a"), SpeakerAssignment("u2", "tmp-b")),
                    newCharacters = listOf(NewCharacter("tmp-a", "sample-a", gender = "male"),
                        NewCharacter("tmp-b", "sample-b", gender = "female"))
                )
            }
        )
        val plan = coordinator.analyze(snapshot = snapshot, scope = "scope", parsedUnits = units)
        assertEquals("n", plan.segments.first().voiceAssetId)
        assertEquals(setOf("a", "b", "n"), plan.segments.map { it.voiceAssetId }.toSet())
        assertEquals(2, matchCalls)
        assertEquals(listOf("male", "female"), matchedGenders)
    }

    @Test fun `role bindings survive the next chapter with reversed speaker order`() = runBlocking {
        val store = MemoryCharacterStore()
        val registry = CharacterRegistry(store)
        fun snapshot(index: Int) = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://stable-roles", chapterIndex = index, chapterUrl = "chapter-$index",
            strings = listOf("sample first", "sample second"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val first = snapshot(0)
        val roles = registry.resolve(first.workKey, listOf(
            NewCharacter("tmp-a", "sample-a"), NewCharacter("tmp-b", "sample-b")
        )).created
        val catalog = listOf(VoiceAsset(voiceAssetId = "n", narrator = true),
            VoiceAsset(voiceAssetId = "a"), VoiceAsset(voiceAssetId = "b"))
        var order = roles
        val coordinator = NovelAudioAnalysisCoordinator(
            registry = registry, voices = { catalog },
            match = { request -> catalog.filter { !it.narrator }
                .sortedBy { it.voiceAssetId in request.alreadyUsedVoiceIds } },
            analyze = { request -> ChapterAnalysisResponse(assignments =
                request.units.mapIndexed { index, unit ->
                    SpeakerAssignment(unit.unitId, order[index].id.toString())
                }) }
        )
        val plan = coordinator.analyze(first, "scope", existingCharacters = roles)
        val original = plan.segments.associate { it.speakerId to it.voiceAssetId }
        assertEquals(3, store.voiceBindings("scope").size)
        order = roles.reversed()
        val next = coordinator.analyze(snapshot(1), "scope", existingCharacters = roles,
            existingBindings = store.voiceBindings("scope").map { it.toDomain() })
        assertEquals(original, next.segments.associate { it.speakerId to it.voiceAssetId })
    }
}
