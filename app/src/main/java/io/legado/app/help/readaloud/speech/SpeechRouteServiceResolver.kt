package io.legado.app.help.readaloud.speech

import io.legado.app.service.HttpReadAloudService
import io.legado.app.service.NovelAudioReadAloudService
import io.legado.app.service.TTSReadAloudService

/**
 * 纯路由映射，不读取 AppConfig、ReadBook 或 Android Context，便于 JVM 回归测试。
 */
object SpeechRouteServiceResolver {

    fun routeToClass(route: SpeechRoute): Class<*> {
        return when (route.engineType) {
            SpeechRoute.ENGINE_HTTP -> HttpReadAloudService::class.java
            SpeechRoute.ENGINE_NOVEL_AUDIO -> NovelAudioReadAloudService::class.java
            else -> TTSReadAloudService::class.java
        }
    }
}
