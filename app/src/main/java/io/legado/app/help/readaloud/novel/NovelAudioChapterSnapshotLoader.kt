package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.appDb
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.ParagraphRuleProcessor
import io.legado.app.help.readaloud.role.FrozenReadAloudPreprocessRules
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig

/**
 * 只读加载后续章节的正文快照，供 AUTO 预取使用。
 *
 * 与阅读页的装载路径刻意分开：这里不安装章节槽位、不刷新正文视图、不产生
 * TextChapter，因此预取三章不会改变用户当前阅读位置。正文来源只取已缓存内容，
 * 缺失即返回 null；离线预取不会因此去书源抓取正文。
 */
class NovelAudioChapterSnapshotLoader(
    private val chapter: (Book, Int) -> BookChapter?,
    private val rawContent: (Book, BookChapter) -> String?,
    private val process: suspend (Book, BookChapter, String) -> List<String>,
    // 规则与当前章一致；调用方注入以便快照散列可复现，测试也无需 Android 配置。
    private val rules: () -> FrozenReadAloudPreprocessRules = {
        ReadAloudPreprocessRuleConfig.current().freeze()
    }
) {

    suspend fun load(book: Book, chapterIndex: Int): NovelAudioChapterSnapshot? {
        if (chapterIndex < 0 || book.bookUrl.isBlank()) return null
        val target = chapter(book, chapterIndex) ?: return null
        // 只读已缓存正文；取消原样向外传播，不能被当作“本地无正文”。
        val raw = rawContent(book, target) ?: return null
        val paragraphs = process(book, target, raw).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return null
        return NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = book.bookUrl,
            chapterIndex = target.index,
            chapterUrl = target.url,
            strings = paragraphs,
            title = book.name,
            baseUrl = target.baseUrl,
            workKey = NovelAudioIdentity.forBook(book).workKey,
            rules = rules()
        )
    }

    companion object {
        /** 生产实例：只读 Room 章节与已缓存正文，复用当前正文与段落规则。 */
        fun create(): NovelAudioChapterSnapshotLoader = NovelAudioChapterSnapshotLoader(
            chapter = { book, index ->
                appDb.bookChapterDao.getChapter(book.bookUrl, index)
            },
            rawContent = { book, chapter -> BookHelp.getContent(book, chapter) },
            process = { book, chapter, raw ->
                val processed = ContentProcessor.get(book).getContent(
                    book = book,
                    chapter = chapter,
                    content = raw,
                    includeTitle = false
                )
                ParagraphRuleProcessor.process(book, chapter, processed).textList
            }
        )
    }
}
