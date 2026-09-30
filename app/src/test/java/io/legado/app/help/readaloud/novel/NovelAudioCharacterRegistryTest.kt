package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.data.entities.NovelAudioVoiceBindingEntity
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.AliasUpdate
import io.legado.app.help.readaloud.server.VoiceAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioCharacterRegistryTest {

    @Test
    fun `temporary characters reuse stable ids through aliases`() {
        val existing = listOf(
            BookCharacter(id = 42, bookUrl = "work:author/title", name = "阿宁")
        )
        val result = NovelAudioCharacterRegistry.resolve(
            workKey = "work:author/title",
            existing = existing,
            newCharacters = listOf(
                NewCharacter(
                    temporaryId = "tmp-1",
                    displayName = "宁宁"
                )
            ),
            aliasUpdates = listOf(AliasUpdate("tmp-1", listOf("阿宁")))
        )

        assertEquals(42L, result.temporaryToCharacterId["tmp-1"])
        assertEquals(emptyList<BookCharacter>(), result.created)
    }

    @Test
    fun `pronouns and titles are rejected as stable aliases`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelAudioCharacterRegistry.normalizeAlias("他")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NovelAudioCharacterRegistry.normalizeAlias("老师")
        }
    }

    @Test
    fun `same work alias conflict is explicit`() {
        assertThrows(NovelAudioCharacterRegistry.AliasConflict::class.java) {
            NovelAudioCharacterRegistry.resolve(
                workKey = "work:author/title",
                existing = listOf(
                    BookCharacter(id = 1, bookUrl = "work:author/title", name = "甲"),
                    BookCharacter(id = 2, bookUrl = "work:author/title", name = "乙")
                ),
                newCharacters = listOf(
                    NewCharacter("tmp", "新角色")
                ),
                aliasUpdates = listOf(AliasUpdate("tmp", listOf("甲", "乙")))
            )
        }
    }

    @Test
    fun `stable aliases are capped per character`() {
        assertThrows(IllegalArgumentException::class.java) {
            NovelAudioCharacterRegistry.resolve(
                workKey = "work:author/title",
                existing = emptyList(),
                newCharacters = listOf(NewCharacter("tmp", "新角色")),
                aliasUpdates = listOf(
                    AliasUpdate("tmp", List(33) { "alias-$it" })
                )
            )
        }
    }

    @Test
    fun `manual aliases can be added and removed without changing formal ids`() {
        val store = MemoryCharacterStore()
        store.seed(
            "work:author/title",
            listOf(
                BookCharacter(
                    id = 7L,
                    bookUrl = "work:author/title",
                    name = "阿宁"
                )
            )
        )
        val registry = CharacterRegistry(store)

        val alias = registry.addManualAlias(
            workKey = "work:author/title",
            characterId = 7L,
            alias = "宁宁"
        )

        assertEquals(7L, alias.characterId)
        assertTrue(registry.removeManualAlias("work:author/title", "宁宁"))
        assertFalse(registry.removeManualAlias("work:author/title", "宁宁"))
        assertEquals(listOf("阿宁"), store.characters("work:author/title").map { it.name })
    }

    @Test
    fun `merge and revoke do not rewrite frozen plan history`() {
        val workKey = "work:author/title"
        val store = MemoryCharacterStore()
        store.seed(
            workKey,
            listOf(
                BookCharacter(id = 1L, bookUrl = workKey, name = "甲"),
                BookCharacter(id = 2L, bookUrl = workKey, name = "乙")
            )
        )
        store.seedAlias(
            NovelAudioAliasEntity(
                workKey = workKey,
                characterId = 2L,
                alias = "乙儿",
                normalizedAlias = "乙儿",
                source = "manual"
            )
        )
        store.seedBinding(
            NovelAudioVoiceBindingEntity(
                scope = "server-scope",
                characterId = 2L,
                voiceAssetId = "voice-乙",
                revision = 1L
            )
        )
        val registry = CharacterRegistry(store)

        val merge = registry.merge(
            workKey = workKey,
            primaryCharacterId = 1L,
            absorbedCharacterId = 2L
        )

        assertEquals(1L, merge.primaryCharacterId)
        assertEquals(1L, store.aliases(workKey).single { it.alias == "乙儿" }.characterId)
        assertEquals(
            listOf(1L),
            store.voiceBindings("server-scope").map { it.characterId }
        )
        assertEquals(1, store.mergeRecords(workKey).size)
        assertEquals(
            1L,
            registry.resolve(
                workKey = workKey,
                newCharacters = listOf(NewCharacter("tmp-after-merge", "乙"))
            ).temporaryToCharacterId["tmp-after-merge"]
        )

        assertTrue(registry.revokeLatestMerge(workKey))
        assertEquals(2L, store.aliases(workKey).single { it.alias == "乙儿" }.characterId)
        assertEquals(
            listOf(2L),
            store.voiceBindings("server-scope").map { it.characterId }
        )
    }

    @Test
    fun `revoking a merge preserves a later manual primary voice change`() {
        val workKey = "work:author/title"
        val store = MemoryCharacterStore()
        store.seed(
            workKey,
            listOf(
                BookCharacter(id = 1L, bookUrl = workKey, name = "甲"),
                BookCharacter(id = 2L, bookUrl = workKey, name = "乙")
            )
        )
        store.seedBinding(
            NovelAudioVoiceBindingEntity(
                scope = "server-scope",
                characterId = 2L,
                voiceAssetId = "voice-old",
                revision = 1L
            )
        )
        val registry = CharacterRegistry(store)
        registry.merge(workKey, 1L, 2L)
        store.upsertVoiceBinding(
            NovelAudioVoiceBindingEntity(
                scope = "server-scope",
                characterId = 1L,
                voiceAssetId = "voice-manual",
                revision = 2L
            )
        )

        assertTrue(registry.revokeLatestMerge(workKey))
        assertEquals(
            "voice-manual",
            store.voiceBindings("server-scope").single { it.characterId == 1L }.voiceAssetId
        )
        assertEquals(
            "voice-old",
            store.voiceBindings("server-scope").single { it.characterId == 2L }.voiceAssetId
        )
    }

    @Test
    fun `an active character merge cannot be applied twice`() {
        val workKey = "work:author/title"
        val store = MemoryCharacterStore()
        store.seed(
            workKey,
            listOf(
                BookCharacter(id = 1L, bookUrl = workKey, name = "甲"),
                BookCharacter(id = 2L, bookUrl = workKey, name = "乙")
            )
        )
        val registry = CharacterRegistry(store)
        registry.merge(workKey, 1L, 2L)

        assertThrows(IllegalArgumentException::class.java) {
            registry.merge(workKey, 1L, 2L)
        }
    }

    @Test
    fun `voice binding never reuses another character and revision changes only on replacement`() {
        val first = VoiceAsset("voice-a", "A")
        val second = VoiceAsset("voice-b", "B")
        val binding = NovelAudioVoiceBindingRegistry.bind(
            scope = "server-scope",
            characterId = 1L,
            existing = emptyList(),
            candidates = listOf(first, second)
        )
        val unchanged = NovelAudioVoiceBindingRegistry.bind(
            scope = "server-scope",
            characterId = 1L,
            existing = listOf(binding),
            candidates = listOf(first, second)
        )
        val retainedWhenNotReturnedByMatcher = NovelAudioVoiceBindingRegistry.bind(
            scope = "server-scope",
            characterId = 1L,
            existing = listOf(binding),
            candidates = listOf(second)
        )
        val changed = NovelAudioVoiceBindingRegistry.bind(
            scope = "server-scope",
            characterId = 1L,
            existing = listOf(binding),
            candidates = listOf(second),
            replace = true
        )

        assertEquals("voice-a", binding.voiceAssetId)
        assertEquals(binding.revision, unchanged.revision)
        assertEquals(binding, retainedWhenNotReturnedByMatcher)
        assertNotEquals(binding.revision, changed.revision)
        assertThrows(NovelAudioVoiceBindingRegistry.NoUnusedVoice::class.java) {
            NovelAudioVoiceBindingRegistry.bind(
                scope = "server-scope",
                characterId = 2L,
                existing = listOf(binding),
                candidates = listOf(first)
            )
        }
    }
}
