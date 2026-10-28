package io.legado.app.help.readaloud.novel

import io.legado.app.data.dao.NovelAudioBookJobDao
import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** 整本任务表的内存实现，语义对齐 NovelAudioBookJobDao 的 SQL。 */
class MemoryBookJobDao : NovelAudioBookJobDao {
    val jobs = linkedMapOf<String, NovelAudioBookJobEntity>()
    val rows = linkedMapOf<Pair<String, Int>, NovelAudioBookJobChapterEntity>()
    private val version = MutableStateFlow(0)

    private fun changed() { version.value += 1 }

    override fun insertJob(job: NovelAudioBookJobEntity) {
        check(job.jobId !in jobs)
        jobs[job.jobId] = job
        changed()
    }

    override fun job(jobId: String) = jobs[jobId]

    override fun activeJobForBook(physicalBookUrl: String, states: Collection<String>) =
        jobs.values.filter { it.physicalBookUrl == physicalBookUrl && it.state in states }
            .maxByOrNull { it.createdAt }

    override fun observeActiveJobForBook(physicalBookUrl: String, states: Collection<String>): Flow<NovelAudioBookJobEntity?> =
        version.map { activeJobForBook(physicalBookUrl, states) }

    override fun jobsInStates(states: Collection<String>) =
        jobs.values.filter { it.state in states }.sortedBy { it.createdAt }

    override fun setJobState(jobId: String, state: String, reason: String, now: Long): Int {
        val job = jobs[jobId] ?: return 0
        jobs[jobId] = job.copy(state = state, stateReason = reason, updatedAt = now)
        changed()
        return 1
    }

    override fun markSynced(jobId: String, now: Long) {
        jobs[jobId]?.let { jobs[jobId] = it.copy(lastSyncAt = now, updatedAt = now) }
    }

    override fun deleteJob(jobId: String) {
        jobs.remove(jobId)
        changed()
    }

    override fun insertChapters(chapters: List<NovelAudioBookJobChapterEntity>) {
        chapters.forEach { rows.putIfAbsent(it.jobId to it.chapterIndex, it) }
        changed()
    }

    override fun chapters(jobId: String) = rows.values.filter { it.jobId == jobId }.sortedBy { it.position }

    override fun observeChapters(jobId: String): Flow<List<NovelAudioBookJobChapterEntity>> =
        version.map { chapters(jobId) }

    override fun chapter(jobId: String, chapterIndex: Int) = rows[jobId to chapterIndex]

    override fun nextChapterInUploadState(jobId: String, uploadState: String) =
        chapters(jobId).firstOrNull { it.uploadState == uploadState }

    override fun setUploadState(jobId: String, chapterIndex: Int, uploadState: String, snapshotHash: String, now: Long) {
        update(jobId, chapterIndex) { it.copy(uploadState = uploadState, snapshotHash = snapshotHash, updatedAt = now) }
    }

    override fun setImportState(
        jobId: String, chapterIndex: Int, importState: String, planId: String, failure: String, now: Long
    ) {
        update(jobId, chapterIndex) {
            it.copy(importState = importState, planId = planId, failure = failure, updatedAt = now)
        }
    }

    override fun requeueMissing(jobId: String, pending: String, missing: String, now: Long) {
        chapters(jobId).filter { it.uploadState == missing }.forEach {
            update(jobId, it.chapterIndex) { row -> row.copy(uploadState = pending, updatedAt = now) }
        }
    }

    override fun deleteChapters(jobId: String) {
        rows.keys.filter { it.first == jobId }.forEach(rows::remove)
        changed()
    }

    private fun update(jobId: String, chapterIndex: Int, change: (NovelAudioBookJobChapterEntity) -> NovelAudioBookJobChapterEntity) {
        rows[jobId to chapterIndex]?.let { rows[jobId to chapterIndex] = change(it) }
        changed()
    }
}
