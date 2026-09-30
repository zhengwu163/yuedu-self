package io.legado.app.help.readaloud.speech

import io.legado.app.data.entities.HttpTTS
import org.junit.Assert.*
import org.junit.Test

class NovelAudioPlaybackCatalogTest {
    @Test
    fun `playback catalog includes a single usable novel audio route`() {
        val groups = SpeechVoiceCatalogRepository.playbackEngineGroups(emptyList())
        val novel = groups.single { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO }
        val route = novel.options.single().toRoute()
        assertTrue(route.isConfigured)
        assertEquals(SpeechRoute.ENGINE_NOVEL_AUDIO, SpeechRoute.resolveSpeechRoute(route.toJson()).engineType)
        assertFalse(novel.options.single().explicitSpeaker)
        assertEquals("", novel.loginKey)
        assertNull(novel.loginUrl)
    }

    @Test
    fun `adding playback engine leaves normal voice groups unchanged`() {
        val normal = SpeechVoiceCatalogRepository.httpGroups(listOf(HttpTTS(id = 7, name = "Test TTS")))
        val result = SpeechVoiceCatalogRepository.playbackEngineGroups(normal)
        assertEquals(normal, result.filter { it.engineType != SpeechRoute.ENGINE_NOVEL_AUDIO })
        assertEquals(1, SpeechVoiceCatalogRepository.playbackEngineGroups(result)
            .count { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO })
        assertFalse(normal.any { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO })
    }
}
