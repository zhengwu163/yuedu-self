package io.legado.app.help.readaloud.novel

import androidx.annotation.Keep
import com.google.gson.reflect.TypeToken
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookCharacterDao
import io.legado.app.data.dao.NovelAudioDao
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.data.entities.NovelAudioMergeRecordEntity
import io.legado.app.data.entities.NovelAudioMergeStates
import io.legado.app.data.entities.NovelAudioVoiceBindingEntity
import io.legado.app.help.readaloud.server.AliasUpdate
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.VoiceAsset
import io.legado.app.help.readaloud.server.VoicePersona
import io.legado.app.utils.GSON
import java.text.Normalizer
import java.util.Locale

interface NovelAudioCharacterStore {
    fun characters(workKey: String): List<BookCharacter>
    fun aliases(workKey: String): List<NovelAudioAliasEntity>
    fun activeMerges(workKey: String): List<NovelAudioCharacterMerge>
    fun save(workKey: String, result: CharacterRegistryResult)
    fun deleteAlias(workKey: String, normalizedAlias: String)
    fun allocateCharacterId(usedIds: Set<Long>): Long
    fun voiceBindings(characterIds: Set<Long>): List<NovelAudioVoiceBindingEntity>
    fun upsertAlias(alias: NovelAudioAliasEntity)
    fun upsertVoiceBinding(binding: NovelAudioVoiceBindingEntity)
    fun deleteVoiceBinding(scope: String, characterId: Long)
    fun insertMerge(merge: NovelAudioCharacterMerge): NovelAudioCharacterMerge
    fun revokeMerge(merge: NovelAudioCharacterMerge)
    fun inTransaction(block: () -> Unit)
}

class MemoryCharacterStore : NovelAudioCharacterStore {
    private val characterMap = mutableMapOf<String, MutableList<BookCharacter>>()
    private val aliasMap = mutableMapOf<String, MutableList<NovelAudioAliasEntity>>()
    private val bindingMap = mutableMapOf<Pair<String, Long>, NovelAudioVoiceBindingEntity>()
    private val mergeMap = mutableMapOf<String, MutableList<NovelAudioCharacterMerge>>()
    private var nextMergeId = 1L

    override fun characters(workKey: String): List<BookCharacter> =
        characterMap[workKey].orEmpty().toList()

    override fun aliases(workKey: String): List<NovelAudioAliasEntity> =
        aliasMap[workKey].orEmpty().toList()

    override fun activeMerges(workKey: String): List<NovelAudioCharacterMerge> =
        mergeMap[workKey].orEmpty()
            .filter { it.state == NovelAudioMergeStates.ACTIVE }
            .sortedByDescending { it.createdAt }

    fun mergeRecords(workKey: String): List<NovelAudioCharacterMerge> =
        mergeMap[workKey].orEmpty().toList()

    override fun save(workKey: String, result: CharacterRegistryResult) {
        characterMap.getOrPut(workKey) { mutableListOf() }.apply {
            result.created.forEach { created ->
                removeAll { it.id == created.id }
                add(created)
            }
        }
        aliasMap.getOrPut(workKey) { mutableListOf() }.apply {
            result.aliases.forEach { alias ->
                removeAll {
                    it.workKey == alias.workKey &&
                        it.normalizedAlias == alias.normalizedAlias
                }
                add(alias)
            }
        }
    }

    fun seed(workKey: String, characters: List<BookCharacter>) {
        characterMap[workKey] = characters.toMutableList()
    }

    fun seedAlias(alias: NovelAudioAliasEntity) {
        upsertAlias(alias)
    }

    fun seedBinding(binding: NovelAudioVoiceBindingEntity) {
        upsertVoiceBinding(binding)
    }

    fun voiceBindings(scope: String): List<NovelAudioVoiceBindingEntity> =
        bindingMap.values
            .filter { it.scope == scope }
            .sortedBy { it.characterId }

