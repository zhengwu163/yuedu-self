package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookIdentity
import io.legado.app.help.readaloud.speech.SpeechRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NovelAudioCharacterScopeTest {
    private val txt = Book(bookUrl = "content://local/test.txt", name = "sample", author = "")
    private val novelEngine = SpeechRoute(engineType = SpeechRoute.ENGINE_NOVEL_AUDIO,
        engineValue = "novel_audio").toJson()

    @Test fun authorlessTxtRolesUseTheAnalysisIdentity() {
        assertEquals(NovelAudioIdentity.workKey(txt), NovelAudioCharacterScope.key(txt, novelEngine))
        assertNotEquals(BookIdentity.key(txt), NovelAudioCharacterScope.key(txt, novelEngine))
    }

    @Test fun systemAndHttpRolesKeepTheirExistingScope() {
        listOf(null, "", "system", "12").forEach {
            assertEquals(BookIdentity.key(txt), NovelAudioCharacterScope.key(txt, it))
        }
    }

    @Test fun managementPreservesTheRequestedNovelScopeButRejectsAnotherBook() {
        assertEquals(NovelAudioIdentity.workKey(txt),
            NovelAudioCharacterScope.forManagement(txt, NovelAudioIdentity.workKey(txt)))
        assertEquals(BookIdentity.key(txt), NovelAudioCharacterScope.forManagement(txt, "work:another/book"))
        assertEquals(BookIdentity.key(txt), NovelAudioCharacterScope.forManagement(txt, null))
    }

    @Test fun missingBookHasNoCharacterScope() {
        assertEquals("", NovelAudioCharacterScope.key(null, novelEngine))
        assertEquals("", NovelAudioCharacterScope.forManagement(null, "work:another/book"))
    }
}
