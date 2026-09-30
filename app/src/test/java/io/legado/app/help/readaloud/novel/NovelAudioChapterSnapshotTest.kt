package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.readaloud.analysis.TextUnitParser
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.role.ReadAloudQuotePair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioChapterSnapshotTest {

    @Test
    fun `factory preserves final content list including blank paragraphs and UTF16`() {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "https://book.example/1",
            chapterIndex = 4,
            chapterUrl = "chapter/4",
            title = "第四章",
            strings = listOf("😀甲", "", "乙\r"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )

        assertEquals("😀甲\n\n乙\r", snapshot.textSnapshot.text)
        assertEquals(listOf(0, 1, 2), snapshot.textSnapshot.paragraphs.map { it.paragraphIndex })
        assertTrue(snapshot.snapshotHash.isNotBlank())
        assertEquals(4, snapshot.chapter.index)
        assertTrue(snapshot.rulesVersion.isNotBlank())
    }

    @Test
    fun `book content factory uses the frozen rules when parsing`() {
        val rules = ReadAloudPreprocessRuleConfig(
            quotePairs = listOf(ReadAloudQuotePair("《", "》")),
            dialogueMinLength = 1
        ).freeze()
        val snapshot = NovelAudioChapterSnapshotFactory.fromBook(
            book = Book(bookUrl = "book://one", name = "作品", author = "作者"),
            chapter = BookChapter(
                bookUrl = "book://one",
                index = 0,
                url = "chapter-0",
                title = "第一章"
            ),
            content = BookContent(
                sameTitleRemoved = false,
                textList = listOf("《你好！》"),
                effectiveReplaceRules = emptyList()
            ),
            rules = rules
        )

        val units = TextUnitParser.parse(snapshot.textSnapshot, snapshot.rules)

        assertEquals(1, units.size)
        assertEquals("dialogue", units.single().kind)
        assertEquals(snapshot.textSnapshot.text, units.single().text)
    }

    @Test
    fun `rule changes participate in snapshot hash`() {
        val first = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 1,
            chapterUrl = "chapter",
            strings = listOf("正文"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        val second = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 1,
            chapterUrl = "chapter",
            strings = listOf("正文"),
            rules = ReadAloudPreprocessRuleConfig(dialogueMinLength = 1).freeze()
        )

        assertNotEquals(first.rulesVersion, second.rulesVersion)
        assertNotEquals(first.snapshotHash, second.snapshotHash)
    }
}