    override fun deleteAlias(workKey: String, normalizedAlias: String) {
        aliasMap[workKey]?.removeAll { it.normalizedAlias == normalizedAlias }
    }

    override fun upsertAlias(alias: NovelAudioAliasEntity) {
        aliasMap.getOrPut(alias.workKey) { mutableListOf() }.apply {
            removeAll {
                it.workKey == alias.workKey &&
                    it.normalizedAlias == alias.normalizedAlias
            }
            add(alias)
        }
    }

    override fun allocateCharacterId(usedIds: Set<Long>): Long {
        val storedIds = characterMap.values.flatten().map { it.id }
        return (storedIds.asSequence() + usedIds.asSequence()).maxOrNull()?.plus(1L) ?: 1L
    }

    override fun voiceBindings(characterIds: Set<Long>): List<NovelAudioVoiceBindingEntity> {
        return bindingMap.values.filter { it.characterId in characterIds }.toList()
    }

    override fun upsertVoiceBinding(binding: NovelAudioVoiceBindingEntity) {
        bindingMap[binding.scope to binding.characterId] = binding
    }

    override fun deleteVoiceBinding(scope: String, characterId: Long) {
        bindingMap.remove(scope to characterId)
    }

    override fun insertMerge(merge: NovelAudioCharacterMerge): NovelAudioCharacterMerge {
        val stored = merge.copy(mergeId = nextMergeId++)
        mergeMap.getOrPut(merge.workKey) { mutableListOf() }.add(stored)
        return stored
    }

    override fun revokeMerge(merge: NovelAudioCharacterMerge) {
        mergeMap[merge.workKey]?.let { merges ->
            for (index in merges.indices) {
                if (merges[index].mergeId == merge.mergeId) {
                    merges[index] = merges[index].copy(
                        state = NovelAudioMergeStates.REVOKED,
                        revokedAt = System.currentTimeMillis()
                    )
                    break
                }
            }
        }
    }

    override fun inTransaction(block: () -> Unit) {
        synchronized(this) {
            block()
        }
    }
}

class CharacterRegistry(private val store: NovelAudioCharacterStore) {
    fun resolve(
        workKey: String,
        newCharacters: List<NewCharacter> = emptyList(),
        aliasUpdates: List<AliasUpdate> = emptyList()
    ): CharacterRegistryResult {
        var result: CharacterRegistryResult? = null
        store.inTransaction {
            result = NovelAudioCharacterRegistry.resolve(
                workKey = workKey,
                existing = store.characters(workKey),
                existingAliases = store.aliases(workKey),
                activeMerges = store.activeMerges(workKey),
                newCharacters = newCharacters,
                aliasUpdates = aliasUpdates,
                idAllocator = store::allocateCharacterId
            )
            store.save(workKey, requireNotNull(result))
        }
        return requireNotNull(result)
    }

    fun addManualAlias(
        workKey: String,
        characterId: Long,
        alias: String
    ): NovelAudioAliasEntity {
        var result: NovelAudioAliasEntity? = null
        store.inTransaction {
            result = NovelAudioCharacterRegistry.addManualAlias(
                workKey = workKey,
                characterId = characterId,
                alias = alias,
                existingCharacters = store.characters(workKey),
                existingAliases = store.aliases(workKey)
            )
            store.save(
                workKey,
                CharacterRegistryResult(aliases = listOf(requireNotNull(result)))
            )
        }
        return requireNotNull(result)
    }

    fun removeManualAlias(workKey: String, normalizedAlias: String): Boolean {
        val normalized = NovelAudioCharacterRegistry.normalizeAlias(normalizedAlias)
        var removed = false
        store.inTransaction {
            removed = store.aliases(workKey).any { it.normalizedAlias == normalized }
            if (removed) store.deleteAlias(workKey, normalized)
        }
        return removed
    }

