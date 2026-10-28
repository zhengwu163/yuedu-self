package io.legado.app.help.readaloud.novel

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.server.AliasUpdate
import io.legado.app.help.readaloud.server.ChapterAnalysisResponse
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.VoiceAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/**
 * 电脑端整本任务移植了手机的分析规则；两侧必须对同一组用例给出相同结果。
 * Python 侧读取同一文件：scripts/novel-audio-local/test_book_jobs_analysis.py。
 */
class NovelAudioBookJobParityTest {
    private val parity: JsonObject = JsonParser.parseString(
        File(PARITY_PATH).readText(Charsets.UTF_8)
    ).asJsonObject

    @Test fun normalizeAlias() = cases("normalizeAlias").forEach { case ->
        val input = case["input"].asString
        if (case.isError()) {
            assertThrows(input, IllegalArgumentException::class.java) {
                NovelAudioCharacterRegistry.normalizeAlias(input)
            }
        } else {
            assertEquals(input, case["output"].asString, NovelAudioCharacterRegistry.normalizeAlias(input))
        }
    }

    @Test fun explicitSpeaker() = cases("explicitSpeaker").forEach { case ->
        val expected = case["expected"].takeUnless { it.isJsonNull }?.asLong
        val actual = NovelAudioSpeakerResolver.explicitSpeaker(
            units(case["units"].asJsonArray),
            case["index"].asInt,
            characters(case["characters"].asJsonArray),
            aliases(case["aliases"].asJsonArray)
        )
        assertEquals(case["name"].asString, expected, actual)
    }

    @Test fun resolve() = cases("resolve").forEach { case ->
        val name = case["name"].asString
        val call = {
            NovelAudioCharacterRegistry.resolve(
                workKey = WORK,
                existing = characters(case["existing"].asJsonArray),
                newCharacters = case["newCharacters"].asJsonArray.map {
                    val item = it.asJsonObject
                    NewCharacter(
                        temporaryId = item["temporaryId"].asString,
                        displayName = item["displayName"].asString,
                        gender = item["gender"].asString
                    )
                },
                aliasUpdates = case["aliasUpdates"].asJsonArray.map {
                    val item = it.asJsonObject
                    AliasUpdate(item["characterId"].asString,
                        item["stableAliases"].asJsonArray.map(JsonElement::getAsString))
                },
                existingAliases = aliases(case["existingAliases"].asJsonArray),
                activeMerges = case["merges"].asJsonObject.entrySet().map { (absorbed, primary) ->
                    NovelAudioCharacterMerge(
                        workKey = WORK,
                        primaryCharacterId = primary.asLong,
                        absorbedCharacterId = absorbed.toLong(),
                        primaryNameBefore = "",
                        absorbedNameBefore = ""
                    )
                }
            )
        }
        if (case.isError()) {
            assertThrows(name, IllegalArgumentException::class.java) { call() }
            return@forEach
        }
        val expected = case["expected"].asJsonObject
        val result = call()
        assertEquals(name,
            expected["temporaryToId"].asJsonObject.entrySet().associate { it.key to it.value.asLong },
            result.temporaryToCharacterId)
        assertEquals(name,
            expected["created"].asJsonArray.map {
                val item = it.asJsonObject
                Triple(item["id"].asLong, item["name"].asString, item["gender"].asString)
            },
            result.created.map { Triple(it.id, it.name, it.gender) })
        assertEquals(name,
            expected["aliases"].asJsonArray.map {
                val item = it.asJsonObject
                Triple(item["characterId"].asLong, item["alias"].asString, item["normalizedAlias"].asString)
            },
            result.aliases.map { Triple(it.characterId, it.alias, it.normalizedAlias) })
    }

    @Test fun bind() = cases("bind").forEach { case ->
        val characterId = case["characterId"].asLong
        val existing = case["existing"].asJsonObject.entrySet().map { (id, value) ->
            val binding = value.asJsonObject
            NovelAudioVoiceBinding(WORK, id.toLong(),
                binding["voiceAssetId"].asString, binding["revision"].asLong)
        }
        val candidates = case["candidates"].asJsonArray.map { VoiceAsset(voiceAssetId = it.asString) }
        val call = { NovelAudioVoiceBindingRegistry.bind(WORK, characterId, existing, candidates) }
        if (case.isError()) {
            assertThrows(case["name"].asString,
                NovelAudioVoiceBindingRegistry.NoUnusedVoice::class.java) { call() }
        } else {
            val expected = case["expected"].asJsonObject
            val binding = call()
            assertEquals(case["name"].asString,
                expected["voiceAssetId"].asString to expected["revision"].asLong,
                binding.voiceAssetId to binding.revision)
        }
    }

    @Test fun partition() {
        val coordinator = NovelAudioAnalysisCoordinator(analyze = { ChapterAnalysisResponse() })
        cases("partition").forEach { case ->
            val units = case["lengths"].asJsonArray.mapIndexed { index, length ->
                unit("u$index", "字".repeat(length.asInt), "narrator")
            }
            if (case.isError()) {
                assertThrows(case["name"].asString, IllegalArgumentException::class.java) {
                    coordinator.partition(units)
                }
            } else {
                assertEquals(case["name"].asString,
                    case["expected"].asJsonArray.map { it.asInt },
                    coordinator.partition(units).map { it.size })
            }
        }
    }

    private fun cases(name: String): List<JsonObject> = parity[name].asJsonArray.map { it.asJsonObject }

    private fun JsonObject.isError() = has("error") && this["error"].asBoolean

    private fun units(array: JsonArray) = array.map {
        val item = it.asJsonObject
        unit(item["unitId"].asString, item["text"].asString, item["roleType"].asString)
    }

    private fun unit(id: String, text: String, roleType: String) = ParsedTextUnit(
        unitId = id, kind = "", roleType = roleType, characterName = "", ranges = emptyList(),
        text = text, needsAi = false, confidence = 0.0, reason = ""
    )

    private fun characters(array: JsonArray) = array.map {
        val item = it.asJsonObject
        BookCharacter(
            id = item["id"].asLong,
            bookUrl = WORK,
            name = item["name"].asString,
            gender = item["gender"]?.asString ?: ""
        )
    }

    private fun aliases(array: JsonArray) = array.map {
        val item = it.asJsonObject
        val alias = item["alias"].asString
        NovelAudioAliasEntity(
            workKey = WORK,
            characterId = item["characterId"].asLong,
            alias = alias,
            normalizedAlias = item["normalizedAlias"]?.asString ?: ""
        )
    }

    private companion object {
        const val WORK = "work"
        // Gradle runs unit tests from the app module directory.
        const val PARITY_PATH = "../scripts/novel-audio-local/fixtures/book_jobs_parity.json"
    }
}
