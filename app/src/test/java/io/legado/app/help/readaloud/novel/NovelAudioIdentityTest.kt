package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NovelAudioIdentityTest {

    @Test
    fun `same title and author share work identity while different authors do not`() {
        val first = Book(bookUrl = "https://one.example/book?id=1", name = "  三体 ", author = " 刘慈欣 ")
        val second = Book(bookUrl = "https://two.example/book?id=9", name = "三体", author = "刘慈欣")
        val otherAuthor = second.copy(author = "另一个作者")

        assertEquals(
            NovelAudioIdentity.workKey(first),
            NovelAudioIdentity.workKey(second)
        )
        assertNotEquals(
            NovelAudioIdentity.workKey(first),
            NovelAudioIdentity.workKey(otherAuthor)
        )
    }

    @Test
    fun `missing author falls back to opaque physical identity`() {
        val url = "https://example.com/book?id=secret"
        val identity = NovelAudioIdentity.forBook(Book(bookUrl = url, name = "无作者"))

        assertEquals(identity.workKey, identity.physicalBookKey)
        assertFalse(identity.workKey.contains(url))
        assertFalse(identity.workKey.contains("secret"))
    }

    @Test
    fun `chapter identity includes physical book chapter index and url`() {
        val first = NovelAudioIdentity.chapter(
            physicalBookUrl = "book://local/one",
            workKey = "work:author/title",
            index = 2,
            url = "chapter-a",
            baseUrl = "https://example.com/novel/"
        )
        val second = NovelAudioIdentity.chapter(
            physicalBookUrl = "book://local/one",
            workKey = "work:author/title",
            index = 3,
            url = "chapter-a",
            baseUrl = "https://example.com/novel/"
        )
        val third = NovelAudioIdentity.chapter(
            physicalBookUrl = "book://local/one",
            workKey = "work:author/title",
            index = 2,
            url = "chapter-b",
            baseUrl = "https://example.com/novel/"
        )

        assertNotEquals(first.chapterKey, second.chapterKey)
        assertNotEquals(first.chapterKey, third.chapterKey)
        assertEquals(
            "https://example.com/novel/chapter-a",
            first.normalizedUrl
        )
    }

    @Test
    fun `server scope excludes token and only normalizes same base url`() {
        val scope = NovelAudioIdentity.serverScope(
            "https://audio.example/api/v1?token=do-not-store"
        )
        val sameBase = NovelAudioIdentity.normalizeUrl(
            "https://audio.example/api/",
            "chapter/1"
        )
        val otherBase = NovelAudioIdentity.normalizeUrl(
            "https://other.example/api/",
            "https://audio.example/api/chapter/1"
        )

        assertFalse(scope.contains("token"))
        assertEquals(
            NovelAudioIdentity.serverScope("https://audio.example/api/v1"),
            scope
        )
        assertEquals("https://audio.example/api/chapter/1", sameBase)
        assertEquals("https://audio.example/api/chapter/1", otherBase)
    }
}