    fun merge(
        workKey: String,
        primaryCharacterId: Long,
        absorbedCharacterId: Long
    ): NovelAudioCharacterMerge {
        var result: NovelAudioCharacterMerge? = null
        store.inTransaction {
            require(
                store.activeMerges(workKey).none {
                    it.primaryCharacterId == primaryCharacterId &&
                        it.absorbedCharacterId == absorbedCharacterId
                }
            ) {
                "character pair already has an active merge"
            }
            val merge = NovelAudioCharacterRegistry.merge(
                workKey = workKey,
                primaryCharacterId = primaryCharacterId,
                absorbedCharacterId = absorbedCharacterId,
                existingCharacters = store.characters(workKey),
                existingAliases = store.aliases(workKey),
                existingBindings = store.voiceBindings(
                    setOf(primaryCharacterId, absorbedCharacterId)
                )
            )
            val now = System.currentTimeMillis()
            merge.absorbedAliasesBefore.forEach { alias ->
                store.upsertAlias(
                    alias.copy(
                        characterId = primaryCharacterId,
                        source = "merge",
                        updatedAt = now
                    )
                )
            }
            val bindingsByScope = merge.voiceBindingsBefore.groupBy { it.scope }
            bindingsByScope.forEach { (scope, bindings) ->
                val primaryBinding = bindings.firstOrNull {
                    it.characterId == primaryCharacterId
                }
                val absorbedBinding = bindings.firstOrNull {
                    it.characterId == absorbedCharacterId
                }
                store.deleteVoiceBinding(scope, absorbedCharacterId)
                if (primaryBinding == null && absorbedBinding != null) {
                    store.upsertVoiceBinding(
                        absorbedBinding.copy(characterId = primaryCharacterId)
                    )
                }
            }
            result = store.insertMerge(merge)
        }
        return requireNotNull(result)
    }

    fun revokeLatestMerge(workKey: String): Boolean {
        var revoked = false
        store.inTransaction {
            val merge = store.activeMerges(workKey).firstOrNull() ?: return@inTransaction
            val now = System.currentTimeMillis()
            merge.absorbedAliasesBefore.forEach { original ->
                val current = store.aliases(workKey).firstOrNull {
                    it.normalizedAlias == original.normalizedAlias
                }
                if (current == null ||
                    (current.characterId == merge.primaryCharacterId && current.source == "merge")
                ) {
                    store.upsertAlias(original.copy(updatedAt = now))
                }
            }
            val beforeByScope = merge.voiceBindingsBefore.groupBy { it.scope }
            beforeByScope.forEach { (scope, bindings) ->
                val primaryBefore = bindings.firstOrNull {
                    it.characterId == merge.primaryCharacterId
                }
                val absorbedBefore = bindings.firstOrNull {
                    it.characterId == merge.absorbedCharacterId
                }
                val currentPrimary = store.voiceBindings(
                    setOf(merge.primaryCharacterId)
                ).firstOrNull { it.scope == scope }
                if (primaryBefore == null &&
                    currentPrimary != null &&
                    absorbedBefore != null &&
                    currentPrimary.voiceAssetId == absorbedBefore.voiceAssetId &&
                    currentPrimary.revision == absorbedBefore.revision
                ) {
                    store.deleteVoiceBinding(scope, merge.primaryCharacterId)
                } else if (primaryBefore != null && currentPrimary == null) {
                    store.upsertVoiceBinding(primaryBefore)
                }
                if (absorbedBefore != null &&
                    store.voiceBindings(setOf(merge.absorbedCharacterId))
                        .none { it.scope == scope }
                ) {
                    store.upsertVoiceBinding(absorbedBefore)
                }
            }
            store.revokeMerge(merge)
            revoked = true
        }
        return revoked
    }
}

@Keep
data class CharacterRegistryResult(
    val temporaryToCharacterId: Map<String, Long> = emptyMap(),
    val created: List<BookCharacter> = emptyList(),
    val aliases: List<NovelAudioAliasEntity> = emptyList()
)

