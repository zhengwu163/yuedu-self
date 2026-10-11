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
    fun novelPlayerBubblesUseTheSameFrozenPlanAsItsAudio() {
        val panel = source("ui/book/read/ReadAloudPlayerPanel.kt")
        assertTrue("Legacy cue indexes must not label frozen NovelAudio audio",
            panel.contains("NovelAudioPlaybackPresentation") && panel.contains("playbackPlanKey"))
    }

    @Test
    fun mediaTransitionUsesPlayerIndexForSeekAndAutomaticChanges() {
        val service = source("service/NovelAudioReadAloudService.kt")
        val transition = service.substringAfter("override fun onMediaItemTransition")
            .substringBefore("override fun onPlayerError")
        assertTrue("Seeking and automatic transitions both publish the current audio cue",
            transition.contains("currentMediaItemIndex") && !transition.contains("currentSegmentIndex++"))
    }

    @Test
    fun characterManagementOffersLocalFixedVoices() {
        val activity = source("ui/book/character/BookCharacterManageActivity.kt")
        assertTrue("Legacy system and HTTP voice selectors cannot save a NovelAudio binding",
            activity.contains("setVoiceBinding") && activity.contains("onFixedVoices"))
    }

    @Test fun aNewFrozenPlanWinsEvenIfItsSnapshotGenerationIsNumericallyLower() {
        val dao = source("data/dao/NovelAudioDao.kt")
        val query = dao.substringBefore("fun currentChapterPlan").substringAfterLast("@Query(")
        assertTrue("Snapshot hashes are not chronological generation counters",
            query.contains("createdAt DESC, generation DESC"))
    }

    @Test fun explicitRegenerationDropsOldPreparedPlayback() {
        val activity = source("ui/book/character/BookCharacterManageActivity.kt")
        assertTrue(activity.indexOf("ReadAloud.stop(this)") >= 0 &&
            activity.indexOf("ReadAloud.stop(this)") < activity.indexOf("NovelAudioPinnedChapterPreparer.prepare("))
    }

    @Test fun replacementReadyChapterSuppressesOldInterruptedRecovery() {
        val dao = source("data/dao/NovelAudioDao.kt")
        val query = dao.substringBefore("fun recoverablePinnedTasks").substringAfterLast("@Query(")
        assertTrue("An older partial task must not load models for an already replaced ready chapter",
            query.contains("NOT EXISTS") && query.contains("replacement.createdAt >= original.createdAt"))
    }

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
        val returnsSafeReason = coordinator.contains("Result.Failed(downloadFailureReason(error))") ||
            (coordinator.contains("val reason = downloadFailureReason(error)") &&
                coordinator.contains("Result.Failed(reason)"))
        assertTrue(
            "A class name alone hides RESOURCE/TIMEOUT diagnosis from callers",
            returnsSafeReason && coordinator.contains("error.kind")
        )
    }
}
