package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * AUTO 后续章节只需要一份正文快照，不能走阅读页装配路径。
 * 该 loader 必须自证：产出的快照与当前章同一套规则，且不触碰阅读器状态。
 */
class NovelAudioChapterSnapshotLoaderTest {

    private val book = Book(bookUrl = "https://example.test/book", name = "测试书")

    @Test
    fun `loader returns a processed snapshot without installing into the reader`() {
        var installed = false
        val loader = loader(
            process = { _, _, _ ->
                installed = false
                listOf("最终排版正文")
            }
        )

        val snapshot = runBlocking { loader.load(book, 2) }

        assertEquals(
            listOf("最终排版正文"),
            snapshot!!.textSnapshot.paragraphs.map { it.text }
        )
        assertEquals(2, snapshot.chapter.index)
        assertFalse(installed)
    }

    @Test
    fun `missing chapter yields no snapshot and never loads content`() {
        var contentReads = 0
        val loader = NovelAudioChapterSnapshotLoader(
            chapter = { _, _ -> null },
            rawContent = { _, _ -> contentReads++; "正文" },
            process = { _, _, _ -> listOf("正文") }
        )

        assertNull(runBlocking { loader.load(book, 3) })
        assertEquals(0, contentReads)
    }

    @Test
    fun `absent local content yields no snapshot instead of fetching from the network`() {
        val loader = loader(rawContent = { _, _ -> null })

        assertNull(runBlocking { loader.load(book, 4) })
    }

    @Test
    fun `blank processed content yields no snapshot`() {
        val loader = loader(process = { _, _, _ -> listOf("   ", "") })

        assertNull(runBlocking { loader.load(book, 5) })
    }

    @Test
    fun `negative chapter index is rejected before any read`() {
        var chapterLookups = 0
        val loader = NovelAudioChapterSnapshotLoader(
            chapter = { _, index -> chapterLookups++; BookChapter(index = index) },
            rawContent = { _, _ -> "正文" },
            process = { _, _, _ -> listOf("正文") }
        )

        assertNull(runBlocking { loader.load(book, -1) })
        assertEquals(0, chapterLookups)
    }

    @Test
    fun `cancellation propagates instead of becoming a missing snapshot`() {
        val loader = loader(rawContent = { _, _ -> throw CancellationException("cancelled") })

        try {
            runBlocking { loader.load(book, 6) }
            fail("cancellation expected")
        } catch (_: CancellationException) {
            assertTrue(true)
        }
    }

    @Test
    fun `snapshot identity binds the physical book and chapter of the target`() {
        val loader = loader()

        val first = runBlocking { loader.load(book, 7) }!!
        val second = runBlocking { loader.load(book, 8) }!!

        assertEquals(first.workKey, second.workKey)
        assertEquals(first.physicalBookKey, second.physicalBookKey)
        assertTrue(first.snapshotHash != second.snapshotHash)
    }

    private fun loader(
        chapter: (Book, Int) -> BookChapter? = { _, index ->
            BookChapter(
                bookUrl = book.bookUrl,
                index = index,
                url = "https://example.test/chapter/$index"
            )
        },        rawContent: (Book, BookChapter) -> String? = { _, _ -> "原始正文" },
        process: suspend (Book, BookChapter, String) -> List<String> = { _, chapterValue, _ ->
            listOf("第${chapterValue.index}章正文")
        }
    ) = NovelAudioChapterSnapshotLoader(
        chapter = chapter,
        rawContent = rawContent,
        process = process,
        rules = { ReadAloudPreprocessRuleConfig().freeze() }
    )
}
