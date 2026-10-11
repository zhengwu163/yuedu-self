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
    @Test fun explicitSavedVoiceDoesNotCallAutomaticMatcherAgain() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            "book://manual", 0, "c", listOf("“明天我们在哪里见面？”"),
            rules = ReadAloudPreprocessRuleConfig().freeze())
        val character = io.legado.app.data.entities.BookCharacter(id = 12L, bookUrl = snapshot.workKey, name = "陈远")
        val saved = listOf(NovelAudioVoiceBinding("scope", 0L, "n", 1L),
            NovelAudioVoiceBinding("scope", 12L, "manual", 2L))
        val plan = NovelAudioAnalysisCoordinator(
            analyze = { r -> ChapterAnalysisResponse(assignments = r.units.map { SpeakerAssignment(it.unitId, "12") }) },
            voices = { listOf(io.legado.app.help.readaloud.server.VoiceAsset("n", narrator = true),
                io.legado.app.help.readaloud.server.VoiceAsset("manual")) },
            match = { throw AssertionError("A saved voice must not be rematched") }
        ).analyze(snapshot, "scope", existingCharacters = listOf(character), existingBindings = saved)
        assertEquals("manual", plan.playableSegments.single().voiceAssetId)
        assertEquals(2L, plan.playableSegments.single().bindingRevision)
    }
    @Test fun batchBoundaryCarriesRealTextContextWithoutReassigningEarlierUnits() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            "book://context", 0, "c", List(75) { "“我们明天在门口集合。$it”" },
            rules = ReadAloudPreprocessRuleConfig().freeze())
        val character = io.legado.app.data.entities.BookCharacter(id = 12L, bookUrl = snapshot.workKey, name = "陈远")
        val other = character.copy(id = 9L, name = "林青")
        val requests = mutableListOf<ChapterAnalysisRequest>()
        val coordinator = NovelAudioAnalysisCoordinator(analyze = { request ->
            requests += request
            ChapterAnalysisResponse(assignments = request.units.map { unit ->
                SpeakerAssignment(unit.unitId, if (requests.size == 1) "12" else "9")
            })
        })
        val plan = coordinator.analyze(snapshot, "scope", existingCharacters = listOf(character, other))
        assertTrue(requests.size > 1)
        val overlap = requests[0].units.map { it.unitId }.toSet()
            .intersect(requests[1].units.map { it.unitId }.toSet())
        assertTrue("Opaque prior IDs alone do not tell the model who was speaking", overlap.isNotEmpty())
        assertEquals(12L, plan.playableSegments.first().speakerId)
        assertTrue(requests.all { it.units.size <= 64 && it.units.sumOf { unit -> unit.text.length } <= 4000 })
    }

    @Test
    fun explicitReportingClauseWinsOverWrongModelSpeaker() = runBlocking {
        val work = "work:test"
        val characters = listOf(
            io.legado.app.data.entities.BookCharacter(id = 12L, bookUrl = work, name = "陈远"),
            io.legado.app.data.entities.BookCharacter(id = 9L, bookUrl = work, name = "林青")
        )
        val store = MemoryCharacterStore().apply { seed(work, characters) }
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://cue", chapterIndex = 0, chapterUrl = "c", workKey = work,
            strings = listOf("陈远问道：“明天我们在哪里见面？”"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val plan = NovelAudioAnalysisCoordinator(
            registry = CharacterRegistry(store),
            analyze = { request -> ChapterAnalysisResponse(assignments = request.units.map {
                SpeakerAssignment(it.unitId, "9")
            }) }
        ).analyze(snapshot, "scope", existingCharacters = characters)
        assertEquals(12L, plan.playableSegments.last().speakerId)
        assertEquals(0L, plan.playableSegments.first().speakerId)
    }

    @Test
    fun storedStableAliasIsSentBackToModelInLaterChapter() = runBlocking {
        val work = "work:test"
        val character = io.legado.app.data.entities.BookCharacter(id = 12L, bookUrl = work, name = "陈远")
        val store = MemoryCharacterStore().apply {
            seed(work, listOf(character))
            seedAlias(io.legado.app.data.entities.NovelAudioAliasEntity(
                workKey = work, characterId = 12L, alias = "阿远", normalizedAlias = "阿远"
            ))
        }
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://alias", chapterIndex = 1, chapterUrl = "c", workKey = work,
            strings = listOf("阿远说：“今天一起出发。”"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        NovelAudioAnalysisCoordinator(registry = CharacterRegistry(store), analyze = { request ->
            assertTrue(request.characters.single().stableAliases.contains("阿远"))
            ChapterAnalysisResponse(assignments = request.units.map { SpeakerAssignment(it.unitId, "12") })
        }).analyze(snapshot, "scope", existingCharacters = listOf(character))
        Unit
    }

    @Test
    fun shortReplyWithExplicitReportingCueIsNotNarrator() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://short", chapterIndex = 0, chapterUrl = "c",
            strings = listOf("陈远答道：“好”"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        assertEquals("character", snapshot.parseUnits().last().roleType)
    }

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
