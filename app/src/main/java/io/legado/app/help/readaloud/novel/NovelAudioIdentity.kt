package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.security.MessageDigest

data class NovelAudioBookIdentity(
    val workKey: String,
    val physicalBookKey: String,
    val title: String,
    val author: String
)

data class NovelAudioChapterIdentity(
    val workKey: String,
    val physicalBookKey: String,
    val physicalBookUrl: String,
    val index: Int,
    val url: String,
    val normalizedUrl: String,
    val chapterKey: String
)

object NovelAudioIdentity {

    fun forBook(book: Book): NovelAudioBookIdentity {
        return forBook(
            title = book.name,
            author = book.getRealAuthor().ifBlank { book.author },
            physicalBookUrl = book.bookUrl
        )
    }

    fun forBook(title: String?, author: String?, physicalBookUrl: String): NovelAudioBookIdentity {
        val normalizedTitle = normalizeText(title)
        val normalizedAuthor = normalizeText(author)
        val physicalKey = physicalKey(physicalBookUrl)
        val work = if (normalizedTitle.isNotBlank() && normalizedAuthor.isNotBlank()) {
            "work:$normalizedAuthor/$normalizedTitle"
        } else {
            physicalKey
        }
        return NovelAudioBookIdentity(work, physicalKey, normalizedTitle, normalizedAuthor)
    }

    fun workKey(book: Book): String = forBook(book).workKey

    fun workKey(title: String?, author: String?, physicalBookUrl: String = ""): String {
        return forBook(title, author, physicalBookUrl).workKey
    }

    fun physicalKey(physicalBookUrl: String): String {
        require(physicalBookUrl.isNotBlank())
        return "physical:${sha256(lengthPrefix(physicalBookUrl))}"
    }

    fun chapter(
        physicalBookUrl: String,
        workKey: String,
        index: Int,
        url: String,
        baseUrl: String = ""
    ): NovelAudioChapterIdentity {
        require(physicalBookUrl.isNotBlank())
        require(index >= 0)
        val physical = physicalKey(physicalBookUrl)
        val normalizedUrl = normalizeUrl(baseUrl, url)
        val chapterKey = "chapter:${sha256(lengthPrefix(physicalBookUrl) +
            lengthPrefix(index.toString()) +
            lengthPrefix(url) +
            lengthPrefix(normalizedUrl))}"
        return NovelAudioChapterIdentity(
            workKey = workKey,
            physicalBookKey = physical,
            physicalBookUrl = physicalBookUrl,
            index = index,
            url = url,
            normalizedUrl = normalizedUrl,
            chapterKey = chapterKey
        )
    }

    /**
     * Query parameters are intentionally dropped. The token is supplied only by the
     * credential provider and can never become part of the server scope.
     */
    fun serverScope(baseUrl: String): String {
        val uri = URI(baseUrl.trim())
        require(!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank())
        val path = uri.path.orEmpty().trimEnd('/')
        return URI(uri.scheme.lowercase(Locale.ROOT), null, uri.host.lowercase(Locale.ROOT),
            uri.port, path.ifBlank { "/" }, null, null).toString().trimEnd('/')
    }

    /**
     * Resolve relative chapter URLs against this exact base. An absolute URL is kept
     * as-is, so one server's normalization cannot rewrite another server's URL.
     */
    fun normalizeUrl(baseUrl: String, url: String): String {
        if (url.isBlank()) return ""
        val candidate = URI(url.trim())
        val resolved = if (candidate.isAbsolute) candidate else {
            if (baseUrl.isBlank()) return url.trim()
            URI(baseUrl.trim()).resolve(candidate)
        }
        val path = resolved.path.orEmpty().ifBlank { "/" }.replace(Regex("/{2,}"), "/")
        return URI(
            resolved.scheme?.lowercase(Locale.ROOT),
            resolved.userInfo,
            resolved.host?.lowercase(Locale.ROOT),
            resolved.port,
            path,
            resolved.query,
            null
        ).toString()
    }

    fun storageKey(vararg values: String): String {
        return sha256(values.joinToString("") { lengthPrefix(it) })
    }

    private fun normalizeText(value: String?): String {
        return Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), "")
            .lowercase(Locale.ROOT)
    }
}

private fun lengthPrefix(value: String): String = "${value.length}:$value"

private fun sha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
