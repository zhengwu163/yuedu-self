package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAudioSpeakerResolverTest {
    private val roles = listOf(BookCharacter(id = 12, name = "陈远"), BookCharacter(id = 9, name = "林青"))
    private fun units(text: String) = NovelAudioChapterSnapshotFactory.fromStrings(
        "book", 0, "c", listOf(text), rules = ReadAloudPreprocessRuleConfig().freeze()).parseUnits()

    @Test fun namedListenerDoesNotBecomeSpeaker() {
        val units = units("陈远看向林青：“明天在哪里见面？”")
        assertNull(NovelAudioSpeakerResolver.explicitSpeaker(units, 1, roles, emptyList()))
    }
    @Test fun suffixReportingClauseUsesStableAlias() {
        val units = units("“明天在哪里见面？”阿远低声问道。")
        assertEquals(12L, NovelAudioSpeakerResolver.explicitSpeaker(units, 0, roles,
            listOf(NovelAudioAliasEntity(workKey = "work", characterId = 12, alias = "阿远", normalizedAlias = "阿远"))))
    }
    @Test fun conflictingPrefixAndSuffixDoNotGuess() {
        val units = units("陈远说道：“明天在哪里见面？”林青问道。")
        assertNull(NovelAudioSpeakerResolver.explicitSpeaker(units, 1, roles, emptyList()))
    }

    @Test fun paragraphSeparatorAndNaturalSpeechModifierDoNotHideReportingCue() {
        val units = NovelAudioChapterSnapshotFactory.fromStrings(
            "book", 0, "c", listOf("陈远疑惑地问道：", "“明天在哪里见面？”"),
            rules = ReadAloudPreprocessRuleConfig().freeze()).parseUnits()
        val quote = units.indexOfFirst { it.roleType == "character" }
        assertEquals(12L, NovelAudioSpeakerResolver.explicitSpeaker(units, quote, roles, emptyList()))
    }
}