@Keep
data class NovelAudioCharacterMerge(
    val mergeId: Long = 0L,
    val workKey: String,
    val primaryCharacterId: Long,
    val absorbedCharacterId: Long,
    val primaryNameBefore: String,
    val absorbedNameBefore: String,
    val primaryAliasesBefore: List<NovelAudioAliasEntity> = emptyList(),
    val absorbedAliasesBefore: List<NovelAudioAliasEntity> = emptyList(),
    val voiceBindingsBefore: List<NovelAudioVoiceBindingEntity> = emptyList(),
    val state: String = NovelAudioMergeStates.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val revokedAt: Long = 0L
)

object NovelAudioCharacterRegistry {

    class AliasConflict(message: String) : IllegalArgumentException(message)

    fun normalizeAlias(alias: String): String {
        val normalized = Normalizer.normalize(alias, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)
        require(normalized.isNotBlank())
        require(normalized.length <= 128)
        require(normalized !in REJECTED_ALIASES) {
            "pronoun or title cannot be a stable alias"
        }
        return normalized
    }

    fun resolve(
        workKey: String,
        existing: List<BookCharacter>,
        newCharacters: List<NewCharacter>,
        aliasUpdates: List<AliasUpdate> = emptyList(),
        existingAliases: List<NovelAudioAliasEntity> = emptyList(),
        activeMerges: List<NovelAudioCharacterMerge> = emptyList(),
        idAllocator: (Set<Long>) -> Long = { ids -> (ids.maxOrNull() ?: 0L) + 1L }
    ): CharacterRegistryResult {
        require(workKey.isNotBlank())
        val allExisting = existing.filter { it.bookUrl == workKey }
        val byAlias = mutableMapOf<String, Long>()
        allExisting.forEach { character ->
            putAlias(
                byAlias,
                normalizeAlias(character.name),
                canonicalCharacterId(character.id, activeMerges)
            )
        }
        existingAliases.filter { it.workKey == workKey }.forEach { alias ->
            putAlias(
                byAlias,
                normalizeAlias(alias.normalizedAlias.ifBlank { alias.alias }),
                canonicalCharacterId(alias.characterId, activeMerges)
            )
        }

        val aliasesByTemporaryId = aliasUpdates.groupBy { it.characterId }
        val ids = allExisting.map { it.id }.toMutableSet()
        val temporaryToId = mutableMapOf<String, Long>()
        val created = mutableListOf<BookCharacter>()
        val generatedAliases = mutableListOf<NovelAudioAliasEntity>()
        val now = System.currentTimeMillis()

        newCharacters.forEach { candidate ->
            require(candidate.temporaryId.isNotBlank())
            require(candidate.displayName.isNotBlank())
            val names = (listOf(candidate.displayName) +
                aliasesByTemporaryId[candidate.temporaryId].orEmpty().flatMap { it.stableAliases })
                .map(::normalizeAlias)
                .distinct()
            val matched = names.mapNotNull { byAlias[it] }.distinct()
            if (matched.size > 1) {
                throw AliasConflict("one temporary character matches multiple formal characters")
            }
            val id = matched.singleOrNull()
                ?: idAllocator(ids).also { ids += it }
            val canonicalId = canonicalCharacterId(id, activeMerges)
            temporaryToId[candidate.temporaryId] = canonicalId
            if (matched.isEmpty()) {
                val formal = BookCharacter(
                    id = canonicalId,
                    bookUrl = workKey,
                    name = candidate.displayName.trim(),
                    gender = BookCharacter.normalizeGender(candidate.gender),
                    autoCreated = true,
                    source = "novel-audio",
                    createdAt = now,
                    updatedAt = now
                )
                created += formal
                putAlias(byAlias, normalizeAlias(formal.name), canonicalId)
            }
            names.forEach { alias ->
                putAlias(byAlias, alias, canonicalId)
                generatedAliases += NovelAudioAliasEntity(
                    workKey = workKey,
                    characterId = canonicalId,
                    alias = alias,
                    normalizedAlias = alias,
                    source = "novel-audio",
                    createdAt = now,
                    updatedAt = now
                )
            }
        }

        aliasUpdates.forEach { update ->
            require(update.stableAliases.size <= MAX_ALIASES_PER_CHARACTER) {
                "too many stable aliases for one character"
            }
            val characterId = update.characterId.toLongOrNull()
                ?: temporaryToId[update.characterId]
                ?: throw IllegalArgumentException("unknown character reference")
            val canonicalId = canonicalCharacterId(characterId, activeMerges)
            require(canonicalId != 0L)
            update.stableAliases.forEach { rawAlias ->
                val alias = normalizeAlias(rawAlias)
                putAlias(byAlias, alias, canonicalId)
                generatedAliases += NovelAudioAliasEntity(
                    workKey = workKey,
                    characterId = canonicalId,
                    alias = rawAlias.trim(),
                    normalizedAlias = alias,
                    source = "server",
                    createdAt = now,
                    updatedAt = now
                )
            }
        }
        return CharacterRegistryResult(
            temporaryToCharacterId = temporaryToId.toMap(),
            created = created.toList(),
            aliases = generatedAliases.distinctBy { it.workKey to it.normalizedAlias }
        )
    }

