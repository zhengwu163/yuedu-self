package io.legado.app.help.readaloud.offline

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Builds opaque, deterministic filesystem identities for NovelAudio artifacts.
 *
 * Logical work keys and chapter URLs remain useful for matching in Room, but are
 * never written directly into a path. The profile is included only after the
 * server has returned it, so a guessed transport profile cannot alias a real file.
 */
object NovelAudioPathCodec {

    fun scopeDirectory(serverScope: String): String =
        "scope-${digest("server-scope", serverScope)}"

    fun workDirectory(workKey: String): String =
        "work-${digest("work-key", workKey)}"

    fun chapterDirectory(
        sourceBookUrl: String,
        chapterIndex: Int,
        chapterUrl: String
    ): String {
        return "chapter-${digest("physical-chapter", "$sourceBookUrl\u0000$chapterIndex\u0000$chapterUrl")}"
    }

    fun cacheKey(
        workKey: String,
        physicalChapterKey: String,
        segmentId: String,
        voiceAssetId: String,
        bindingRevision: Long,
        language: String,
        speed: Double,
        ttsProfile: String
    ): String {
        return digest(
            "audio-artifact",
            listOf(
                workKey,
                physicalChapterKey,
                segmentId,
                voiceAssetId,
                bindingRevision.toString(),
                language,
                speed.toString(),
                ttsProfile
            ).joinToString("\u0000")
        )
    }

    fun extensionFor(contentType: String): String {
        return when (contentType.lowercase(Locale.ROOT)) {
            "audio/ogg" -> "ogg"
            "audio/mp4" -> "mp4"
            "audio/aac" -> "aac"
            else -> throw IllegalArgumentException("unsupported audio content type")
        }
    }

    private fun digest(domain: String, value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$domain\u0000$value".toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }
}
