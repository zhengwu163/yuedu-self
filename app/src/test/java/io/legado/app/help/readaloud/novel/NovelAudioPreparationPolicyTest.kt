package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.speech.SpeechRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAudioPreparationPolicyTest {

    private val novelAudio = SpeechRoute(engineType = SpeechRoute.ENGINE_NOVEL_AUDIO)

    @Test
    fun `only explicit user playback on novel audio route prepares`() {
        assertTrue(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = novelAudio,
                play = true,
                userInitiated = true,
                prefetchRequest = null,
                prefetchRequestValid = false
            )
        )
    }

    @Test
    fun `ordinary reading and resume do not prepare`() {
        assertFalse(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = novelAudio,
                play = true,
                userInitiated = false,
                prefetchRequest = null,
                prefetchRequestValid = false
            )
        )
        assertFalse(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = novelAudio,
                play = false,
                userInitiated = true,
                prefetchRequest = null,
                prefetchRequestValid = false
            )
        )
        assertTrue(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = novelAudio,
                play = true,
                userInitiated = false,
                prefetchRequest = "already-issued",
                prefetchRequestValid = true
            )
        )
        assertFalse(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = novelAudio,
                play = true,
                userInitiated = false,
                prefetchRequest = "stale",
                prefetchRequestValid = false
            )
        )
    }

    @Test
    fun `system and http routes never prepare novel audio`() {
        assertFalse(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = SpeechRoute(engineType = SpeechRoute.ENGINE_SYSTEM),
                play = true,
                userInitiated = true,
                prefetchRequest = null,
                prefetchRequestValid = false
            )
        )
        assertFalse(
            NovelAudioPreparationPolicy.shouldPrepare(
                route = SpeechRoute(engineType = SpeechRoute.ENGINE_HTTP),
                play = true,
                userInitiated = true,
                prefetchRequest = null,
                prefetchRequestValid = false
            )
        )
    }
}