    private fun canonicalCharacterId(
        characterId: Long,
        activeMerges: List<NovelAudioCharacterMerge>
    ): Long {
        var current = characterId
        val seen = mutableSetOf<Long>()
        while (seen.add(current)) {
            val next = activeMerges.firstOrNull {
                it.absorbedCharacterId == current &&
                    it.state == NovelAudioMergeStates.ACTIVE
            }?.primaryCharacterId ?: return current
            current = next
        }
        throw AliasConflict("character merge graph contains a cycle")
    }

    fun addManualAlias(
        workKey: String,
        characterId: Long,
        alias: String,
        existingCharacters: List<BookCharacter>,
        existingAliases: List<NovelAudioAliasEntity>
    ): NovelAudioAliasEntity {
        require(existingCharacters.any { it.bookUrl == workKey && it.id == characterId })
        val normalized = normalizeAlias(alias)
        val conflict = existingAliases.firstOrNull {
            it.workKey == workKey && it.normalizedAlias == normalized &&
                it.characterId != characterId
        }
        if (conflict != null) throw AliasConflict("alias is already assigned to another character")
        return NovelAudioAliasEntity(
            workKey = workKey,
            characterId = characterId,
            alias = alias.trim(),
            normalizedAlias = normalized,
            source = "manual"
        )
    }

    fun removeManualAlias(
        workKey: String,
        characterId: Long,
        normalizedAlias: String
    ): Boolean {
        return normalizedAlias.isNotBlank() && characterId != 0L && workKey.isNotBlank()
    }

    fun merge(
        workKey: String,
        primaryCharacterId: Long,
        absorbedCharacterId: Long,
        existingCharacters: List<BookCharacter>,
        existingAliases: List<NovelAudioAliasEntity> = emptyList(),
        existingBindings: List<NovelAudioVoiceBindingEntity> = emptyList()
    ): NovelAudioCharacterMerge {
        require(primaryCharacterId != absorbedCharacterId)
        val primary = existingCharacters.first {
            it.bookUrl == workKey && it.id == primaryCharacterId
        }
        val absorbed = existingCharacters.first {
            it.bookUrl == workKey && it.id == absorbedCharacterId
        }
        val aliases = existingAliases.filter { it.workKey == workKey }
        val absorbedAliases = aliases.filter { it.characterId == absorbedCharacterId }
        val primaryAliases = aliases.filter { it.characterId == primaryCharacterId }
        val protectedIds = setOf(primaryCharacterId, absorbedCharacterId)
        absorbedAliases.forEach { alias ->
            val owner = aliases.firstOrNull {
                it.normalizedAlias == alias.normalizedAlias &&
                    it.characterId !in protectedIds
            }
            require(owner == null) {
                "cannot merge characters because an alias belongs to another character"
            }
        }
        return NovelAudioCharacterMerge(
            workKey = workKey,
            primaryCharacterId = primary.id,
            absorbedCharacterId = absorbed.id,
            primaryNameBefore = primary.name,
            absorbedNameBefore = absorbed.name,
            primaryAliasesBefore = primaryAliases,
            absorbedAliasesBefore = absorbedAliases,
            voiceBindingsBefore = existingBindings
                .filter { it.characterId in protectedIds }
        )
    }

