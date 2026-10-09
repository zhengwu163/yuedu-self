package io.legado.app.help.readaloud.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

/** 在转换领域对象前严格检查 wire 类型，防 Gson 将缺字段/null/字符串布尔值静默默认化。 */
internal object NovelAudioJson {
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()

    fun health(json: String): ServerHealth = decode(json) { root ->
        val status = root.text("status")
        val version = root.text("apiVersion")
        require(status == "ok" && version == "1")
        ServerHealth(status, version, root.bool("directorReady"), root.bool("ttsReady"))
    }

    fun runtimeLease(json: String): RuntimeLease = decode(json) { root ->
        RuntimeLease(
            root.text("leaseId"),
            root.text("runtimeProfile"),
            root.optionalInt("maxSegmentChars", 1..MAX_SEGMENT_CHARS) ?: 0,
            root.optionalBool("selfHosted") ?: false
        )
    }

    fun runtimeStatus(json: String): RuntimeStatus = decode(json) { root ->
        val state = root.text("state")
        require(state in RUNTIME_STATES)
        RuntimeStatus(state, root.bool("activeLease"))
    }

    fun runtimeRelease(json: String) = decode(json) {
        require(it.text("state") == "idle")
    }

    fun voices(json: String, key: String): List<VoiceAsset> = decode(json) { root ->
        root.objects(key).map {
            VoiceAsset(it.text("voiceAssetId"), it.text("displayName"), it.text("gender"),
                it.text("ageRange"), it.strings("traits"), it.bool("previewAvailable"))
        }.also { require(it.map(VoiceAsset::voiceAssetId).distinct().size == it.size) }
    }

    fun analysis(json: String, request: ChapterAnalysisRequest): ChapterAnalysisResponse = decode(json) { root ->
        val assignments = root.objects("assignments").map {
            SpeakerAssignment(it.text("unitId"), it.text("speakerId"))
        }
        val created = root.objects("newCharacters").map {
            NewCharacter(it.text("temporaryId"), it.text("displayName"), it.text("gender"),
                it.text("ageRange"), VoicePersona(it.getAsJsonObject("voicePersona").strings("traits")))
        }
        val aliases = root.objects("aliasUpdates").map {
            AliasUpdate(it.text("characterId"), it.strings("stableAliases"))
        }
        val known = request.characters.map { it.characterId }.toSet() + "narrator"
        val temporaryIds = created.map { it.temporaryId }
        require(temporaryIds.distinct().size == temporaryIds.size && temporaryIds.none { it in known })
        val allowed = known + temporaryIds
        require(assignments.map { it.unitId }.toSet() == request.units.map { it.unitId }.toSet())
        require(assignments.size == request.units.size)
        require(assignments.all { it.speakerId in allowed })
        require(aliases.all { it.characterId in allowed && it.characterId != "narrator" })
        ChapterAnalysisResponse(assignments, created, aliases)
    }

    private fun <T> decode(json: String, transform: (JsonObject) -> T): T =
        kotlin.runCatching {
            rejectDuplicateKeys(json)
            val root = gson.fromJson(json, JsonElement::class.java)
            require(root != null && root.isJsonObject)
            transform(root.asJsonObject)
        }.getOrElse { throw NovelAudioServerException("PROTOCOL") }

    /** Gson 树解析会覆盖同名键；在构造树前检查每层对象，避免版本/身份的歧义。 */
    private fun rejectDuplicateKeys(json: String) {
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            val scopes = ArrayDeque<MutableSet<String>?>()
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject()
                        scopes.addLast(HashSet())
                    }
                    JsonToken.END_OBJECT -> {
                        reader.endObject()
                        scopes.removeLast()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        scopes.addLast(null)
                    }
                    JsonToken.END_ARRAY -> {
                        reader.endArray()
                        scopes.removeLast()
                    }
                    JsonToken.NAME -> require(scopes.last()?.add(reader.nextName()) == true)
                    JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> error("Unexpected JSON token")
                }
            }
        }
    }

    private fun JsonObject.text(key: String): String {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.also { require(it.isNotBlank()) }
    }

    private fun JsonObject.bool(key: String): Boolean {
        val value = get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }

    /** 缺省返回 null；出现时必须是 JSON 布尔，避免 "true" 被当成真。 */
    private fun JsonObject.optionalBool(key: String): Boolean? {
        if (get(key) == null) return null
        return bool(key)
    }

    /** 缺省返回 null；一旦出现就必须是范围内的整数，避免 "50" 或 50.5 被静默截断。 */
    private fun JsonObject.optionalInt(key: String, range: IntRange): Int? {
        val value = get(key) ?: return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        val number = value.asBigDecimal
        require(number.stripTrailingZeros().scale() <= 0)
        return number.intValueExact().also { require(it in range) }
    }

    private fun JsonObject.objects(key: String): List<JsonObject> {
        val value = get(key)
        require(value != null && value.isJsonArray)
        return value.asJsonArray.map { require(it.isJsonObject); it.asJsonObject }
    }

    private fun JsonObject.strings(key: String): List<String> {
        val value = get(key)
        require(value != null && value.isJsonArray)
        return value.asJsonArray.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank())
            it.asString
        }
    }

    // 与合成请求的单次文本上限一致。
    private const val MAX_SEGMENT_CHARS = 1200

    private val RUNTIME_STATES = setOf(
        "idle",
        "starting",
        "ready",
        "generating",
        "unloading",
        "failed"
    )
}
