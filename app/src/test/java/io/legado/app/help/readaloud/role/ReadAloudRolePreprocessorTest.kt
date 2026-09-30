package io.legado.app.help.readaloud.role

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudRolePreprocessorTest {
    @Test
    fun quotedSentenceBecomesAiDialogueUnit() {
        val result = ReadAloudRolePreprocessor.process(listOf("他说：“你好。”"))

        assertEquals(ReadAloudRolePreprocessor.VERSION, result.version)
        val dialogue = result.units.single { it.kind == "dialogue" }
        assertEquals("dialogue", dialogue.kind)
        assertTrue(dialogue.needsAi)
    }
}
