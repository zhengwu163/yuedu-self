package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookIdentity
import io.legado.app.help.readaloud.speech.SpeechRoute

/** Keep legacy role storage intact; NovelAudio uses its analysis identity even for authorless TXT. */
object NovelAudioCharacterScope {
    fun key(book: Book?, engineValue: String?): String {
        book ?: return ""
        return if (SpeechRoute.fromTtsEngineValue(engineValue).engineType == SpeechRoute.ENGINE_NOVEL_AUDIO) {
            NovelAudioIdentity.workKey(book)
        } else {
            BookIdentity.key(book)
        }
    }

    fun forManagement(book: Book?, requestedKey: String?): String {
        book ?: return ""
        val legacy = BookIdentity.key(book)
        val novel = NovelAudioIdentity.workKey(book)
        // An exported Activity must not accept another book's private role scope.
        return requestedKey?.takeIf { it == legacy || it == novel } ?: legacy
    }
}
