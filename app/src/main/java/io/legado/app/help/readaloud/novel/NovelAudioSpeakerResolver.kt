package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.help.readaloud.analysis.ParsedTextUnit

/** Explicit reporting clauses override model guesses; ambiguous clauses remain model decisions. */
object NovelAudioSpeakerResolver {
    private const val VERB = "(?:说道|问道|答道|喊道|叫道|笑道|吼道|说|问|答|喊|叫|道)"
    private const val MODIFIER = "(?:轻声|低声|大声|小声|冷冷地|笑着|哭着|接着|继续|又|便|才|连忙|缓缓|突然|认真地|平静地|不禁|开口|地)*"

    fun explicitSpeaker(
        units: List<ParsedTextUnit>, index: Int,
        characters: List<BookCharacter>, aliases: List<NovelAudioAliasEntity>
    ): Long? {
        val unit = units.getOrNull(index) ?: return null
        if (unit.roleType == "narrator") return 0L
        val before = units.getOrNull(index - 1)?.takeIf { it.roleType == "narrator" }
            ?.text?.takeLast(80).orEmpty()
        val after = units.getOrNull(index + 1)?.takeIf { it.roleType == "narrator" }
            ?.text?.take(80).orEmpty()
        val hits = characters.filter { character ->
            val names = listOf(character.name) + aliases.filter { it.characterId == character.id }.map { it.alias }
            names.filter { it.isNotBlank() }.any { name ->
                val stem = Regex.escape(name) + "\\s*" + MODIFIER + VERB
                Regex(stem + "[：:，,\\s]*$").containsMatchIn(before) ||
                    Regex("^[，,。！？\\s]*" + stem).containsMatchIn(after)
            }
        }.map { it.id }.distinct()
        return hits.singleOrNull()
    }
}
