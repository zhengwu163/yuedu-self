package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioBookJobImportStates
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import io.legado.app.help.readaloud.server.BookJobBinding
import io.legado.app.help.readaloud.server.BookJobChapterResult
import io.legado.app.help.readaloud.server.BookJobChapterState
import io.legado.app.help.readaloud.server.BookJobCreated
import io.legado.app.help.readaloud.server.BookJobDetail
import io.legado.app.help.readaloud.server.BookJobPiece
import io.legado.app.help.readaloud.server.BookJobResultCharacter
import io.legado.app.help.readaloud.server.BookJobResultUnit
import io.legado.app.help.readaloud.server.SynthesizedAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

class NovelAudioBookJobImporterTest {
    private val dao = MemoryBookJobDao()
    private val store = MemoryCharacterStore()
    private val registry = CharacterRegistry(store)
    private var texts = mapOf(0 to listOf("单牧说：", "“钟又不响了。”", "曾晚推门进来。", "“我知道。”"))
    private var hostState = "done"
    private val downloaded = mutableListOf<String>()
    private val fetched = mutableListOf<String>()
    private val savedPlans = mutableListOf<NovelAudioChapterPlan>()

    @Before fun setup(): Unit = runBlocking {
        // 手机上另有一个电脑不知道的人物，占用了电脑会分配给「曾晚」的 ID 2。
        store.seed(WORK, listOf(BookCharacter(id = 1, bookUrl = WORK, name = "单牧", gender = "male"),
            BookCharacter(id = 2, bookUrl = WORK, name = "老钟", gender = "male")))
        NovelAudioBookJobCreator(dao, store, { BookJobCreated(JOB, 50, "uploading") })
            .create(WORK, BOOK, "哑钟", SCOPE, chapterCount = 1, currentIndex = 0)
        dao.setUploadState(JOB, 0, NovelAudioBookJobUploadStates.UPLOADED, snapshot(0).snapshotHash, 1L)
        dao.setJobState(JOB, NovelAudioBookJobStates.UPLOADED, "", 1L)
        Unit
    }

    @Test fun importsChapterWithPhoneIdsAndFinishesJob() = runBlocking {
        val summary = importer().sync(JOB)
        assertEquals(1, summary.imported)
        val plan = savedPlans.single()
        val speakers = plan.playableSegments.map { it.speakerId }
        val zengWan = store.characters(WORK).single { it.name == "曾晚" }.id
        assertTrue(zengWan != 2L)
        assertEquals(listOf(0L, 1L, 0L, zengWan), speakers)
        assertEquals(listOf("N1", "M1", "N1", "F1"), plan.playableSegments.map { it.voiceAssetId })
        assertEquals(plan.playableSegments.size, fetched.size)
        assertEquals(listOf("c0"), downloaded)
        assertEquals(NovelAudioBookJobImportStates.IMPORTED, dao.chapter(JOB, 0)?.importState)
        assertEquals(plan.planId, dao.chapter(JOB, 0)?.planId)
        assertEquals(NovelAudioBookJobStates.DONE, dao.job(JOB)?.state)
        assertEquals("F1", store.voiceBindings(SCOPE).single { it.characterId == zengWan }.voiceAssetId)
    }

    @Test fun changedTextIsMarkedStaleAndNotDownloaded() = runBlocking {
        val uploaded = snapshot(0)
        texts = mapOf(0 to listOf("单牧说：", "“钟响了。”"))
        val summary = importer(resultSnapshot = uploaded).sync(JOB)
        assertEquals(1, summary.stale)
        assertEquals(NovelAudioBookJobImportStates.STALE, dao.chapter(JOB, 0)?.importState)
        assertEquals(emptyList<String>(), fetched)
    }

    @Test fun unfinishedHostChapterWaits() = runBlocking {
        hostState = "analyzed"
        val summary = importer().sync(JOB)
        assertEquals(1, summary.waiting)
        assertEquals(NovelAudioBookJobImportStates.NONE, dao.chapter(JOB, 0)?.importState)
        assertEquals(NovelAudioBookJobStates.UPLOADED, dao.job(JOB)?.state)
    }

    @Test fun pieceTextMismatchRejectsTheChapter() = runBlocking {
        val summary = importer(corruptPiece = true).sync(JOB)
        assertEquals(1, summary.failed)
        val row = dao.chapter(JOB, 0)
        assertEquals(NovelAudioBookJobImportStates.FAILED to "PIECE_MISMATCH", row?.importState to row?.failure)
        assertEquals(emptyList<NovelAudioChapterPlan>(), savedPlans)
    }

