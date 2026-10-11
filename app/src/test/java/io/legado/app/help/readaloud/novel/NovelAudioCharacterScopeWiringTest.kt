package io.legado.app.help.readaloud.novel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TXT without an author uses an opaque NovelAudio key, unlike legacy roles. */
class NovelAudioCharacterScopeWiringTest {
    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }
    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test fun playerPreviewUsesTheSameIdentityAsChapterAnalysis() {
        val panel = source("ui/book/read/ReadAloudPlayerPanel.kt")
        assertTrue(panel.contains("val characterPreview = buildCharacterPreview(\n            NovelAudioCharacterScope.key(book, ReadAloud.ttsEngine)") &&
            panel.contains("characterPreview = characterPreview,"))
    }

    @Test fun roleManagementCarriesAndReadsTheResolvedScope() {
        val reader = source("ui/book/read/ReadBookActivity.kt").substringAfter("override fun openBookCharacters()")
            .substringBefore("override fun")
        val manager = source("ui/book/character/BookCharacterManageActivity.kt")
        assertTrue(reader.contains("EXTRA_CHARACTER_BOOK_KEY") && reader.contains("NovelAudioCharacterScope.key"))
        assertTrue(manager.contains("intent.getStringExtra(EXTRA_CHARACTER_BOOK_KEY)"))
    }

    @Test fun anOpenPlayerRefreshesAfterAnalysisCreatesCharacters() {
        val panel = source("ui/book/read/ReadAloudPlayerPanel.kt")
        val cache = panel.substringAfter("val modelKey = listOf(").substringBefore(").joinToString(\"|\")")
        assertTrue("New roles and engine changes must invalidate an already open player",
            cache.contains("characterPreview.hashCode().toString()") && cache.contains("ReadAloud.ttsEngine.orEmpty()"))
    }
}
