package io.legado.app.ui.book.character.compose

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCharacterComposeScreensTest {
    @Test fun fixedVoiceEntryIsOptionalAndUsesExistingManagementActions() {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.first { it.isDirectory }
        val source = File(root, "io/legado/app/ui/book/character/compose/BookCharacterComposeScreens.kt").readText()
        assertTrue(source.contains("onFixedVoices: (() -> Unit)? = null"))
        assertTrue(source.contains("onFixedVoices?.let { action -> AppManagementAction("))
        assertTrue(source.contains("text = \"固定音色\""))
        assertTrue(source.contains("rememberAppManagementPalette()"))
    }
}
