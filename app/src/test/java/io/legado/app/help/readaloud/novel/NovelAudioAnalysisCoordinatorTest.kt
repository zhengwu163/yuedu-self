package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudRoleRange
import io.legado.app.help.readaloud.server.ChapterAnalysisRequest
import io.legado.app.help.readaloud.server.ChapterAnalysisResponse
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.SpeakerAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class NovelAudioAnalysisCoordinatorTest {

    @Test
    fun `analysis batches units by count and UTF16 budget and omits whitespace requests`() = runBlocking {
        val units = List(200) { index ->
            ParsedTextUnit(
                unitId = "unit-$index",
                kind = "narrator",
                roleType = "narrator",
                characterName = "旁白",
                ranges = listOf(ReadAloudRoleRange(index, 0, 4)),
                text = if (index % 17 == 0) "   " else "正文$index",
                needsAi = false,
                confidence = 1.0,
                reason = "test"
            )
        }
        val calls = mutableListOf<ChapterAnalysisRequest>()
        val coordinator = NovelAudioAnalysisCoordinator(
            registry = CharacterRegistry(MemoryCharacterStore()),
            voices = { listOf(io.legado.app.help.readaloud.server.VoiceAsset("narrator-voice")) },
            match = { listOf(io.legado.app.help.readaloud.server.VoiceAsset("narrator-voice")) },
            analyze = { request ->
                calls += request
                ChapterAnalysisResponse(
                    assignments = request.units.map { SpeakerAssignment(it.unitId, "narrator") }
                )
            }
        )
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = units.map { it.text },
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )

        val plan = coordinator.analyze(
            snapshot = snapshot,
            scope = "scope",
            parsedUnits = units
        )

        assertTrue(calls.size >= 3)
        assertTrue(calls.all { it.units.size <= 64 })
        assertTrue(calls.all { it.units.sumOf { unit -> unit.text.length } <= 4000 })
        assertTrue(calls.all { requestJsonSize(it) <= 32 * 1024 })
        assertTrue(calls.all { request -> request.units.none { it.text.isBlank() } })
        assertTrue(calls.all { it.analysisVersion == "1" })
        assertEquals(units.size, plan.segments.size)
        assertTrue(plan.segments.filter { it.text.isBlank() }.all { it.speakerId == 0L })
    }

    @Test
    fun `analysis preserves formal speaker id in the frozen segment plan`() = runBlocking {
        val units = listOf(
            ParsedTextUnit(
                unitId = "unit-dialogue",
                kind = "dialogue",
                roleType = "dialogue",
                characterName = "临时角色",
                ranges = listOf(ReadAloudRoleRange(0, 0, 2)),
                text = "你好",
                needsAi = true,
                confidence = 0.5,
                reason = "test"
            )
        )
        val coordinator = NovelAudioAnalysisCoordinator(
            registry = CharacterRegistry(MemoryCharacterStore()),
            analyze = {
                ChapterAnalysisResponse(
                    assignments = listOf(SpeakerAssignment("unit-dialogue", "tmp-1")),
                    newCharacters = listOf(NewCharacter("tmp-1", "临时角色"))
                )
            }
        )
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("你好"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )

        val plan = coordinator.analyze(
            snapshot = snapshot,
            scope = "scope",
            parsedUnits = units
        )

        assertEquals(1L, plan.segments.single().speakerId)
    }

    @Test
    fun `analysis stamps the current chapter-load generation into the plan`() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("旁白"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val coordinator = NovelAudioAnalysisCoordinator(
            analyze = { request ->
                ChapterAnalysisResponse(
                    assignments = request.units.map { SpeakerAssignment(it.unitId, "narrator") }
                )
            }
        )

        val plan = coordinator.analyze(
            snapshot = snapshot,
            scope = "scope",
            generation = 42L
        )

        assertEquals(42L, plan.generation)
    }

    @Test
    fun `plan producer does not persist a plan after the load generation becomes stale`() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("旁白"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        var persisted = false
        val producer = NovelAudioPlanProducer(
            analyze = { source, scope, generation, _, parsedUnits, _ ->
                NovelAudioPlanFactory.create(
                    snapshot = source,
                    serverScope = scope,
                    generation = generation,
                    parsedUnits = parsedUnits
                )
            },
            persist = { _, _ ->
                persisted = true
                true
            }
        )

        val plan = producer.produce(
            snapshot = snapshot,
            scope = "scope",
            generation = 7L,
            isCurrent = { false }
        )

        assertNull(plan)
        assertTrue(!persisted)
    }

    private fun requestJsonSize(request: ChapterAnalysisRequest): Int {
        return com.google.gson.Gson().toJson(request).toByteArray(Charsets.UTF_8).size
    }
}
