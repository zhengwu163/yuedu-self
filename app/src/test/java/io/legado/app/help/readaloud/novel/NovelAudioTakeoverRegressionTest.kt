package io.legado.app.help.readaloud.novel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Integration wiring matters here: a correct helper alone cannot resume the player. */
class NovelAudioTakeoverRegressionTest {
    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test
    fun readyNotificationUsesTheCompletedPlanInsteadOfAnOlderGeneration() {
        val service = source("service/NovelAudioReadAloudService.kt")
        val observer = service.substringAfter("override fun observeLiveBus()")
            .substringBefore("override fun")
        assertTrue(
            "READY must pass its completed plan id into playback selection",
            observer.contains("prepareAndPlay(work, readyPlanId = state.planId)")
        )
    }

    @Test
    fun aBlockedPlayerDoesNotReplaceAnActivePreparationOfTheSameChapter() {
        val service = source("service/NovelAudioReadAloudService.kt")
        assertTrue(
            "The continuation decision must check the running chapter preparation",
            service.contains("alreadyPreparing = NovelAudioPreparationCoordinator.isPreparing(")
        )
    }

    @Test
    fun downloadFailurePreservesTheSafeServerKindForDiagnosis() {
        val coordinator = source("help/readaloud/offline/NovelAudioDownloadCoordinator.kt")
        assertTrue(
            "A class name alone hides RESOURCE/TIMEOUT diagnosis from callers",
            coordinator.contains("Result.Failed(downloadFailureReason(error))") &&
                coordinator.contains("error.kind")
        )
    }
}