    @Test fun audioHashMismatchFailsTheDownload() = runBlocking {
        val summary = importer(badAudio = true).sync(JOB)
        assertEquals(1, summary.failed)
        assertEquals("DOWNLOAD_FAILED", dao.chapter(JOB, 0)?.failure)
        assertEquals(emptyList<String>(), downloaded)
    }

    @Test fun deadlineStopsBeforeTheNextChapterAndKeepsTheJobOpen() = runBlocking {
        val summary = importer().sync(JOB, canContinue = { false })
        assertEquals(0, summary.imported)
        assertEquals(NovelAudioBookJobImportStates.NONE, dao.chapter(JOB, 0)?.importState)
        assertEquals(NovelAudioBookJobStates.UPLOADED, dao.job(JOB)?.state)
    }

    @Test fun playbackImportsOnlyUploadedChaptersNotYetImported() {
        val row = checkNotNull(dao.chapter(JOB, 0))
        assertTrue(NovelAudioBookJobImporter.shouldImportForPlayback(row))
        assertEquals(false, NovelAudioBookJobImporter.shouldImportForPlayback(null))
        assertEquals(false, NovelAudioBookJobImporter.shouldImportForPlayback(
            row.copy(uploadState = NovelAudioBookJobUploadStates.PENDING)))
        assertEquals(false, NovelAudioBookJobImporter.shouldImportForPlayback(
            row.copy(importState = NovelAudioBookJobImportStates.STALE)))
        assertEquals(false, NovelAudioBookJobImporter.shouldImportForPlayback(
            row.copy(importState = NovelAudioBookJobImportStates.IMPORTED)))
    }

    private fun importer(
        resultSnapshot: NovelAudioChapterSnapshot? = null,
        corruptPiece: Boolean = false,
        badAudio: Boolean = false
    ) = NovelAudioBookJobImporter(
        dao = dao,
        registry = registry,
        loadSnapshot = { snapshot(it) },
        fetchDetail = { BookJobDetail(JOB, "done", true, 1, 3.0,
            listOf(BookJobChapterState("c0", 0, dao.chapter(JOB, 0)!!.snapshotHash, hostState, null, false))) },
        fetchResult = { _, _ -> result(resultSnapshot ?: snapshot(0), corruptPiece) },
        fetchAudio = { _, audioId ->
            fetched += audioId
            SynthesizedAudio(if (badAudio) byteArrayOf(9) else audioId.toByteArray(), "audio/ogg", "local-qwen")
        },
        markDownloaded = { _, chapterId -> downloaded += chapterId },
        savePlan = { plan, fetch ->
            savedPlans += plan
            plan.playableSegments.all { segment ->
                runCatching {
                    fetch(io.legado.app.help.readaloud.server.SynthesisRequest(
                        segment.text, segment.voiceAssetId, segment.language, segment.speed))
                }.isSuccess
            }
        }
    )

    /** 模拟电脑：把「曾晚」新建为电脑 ID 2（与手机上的「老钟」撞号）。 */
    private fun result(snapshot: NovelAudioChapterSnapshot, corruptPiece: Boolean): BookJobChapterResult {
        val body = checkNotNull(NovelAudioBookJobUploader.chapterBody(snapshot, 0, 50))
        var audio = 0
        val units = body.units.map { unit ->
            val speaker = when {
                unit.roleType == "narrator" -> 0L
                unit.text.contains("钟又") || unit.text.contains("钟响") -> 1L
                else -> 2L
            }
            BookJobResultUnit(unit.unitId, speaker, unit.pieces.mapIndexed { index, text ->
                val id = "%032x".format(++audio)
                val hashed = if (corruptPiece) "$text!" else text
                BookJobPiece(index, sha256(hashed.toByteArray()), id, sha256(id.toByteArray()), 3, "audio/ogg", "local-qwen")
            })
        }
        return BookJobChapterResult("c0", 0, snapshot.snapshotHash, units,
            listOf(BookJobResultCharacter(1, "单牧", "male", true, listOf("单牧")),
                BookJobResultCharacter(2, "曾晚", "female", false, listOf("曾晚"))),
            listOf(BookJobBinding("0", "N1", 1), BookJobBinding("1", "M1", 1), BookJobBinding("2", "F1", 1)))
    }

    private fun snapshot(index: Int) = NovelAudioChapterSnapshotFactory.fromStrings(
        BOOK, index, "chapter://$index", texts.getValue(index), workKey = WORK,
        rules = ReadAloudPreprocessRuleConfig().freeze())

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val WORK = "work:哑钟"
        const val BOOK = "book://dumb-bell"
        const val SCOPE = "scope"
        const val JOB = "job-0123456789abcdef01234567"
    }
}
