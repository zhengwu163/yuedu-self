package io.legado.app.help.readaloud.novel

/**
 * Small synchronized state holder used to prevent an older preparation callback
 * from clearing a newer user request.
 */
internal class NovelAudioPreparationState {

    internal data class Request(
        val bookUrl: String,
        val chapterIndex: Int,
        val retention: String
    )

    private var request: Request? = null

    @Synchronized
    fun replace(bookUrl: String, chapterIndex: Int, retention: String): Request {
        return Request(bookUrl, chapterIndex, retention).also { request = it }
    }

    @Synchronized
    fun current(): Request? = request

    @Synchronized
    fun clear() {
        request = null
    }

    @Synchronized
    fun clearIfCurrent(expected: Request): Boolean {
        if (request !== expected) return false
        request = null
        return true
    }
}
