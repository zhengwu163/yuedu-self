package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelAudioBookJobDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertJob(job: NovelAudioBookJobEntity)

    @Query("SELECT * FROM novel_audio_book_jobs WHERE jobId = :jobId")
    fun job(jobId: String): NovelAudioBookJobEntity?

    /** 一本书同时只有一个未结束的整本任务。 */
    @Query(
        """
        SELECT * FROM novel_audio_book_jobs
        WHERE physicalBookUrl = :physicalBookUrl AND state IN (:states)
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    fun activeJobForBook(physicalBookUrl: String, states: Collection<String>): NovelAudioBookJobEntity?

    @Query(
        """
        SELECT * FROM novel_audio_book_jobs
        WHERE physicalBookUrl = :physicalBookUrl AND state IN (:states)
        ORDER BY createdAt DESC LIMIT 1
        """
    )
    fun observeActiveJobForBook(
        physicalBookUrl: String,
        states: Collection<String>
    ): Flow<NovelAudioBookJobEntity?>

    @Query("SELECT * FROM novel_audio_book_jobs WHERE state IN (:states) ORDER BY createdAt")
    fun jobsInStates(states: Collection<String>): List<NovelAudioBookJobEntity>

    @Query(
        """
        UPDATE novel_audio_book_jobs SET state = :state, stateReason = :reason, updatedAt = :now
        WHERE jobId = :jobId
        """
    )
    fun setJobState(jobId: String, state: String, reason: String, now: Long): Int

    @Query("UPDATE novel_audio_book_jobs SET lastSyncAt = :now, updatedAt = :now WHERE jobId = :jobId")
    fun markSynced(jobId: String, now: Long)

    @Query("DELETE FROM novel_audio_book_jobs WHERE jobId = :jobId")
    fun deleteJob(jobId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChapters(chapters: List<NovelAudioBookJobChapterEntity>)

    @Query("SELECT * FROM novel_audio_book_job_chapters WHERE jobId = :jobId ORDER BY position")
    fun chapters(jobId: String): List<NovelAudioBookJobChapterEntity>

    @Query("SELECT * FROM novel_audio_book_job_chapters WHERE jobId = :jobId ORDER BY position")
    fun observeChapters(jobId: String): Flow<List<NovelAudioBookJobChapterEntity>>

    @Query(
        """
        SELECT * FROM novel_audio_book_job_chapters
        WHERE jobId = :jobId AND chapterIndex = :chapterIndex
        """
    )
    fun chapter(jobId: String, chapterIndex: Int): NovelAudioBookJobChapterEntity?

    @Query(
        """
        SELECT * FROM novel_audio_book_job_chapters
        WHERE jobId = :jobId AND uploadState = :uploadState
        ORDER BY position LIMIT 1
        """
    )
    fun nextChapterInUploadState(jobId: String, uploadState: String): NovelAudioBookJobChapterEntity?

    @Query(
        """
        UPDATE novel_audio_book_job_chapters
        SET uploadState = :uploadState, snapshotHash = :snapshotHash, updatedAt = :now
        WHERE jobId = :jobId AND chapterIndex = :chapterIndex
        """
    )
    fun setUploadState(jobId: String, chapterIndex: Int, uploadState: String, snapshotHash: String, now: Long)

    @Query(
        """
        UPDATE novel_audio_book_job_chapters
        SET importState = :importState, planId = :planId, failure = :failure, updatedAt = :now
        WHERE jobId = :jobId AND chapterIndex = :chapterIndex
        """
    )
    fun setImportState(
        jobId: String,
        chapterIndex: Int,
        importState: String,
        planId: String,
        failure: String,
        now: Long
    )

    /** 重新上传前把缺正文的章节放回待上传队列。 */
    @Query(
        """
        UPDATE novel_audio_book_job_chapters SET uploadState = :pending, updatedAt = :now
        WHERE jobId = :jobId AND uploadState = :missing
        """
    )
    fun requeueMissing(jobId: String, pending: String, missing: String, now: Long)

    @Transaction
    fun createJob(job: NovelAudioBookJobEntity, chapters: List<NovelAudioBookJobChapterEntity>) {
        insertJob(job)
        insertChapters(chapters)
    }

    @Transaction
    fun deleteJobWithChapters(jobId: String) {
        deleteChapters(jobId)
        deleteJob(jobId)
    }

    @Query("DELETE FROM novel_audio_book_job_chapters WHERE jobId = :jobId")
    fun deleteChapters(jobId: String)
}
