package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudRolePreprocessorTest {
    @org.junit.Test fun shortStandaloneReplyIsDialogueButQuotedTermStaysNarrator() {
        val result = ReadAloudRolePreprocessor.process(listOf("“好”", "他说这叫“晴窗”。"))
        org.junit.Assert.assertEquals("character", result.units.first().roleType)
        org.junit.Assert.assertEquals("narrator", result.units.first { it.text == "“晴窗”" }.roleType)
    }
    @Test
    fun quotedSentenceBecomesAiDialogueUnit() {
        val result = ReadAloudRolePreprocessor.process(listOf("他说：“你好。”"))

        assertEquals(ReadAloudRolePreprocessor.VERSION, result.version)
        val dialogue = result.units.single { it.kind == "dialogue" }
        assertEquals("dialogue", dialogue.kind)
        assertTrue(dialogue.needsAi)
    }
}