    fun revokeMerge(
        merge: NovelAudioCharacterMerge
    ): Boolean {
        return merge.primaryCharacterId != merge.absorbedCharacterId &&
            merge.state == NovelAudioMergeStates.ACTIVE
    }

    private fun putAlias(map: MutableMap<String, Long>, alias: String, id: Long) {
        val previous = map[alias]
        if (previous != null && previous != id) {
            throw AliasConflict("alias is already assigned to another character")
        }
        map[alias] = id
    }

    private val REJECTED_ALIASES = setOf(
        "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们",
        "先生", "女士", "小姐", "老师", "同学", "大人", "殿下", "陛下",
        "公子", "姑娘", "夫人", "少爷", "老爷", "前辈", "后辈"
    )

    private const val MAX_ALIASES_PER_CHARACTER = 32
}

@Keep
data class NovelAudioVoiceBinding(
    val scope: String,
    val characterId: Long,
    val voiceAssetId: String,
    val revision: Long
)

object NovelAudioVoiceBindingRegistry {

    class NoUnusedVoice : IllegalStateException("no unused voice asset is available")

    fun bind(
        scope: String,
        characterId: Long,
        existing: List<NovelAudioVoiceBinding>,
        candidates: List<VoiceAsset>,
        replace: Boolean = false
    ): NovelAudioVoiceBinding {
        require(scope.isNotBlank())
        require(characterId >= 0L)
        val current = existing.firstOrNull {
            it.scope == scope && it.characterId == characterId
        }
        if (current != null && !replace) {
            return current
        }
        val usedByOthers = existing
            .filter { it.scope == scope && it.characterId != characterId }
            .map { it.voiceAssetId }
            .toSet()
        val selected = candidates.firstOrNull {
            it.voiceAssetId.isNotBlank() && it.voiceAssetId !in usedByOthers
        } ?: throw NoUnusedVoice()
        return NovelAudioVoiceBinding(
            scope = scope,
            characterId = characterId,
            voiceAssetId = selected.voiceAssetId,
            revision = if (current == null) 1L else current.revision + 1L
        )
    }
}

class NovelAudioRoomCharacterStore(
    private val database: AppDatabase,
    private val characterDao: BookCharacterDao = database.bookCharacterDao,
    private val novelAudioDao: NovelAudioDao = database.novelAudioDao
) : NovelAudioCharacterStore {
    override fun characters(workKey: String): List<BookCharacter> = characterDao.characters(workKey)

    override fun aliases(workKey: String): List<NovelAudioAliasEntity> = novelAudioDao.aliases(workKey)

    override fun activeMerges(workKey: String): List<NovelAudioCharacterMerge> {
        return novelAudioDao.activeMergeRecords(workKey).mapNotNull { it.toDomainOrNull() }
    }

    override fun save(workKey: String, result: CharacterRegistryResult) {
        database.runInTransaction {
            result.created.forEach { characterDao.insertCharacter(it) }
            result.aliases.forEach { novelAudioDao.insertAlias(it) }
        }
    }

    override fun deleteAlias(workKey: String, normalizedAlias: String) {
        novelAudioDao.deleteAlias(workKey, normalizedAlias)
    }

    override fun allocateCharacterId(usedIds: Set<Long>): Long {
        val storedMax = characterDao.allCharacters().maxOfOrNull { it.id } ?: 0L
        return maxOf(storedMax, usedIds.maxOrNull() ?: 0L) + 1L
    }

    override fun voiceBindings(characterIds: Set<Long>): List<NovelAudioVoiceBindingEntity> {
        if (characterIds.isEmpty()) return emptyList()
        return novelAudioDao.voiceBindingsForCharacters(characterIds.toList())
    }

    override fun upsertAlias(alias: NovelAudioAliasEntity) {
        novelAudioDao.upsertAlias(alias)
    }

    override fun upsertVoiceBinding(binding: NovelAudioVoiceBindingEntity) {
        novelAudioDao.upsertVoiceBinding(binding)
    }

    override fun deleteVoiceBinding(scope: String, characterId: Long) {
        novelAudioDao.deleteVoiceBinding(scope, characterId)
    }

    override fun insertMerge(merge: NovelAudioCharacterMerge): NovelAudioCharacterMerge {
        val id = novelAudioDao.insertMergeRecord(merge.toEntity())
        return merge.copy(mergeId = id)
    }

    override fun revokeMerge(merge: NovelAudioCharacterMerge) {
        novelAudioDao.revokeMergeRecord(merge.mergeId)
    }

    override fun inTransaction(block: () -> Unit) {
        database.runInTransaction(block)
    }
}

