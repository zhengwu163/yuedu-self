package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.readaloud.analysis.ChapterParagraphSnapshot
import io.legado.app.help.readaloud.analysis.ChapterTextSnapshot
import io.legado.app.help.readaloud.analysis.ParsedTextUnit
import io.legado.app.help.readaloud.analysis.TextUnitParser
import io.legado.app.help.readaloud.role.FrozenReadAloudPreprocessRules
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig

data class NovelAudioChapterSnapshot(
    val workKey: String,
    val physicalBookKey: String,
    val chapter: NovelAudioChapterIdentity,
    val textSnapshot: ChapterTextSnapshot,
    val rules: FrozenReadAloudPreprocessRules,
    val snapshotHash: String = NovelAudioIdentity.storageKey(
        workKey,
        physicalBookKey,
        chapter.chapterKey,
        textSnapshot.textHash,
        TextUnitParser.ANALYSIS_VERSION,
        rules.version
    )
) {
    val rulesVersion: String
        get() = rules.version

    fun parseUnits(): List<ParsedTextUnit> = TextUnitParser.parse(textSnapshot, rules)

    override fun toString(): String {
        return "NovelAudioChapterSnapshot(workKey=$workKey, physicalBookKey=$physicalBookKey, " +
            "chapterIndex=${chapter.index}, snapshotHash=$snapshotHash, " +
            "paragraphCount=${textSnapshot.paragraphs.size}, rulesVersion=$rulesVersion)"
    }
}

object NovelAudioChapterSnapshotFactory {

    fun fromBook(
        book: Book,
        chapter: BookChapter,
        content: BookContent,
        rules: FrozenReadAloudPreprocessRules = ReadAloudPreprocessRuleConfig.current().freeze()
    ): NovelAudioChapterSnapshot {
        val identity = NovelAudioIdentity.forBook(book)
        return create(
            workKey = identity.workKey,
            physicalBookUrl = book.bookUrl,
            chapter = NovelAudioIdentity.chapter(
                physicalBookUrl = book.bookUrl,
                workKey = identity.workKey,
                index = chapter.index,
                url = chapter.url,
                baseUrl = chapter.baseUrl
            ),
            strings = content.textList,
            rules = rules
        )
    }

    fun fromStrings(
        bookUrl: String,
        chapterIndex: Int,
        chapterUrl: String,
        strings: List<String>,
        title: String = "",
        baseUrl: String = "",
        workKey: String = NovelAudioIdentity.workKey(title, "", bookUrl),
        rules: FrozenReadAloudPreprocessRules = ReadAloudPreprocessRuleConfig.current().freeze()
    ): NovelAudioChapterSnapshot {
        return create(
            workKey = workKey,
            physicalBookUrl = bookUrl,
            chapter = NovelAudioIdentity.chapter(
                physicalBookUrl = bookUrl,
                workKey = workKey,
                index = chapterIndex,
                url = chapterUrl,
                baseUrl = baseUrl
            ),
            strings = strings,
            rules = rules
        )
    }

    private fun create(
        workKey: String,
        physicalBookUrl: String,
        chapter: NovelAudioChapterIdentity,
        strings: List<String>,
        rules: FrozenReadAloudPreprocessRules
    ): NovelAudioChapterSnapshot {
        require(workKey.isNotBlank())
        require(physicalBookUrl.isNotBlank())
        val paragraphs = strings.toList().mapIndexed { index, text ->
            val start = strings.take(index).sumOf { it.length + 1 }
            ChapterParagraphSnapshot(
                paragraphIndex = index,
                chapterStart = start,
                text = text
            )
        }
        return NovelAudioChapterSnapshot(
            workKey = workKey,
            physicalBookKey = chapter.physicalBookKey,
            chapter = chapter,
            textSnapshot = ChapterTextSnapshot(paragraphs),
            rules = rules
        )
    }
}
