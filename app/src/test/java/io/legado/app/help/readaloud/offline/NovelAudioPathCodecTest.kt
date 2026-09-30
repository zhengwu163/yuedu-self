package io.legado.app.help.readaloud.offline

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioPathCodecTest {

    @Test
    fun `path components are stable and opaque`() {
        val first = NovelAudioPathCodec.scopeDirectory("https://audio.example/v1")
        val second = NovelAudioPathCodec.scopeDirectory("https://audio.example/v1")

        assertTrue(first.startsWith("scope-"))
        assertTrue(first.matches(Regex("scope-[0-9a-f]{64}")))
        assertTrue(first == second)
        assertTrue(!first.contains("audio.example"))
        assertTrue(!first.contains('/'))
    }

    @Test
    fun `different logical work and physical chapter identities cannot collide`() {
        val workA = NovelAudioPathCodec.workDirectory("work:author-a/title")
        val workB = NovelAudioPathCodec.workDirectory("work:author-b/title")
        val chapterA = NovelAudioPathCodec.chapterDirectory("book-url", 1, "chapter-a")
        val chapterB = NovelAudioPathCodec.chapterDirectory("book-url", 2, "chapter-a")

        assertNotEquals(workA, workB)
        assertNotEquals(chapterA, chapterB)
        assertTrue(workA.none { it == '/' || it == '\\' })
        assertTrue(chapterA.none { it == '/' || it == '\\' })
    }

    @Test
    fun `cache key changes when actual tts profile changes`() {
        val first = NovelAudioPathCodec.cacheKey(
            workKey = "work:a/title",
            physicalChapterKey = "book-url|1|chapter-url",
            segmentId = "segment-1",
            voiceAssetId = "voice-1",
            bindingRevision = 1L,
            language = "zh-CN",
            speed = 1.0,
            ttsProfile = "profile-a"
        )
        val second = NovelAudioPathCodec.cacheKey(
            workKey = "work:a/title",
            physicalChapterKey = "book-url|1|chapter-url",
            segmentId = "segment-1",
            voiceAssetId = "voice-1",
            bindingRevision = 1L,
            language = "zh-CN",
            speed = 1.0,
            ttsProfile = "profile-b"
        )

        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
    }
}
