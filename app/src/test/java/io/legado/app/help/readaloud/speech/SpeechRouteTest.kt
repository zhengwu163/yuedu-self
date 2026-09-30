package io.legado.app.help.readaloud.speech

import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.NovelAudioReadAloudService
import io.legado.app.service.TTSReadAloudService
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRouteTest {

    @Test
    fun novelAudioRouteSelectsNovelAudioService() {
        assertEquals(
            NovelAudioReadAloudService::class.java,
            SpeechRouteServiceResolver.routeToClass(
                SpeechRoute(
                    engineType = SpeechRoute.ENGINE_NOVEL_AUDIO,
                    engineValue = "enabled"
                )
            )
        )
    }

    @Test
    fun legacyRoutesKeepTheirExistingServices() {
        assertEquals(
            HttpReadAloudService::class.java,
            SpeechRouteServiceResolver.routeToClass(
                SpeechRoute(
                    engineType = SpeechRoute.ENGINE_HTTP,
                    engineValue = "42"
                )
            )
        )
        assertEquals(
            TTSReadAloudService::class.java,
            SpeechRouteServiceResolver.routeToClass(SpeechRoute(SpeechRoute.ENGINE_SYSTEM))
        )
        assertEquals(
            TTSReadAloudService::class.java,
            SpeechRouteServiceResolver.routeToClass(SpeechRoute(SpeechRoute.ENGINE_DEFAULT))
        )
    }

    @Test
    fun unresolvedRouteFallsBackToExistingSystemService() {
        val route = SpeechRoute.resolveSpeechRoute("""{"engineType":"unknown"}""")
        assertEquals(SpeechRoute.ENGINE_DEFAULT, route.engineType)
        assertEquals(TTSReadAloudService::class.java, SpeechRouteServiceResolver.routeToClass(route))
    }
}
