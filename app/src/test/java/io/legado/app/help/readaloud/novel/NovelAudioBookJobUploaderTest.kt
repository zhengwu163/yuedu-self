package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.data.entities.NovelAudioVoiceBindingEntity
import io.legado.app.help.readaloud.novel.NovelAudioBookJobUploader.Outcome
import io.legado.app.help.readaloud.novel.NovelAudioBookJobUploader.Pass
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.server.BookJobChapterUpload
import io.legado.app.help.readaloud.server.BookJobCreateRequest
import io.legado.app.help.readaloud.server.BookJobCreated
import io.legado.app.help.readaloud.server.NovelAudioServerException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAudioBookJobUploaderTest {
    private val dao = MemoryBookJobDao()
    private val store = MemoryCharacterStore()

    @Test fun orderStartsAtCurrentChapterThenWrapsToTheStart() {
        assertEquals(listOf(3, 4, 0, 1, 2), NovelAudioBookJobOrder.uploadOrder(5, 3))
        assertEquals(listOf(0, 1), NovelAudioBookJobOrder.uploadOrder(2, -7))
        assertEquals(listOf(2, 0, 1), NovelAudioBookJobOrder.uploadOrder(3, 99))
        assertEquals(emptyList<Int>(), NovelAudioBookJobOrder.uploadOrder(0, 0))
    }

    @Test fun createSendsOnlyThisWorkAndScope() = runBlocking {
        store.seed(WORK, listOf(BookCharacter(id = 7, bookUrl = WORK, name = "单牧", gender = "male")))
        store.seedAlias(NovelAudioAliasEntity(workKey = WORK, characterId = 7, alias = "老单", normalizedAlias = "老单"))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = SCOPE, characterId = 0, voiceAssetId = "N1", revision = 2))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = SCOPE, characterId = 7, voiceAssetId = "M1", revision = 1))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = "other", characterId = 7, voiceAssetId = "X", revision = 1))
        var sent: BookJobCreateRequest? = null
        val job = NovelAudioBookJobCreator(dao, store, { sent = it; BookJobCreated(JOB, 50, "uploading") }) { 5L }
            .create(WORK, BOOK, "哑钟", SCOPE, chapterCount = 3, currentIndex = 1)
        val request = checkNotNull(sent)
        assertEquals(listOf("7" to "单牧"), request.characters.map { it.characterId to it.displayName })
        assertEquals(listOf("老单"), request.aliases.map { it.alias })
        assertEquals(setOf("0" to "N1", "7" to "M1"), request.bindings.map { it.characterId to it.voiceAssetId }.toSet())
        assertEquals(50, job.maxSegmentChars)
        assertEquals(listOf(1, 2, 0), dao.chapters(JOB).map { it.chapterIndex })
        assertEquals(listOf("c1", "c2", "c0"), dao.chapters(JOB).map { it.chapterId })
    }

    @Test fun bindingSyncSkipsCharactersCreatedAfterTheJob() {
        store.seed(WORK, listOf(
            BookCharacter(id = 7, bookUrl = WORK, name = "单牧", createdAt = 10L),
            BookCharacter(id = 9, bookUrl = WORK, name = "曾晚", createdAt = 99L)))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = SCOPE, characterId = 0, voiceAssetId = "N2", revision = 3))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = SCOPE, characterId = 7, voiceAssetId = "M2", revision = 2))
        store.seedBinding(NovelAudioVoiceBindingEntity(scope = SCOPE, characterId = 9, voiceAssetId = "F1", revision = 1))
        val bindings = NovelAudioBookJobCreator.phoneBindings(store, WORK, SCOPE, createdUpTo = 50L)
        assertEquals(setOf("0" to "N2", "7" to "M2"), bindings.map { it.characterId to it.voiceAssetId }.toSet())
        assertEquals(3, NovelAudioBookJobCreator.phoneBindings(store, WORK, SCOPE, createdUpTo = null).size)
    }

    @Test fun uploadsInOrderSkipsMissingTextAndFinishes() = runBlocking {
        createJob(chapterCount = 3, current = 1)
        val uploads = mutableListOf<Pair<String, BookJobChapterUpload>>()
        val outcome = uploader(
            load = { index -> if (index == 2) null else snapshot(index, "林舟说：", "“走吧。”") },
            upload = { _, chapterId, body -> uploads += chapterId to body; true }
        ).run(JOB)
        assertEquals(NovelAudioBookJobUploader.Outcome.UPLOADED, outcome)
        assertEquals(listOf("c1", "c0"), uploads.map { it.first })
        assertEquals(NovelAudioBookJobStates.UPLOADED, dao.job(JOB)?.state)
        assertEquals(NovelAudioBookJobUploadStates.MISSING, dao.chapter(JOB, 2)?.uploadState)
        val body = uploads.first().second
        assertEquals(1, body.chapterIndex)
        assertEquals("narrator", body.units.first().roleType)
        assertEquals("character", body.units.last().roleType)
        assertEquals(dao.chapter(JOB, 1)?.snapshotHash, body.snapshotHash)
    }

    @Test fun piecesFollowTheHostSegmentLimit() {
        val long = "“" + "一二三四五六七八九十，".repeat(12) + "”"
        val body = checkNotNull(NovelAudioBookJobUploader.chapterBody(snapshot(0, long), 0, 50))
        val pieces = body.units.single().pieces
        assert(pieces.size > 1)
        assertEquals(body.units.single().text, pieces.joinToString(""))
        val whole = checkNotNull(NovelAudioBookJobUploader.chapterBody(snapshot(0, long), 0, 0))
        assertEquals(1, whole.units.single().pieces.size)
    }

    @Test fun networkLossStopsAndResumesWithoutReuploading() = runBlocking {
        createJob(chapterCount = 3, current = 0)
        var online = true
        var failed = false
        val uploads = mutableListOf<String>()
        val uploader = uploader(
            load = { snapshot(it, "天黑了。") },
            upload = { _, chapterId, _ ->
                if (chapterId == "c1" && !failed) {
                    failed = true
                    online = false
                    throw NovelAudioServerException("UNAVAILABLE")
                }
                uploads += chapterId; true
            },
            canContinue = { online }
        )
        assertEquals(NovelAudioBookJobUploader.Outcome.WAITING_NETWORK, uploader.run(JOB))
        assertEquals(listOf("c0"), uploads)
        online = true
        assertEquals(NovelAudioBookJobUploader.Outcome.UPLOADED, uploader.run(JOB))
        assertEquals(listOf("c0", "c1", "c2"), uploads)
    }

    @Test fun pausedJobStopsAndRemovedHostJobCancels() = runBlocking {
        createJob(chapterCount = 2, current = 0)
        dao.setJobState(JOB, NovelAudioBookJobStates.PAUSED, "", 1L)
        assertEquals(NovelAudioBookJobUploader.Outcome.STOPPED,
            uploader(load = { snapshot(it, "天黑了。") }, upload = { _, _, _ -> true }).run(JOB))
        dao.setJobState(JOB, NovelAudioBookJobStates.UPLOADING, "", 1L)
        val outcome = uploader(load = { snapshot(it, "天黑了。") },
            upload = { _, _, _ -> throw NovelAudioServerException("UNKNOWN_JOB") }).run(JOB)
        assertEquals(NovelAudioBookJobUploader.Outcome.JOB_GONE, outcome)
        assertEquals(NovelAudioBookJobStates.CANCELLED, dao.job(JOB)?.state)
        assertNull(dao.activeJobForBook(BOOK, NovelAudioBookJobStates.ACTIVE))
    }

    @Test fun serviceLoopStopsInsteadOfSpinning() {
        assertEquals(Pass.AGAIN, NovelAudioBookJobUploader.nextPass(listOf(Outcome.STOPPED, Outcome.UPLOADED), 9, 3))
        assertEquals(Pass.WAIT, NovelAudioBookJobUploader.nextPass(listOf(Outcome.WAITING_NETWORK), 2, 3))
        assertEquals(Pass.STOP, NovelAudioBookJobUploader.nextPass(listOf(Outcome.WAITING_NETWORK), 3, 3))
        assertEquals(Pass.STOP, NovelAudioBookJobUploader.nextPass(listOf(Outcome.STOPPED), 0, 3))
    }

    @Test fun resumeReturnsToUploadOnlyWhenUploadWasUnfinished() {
        assertEquals(NovelAudioBookJobStates.UPLOADING,
            NovelAudioBookJobs.resumedState(NovelAudioBookJobs.REASON_UPLOAD_UNFINISHED, false))
        assertEquals(NovelAudioBookJobStates.UPLOADING, NovelAudioBookJobs.resumedState("", true))
        assertEquals(NovelAudioBookJobStates.UPLOADED, NovelAudioBookJobs.resumedState("", false))
    }

    private fun createJob(chapterCount: Int, current: Int) = runBlocking {
        NovelAudioBookJobCreator(dao, store, { BookJobCreated(JOB, 50, "uploading") })
            .create(WORK, BOOK, "哑钟", SCOPE, chapterCount, current)
    }

    private fun uploader(
        load: suspend (Int) -> NovelAudioChapterSnapshot?,
        upload: suspend (String, String, BookJobChapterUpload) -> Boolean,
        canContinue: () -> Boolean = { true }
    ) = NovelAudioBookJobUploader(dao, load, upload, { "running" }, canContinue)

    private fun snapshot(index: Int, vararg paragraphs: String) = NovelAudioChapterSnapshotFactory.fromStrings(
        BOOK, index, "chapter://$index", paragraphs.toList(), workKey = WORK,
        rules = ReadAloudPreprocessRuleConfig().freeze())

    private companion object {
        const val WORK = "work:哑钟"
        const val BOOK = "book://dumb-bell"
        const val SCOPE = "scope"
        const val JOB = "job-0123456789abcdef01234567"
    }
}
