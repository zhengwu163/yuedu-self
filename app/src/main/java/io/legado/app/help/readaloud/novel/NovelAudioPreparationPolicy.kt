package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.speech.SpeechRoute

/**
 * Keeps the user-intent boundary for NovelAudio preparation independent from
 * Android services so it can be verified without starting a Service.
 */
internal object NovelAudioPreparationPolicy {

    fun shouldPrepare(
        route: SpeechRoute,
        play: Boolean,
        userInitiated: Boolean,
        prefetchRequest: String?,
        prefetchRequestValid: Boolean
    ): Boolean {
        return play &&
            (userInitiated || (prefetchRequest != null && prefetchRequestValid)) &&
            route.engineType == SpeechRoute.ENGINE_NOVEL_AUDIO
    }
}