private val NOVEL_AUDIO_ALIAS_LIST_TYPE =
    object : TypeToken<List<NovelAudioAliasEntity>>() {}.type

private val NOVEL_AUDIO_BINDING_LIST_TYPE =
    object : TypeToken<List<NovelAudioVoiceBindingEntity>>() {}.type

private fun NovelAudioCharacterMerge.toEntity(): NovelAudioMergeRecordEntity {
    return NovelAudioMergeRecordEntity(
        id = mergeId,
        workKey = workKey,
        primaryCharacterId = primaryCharacterId,
        absorbedCharacterId = absorbedCharacterId,
        primaryNameBefore = primaryNameBefore,
        absorbedNameBefore = absorbedNameBefore,
        primaryAliasesBeforeJson = GSON.toJson(primaryAliasesBefore),
        absorbedAliasesBeforeJson = GSON.toJson(absorbedAliasesBefore),
        voiceBindingsBeforeJson = GSON.toJson(voiceBindingsBefore),
        state = state,
        createdAt = createdAt,
        revokedAt = revokedAt
    )
}

private fun NovelAudioMergeRecordEntity.toDomainOrNull(): NovelAudioCharacterMerge? {
    return kotlin.runCatching {
        NovelAudioCharacterMerge(
            mergeId = id,
            workKey = workKey,
            primaryCharacterId = primaryCharacterId,
            absorbedCharacterId = absorbedCharacterId,
            primaryNameBefore = primaryNameBefore,
            absorbedNameBefore = absorbedNameBefore,
            primaryAliasesBefore = GSON.fromJson(
                primaryAliasesBeforeJson,
                NOVEL_AUDIO_ALIAS_LIST_TYPE
            ) ?: emptyList(),
            absorbedAliasesBefore = GSON.fromJson(
                absorbedAliasesBeforeJson,
                NOVEL_AUDIO_ALIAS_LIST_TYPE
            ) ?: emptyList(),
            voiceBindingsBefore = GSON.fromJson(
                voiceBindingsBeforeJson,
                NOVEL_AUDIO_BINDING_LIST_TYPE
            ) ?: emptyList(),
            state = state,
            createdAt = createdAt,
            revokedAt = revokedAt
        )
    }.getOrNull()
}

fun NovelAudioVoiceBinding.toEntity(): NovelAudioVoiceBindingEntity {
    return NovelAudioVoiceBindingEntity(
        scope = scope,
        characterId = characterId,
        voiceAssetId = voiceAssetId,
        revision = revision
    )
}

fun NovelAudioVoiceBindingEntity.toDomain(): NovelAudioVoiceBinding {
    return NovelAudioVoiceBinding(scope, characterId, voiceAssetId, revision)
}
