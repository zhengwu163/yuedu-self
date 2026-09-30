package io.legado.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the Context overload used by both engine pickers, without saving any preference. */
@RunWith(AndroidJUnit4::class)
class NovelAudioCatalogDeviceTest {
    @Test
    fun playbackCatalogPreservesVoiceOrderAndKeepsChapterEngineOutOfRoleAssignment() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val http = listOf(HttpTTS(id = -916L, name = "目录隔离测试"))
        val voices = SpeechVoiceCatalogRepository.allGroups(context, http)
        val playback = SpeechVoiceCatalogRepository.playbackEngineGroups(context, http)

        assertTrue(voices.any { it.engineType == SpeechRoute.ENGINE_SYSTEM })
        assertEquals(SpeechRoute.ENGINE_HTTP, voices.last().engineType)
        assertEquals(voices, playback.drop(1))
        assertEquals(SpeechRoute.ENGINE_NOVEL_AUDIO, playback.first().engineType)
        assertEquals(1, playback.count { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO })
        assertFalse(voices.any { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO })
        listOf(http, emptyList()).forEach { source ->
            assertFalse(SpeechVoiceCatalogRepository.assignableRoutes(source)
                .any { it.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO })
        }
    }
}
