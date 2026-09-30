package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.data.entities.NovelAudioAliasEntity
import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioDownloadTaskEntity
import io.legado.app.data.entities.NovelAudioMergeRecordEntity
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioVoiceBindingEntity
import io.legado.app.data.entities.NovelAudioStates

@Dao
interface NovelAudioDao {

    @Query(
        """
        SELECT * FROM novel_audio_aliases
        WHERE workKey = :workKey
        ORDER BY normalizedAlias ASC
        """
    )
    fun aliases(workKey: String): List<NovelAudioAliasEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAlias(alias: NovelAudioAliasEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAlias(alias: NovelAudioAliasEntity)

    @Query(
        """
        DELETE FROM novel_audio_aliases
        WHERE workKey = :workKey AND normalizedAlias = :normalizedAlias
        """
    )
    fun deleteAlias(workKey: String, normalizedAlias: String)

    @Query(
        """
        SELECT * FROM novel_audio_voice_bindings
        WHERE scope = :scope
        ORDER BY characterId ASC
        """
    )
    fun voiceBindings(scope: String): List<NovelAudioVoiceBindingEntity>

    @Query(
        """
        SELECT * FROM novel_audio_voice_bindings
        WHERE scope = :scope AND characterId = :characterId
        LIMIT 1
        """
    )
    fun voiceBinding(scope: String, characterId: Long): NovelAudioVoiceBindingEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertVoiceBinding(binding: NovelAudioVoiceBindingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertVoiceBinding(binding: NovelAudioVoiceBindingEntity)

    @Update
    fun updateVoiceBinding(binding: NovelAudioVoiceBindingEntity)

    @Query(
        """
        SELECT * FROM novel_audio_voice_bindings
        WHERE characterId IN (:characterIds)
        ORDER BY scope ASC, characterId ASC
        """
    )
    fun voiceBindingsForCharacters(characterIds: List<Long>): List<NovelAudioVoiceBindingEntity>

    @Query(
        """
        DELETE FROM novel_audio_voice_bindings
        WHERE scope = :scope AND characterId = :characterId
        """
    )
    fun deleteVoiceBinding(scope: String, characterId: Long)

    @Query(
        """
        SELECT * FROM novel_audio_chapter_plans
        WHERE physicalBookUrl = :physicalBookUrl
          AND chapterIndex = :chapterIndex
        ORDER BY retention = 'PINNED' DESC, generation DESC, updatedAt DESC
        LIMIT 1
        """
    )
    fun currentChapterPlan(physicalBookUrl: String, chapterIndex: Int): NovelAudioChapterPlanEntity?

    @Query(
        """
        SELECT * FROM novel_audio_chapter_plans
        WHERE physicalBookUrl = :physicalBookUrl
          AND chapterIndex = :chapterIndex
        ORDER BY retention = 'PINNED' DESC, generation DESC, updatedAt DESC
        """
    )
    fun chapterPlans(physicalBookUrl: String, chapterIndex: Int): List<NovelAudioChapterPlanEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertChapterPlan(plan: NovelAudioChapterPlanEntity): Long

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET retention = 'PINNED', updatedAt = :updatedAt
        WHERE planId = :planId AND retention != 'PINNED'
        """
    )
    fun pinChapterPlan(planId: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET state = :state, stateReason = :reason, progress = :progress, updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('PLANNED', 'QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK')
        """
    )
    fun compareAndSetPlanState(
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long = 0L,
        state: String,
        progress: Int,
        reason: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET state = :state, stateReason = :reason, progress = :progress, updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state = 'READY'
        """
    )
    fun invalidateReadyPlan(
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long = 0L,
        state: String = NovelAudioStates.PARTIAL,
        progress: Int = 0,
        reason: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET state = :state, stateReason = :reason, progress = :progress,
            executionAttempt = executionAttempt + 1, updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('FAILED', 'PARTIAL')
        """
    )
    fun requeueFailedPlan(
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        state: String = NovelAudioStates.PLANNED,
        progress: Int = 0,
        reason: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET state = :state, stateReason = :reason, progress = :progress,
            executionAttempt = executionAttempt + 1, updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('PLANNED', 'QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK')
        """
    )
    fun releaseExecutionPlan(
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        state: String = NovelAudioStates.PARTIAL,
        progress: Int = 0,
        reason: String = "CANCELLED",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_chapter_plans
        SET retention = 'PINNED', updatedAt = :updatedAt
        WHERE planId = :planId AND generation = :expectedGeneration
        """
    )
    fun promotePlanRetention(
        planId: String,
        expectedGeneration: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM novel_audio_chapter_plans WHERE planId = :planId LIMIT 1")
    fun chapterPlan(planId: String): NovelAudioChapterPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSegmentArtifact(artifact: NovelAudioSegmentArtifactEntity)

    @Query(
        """
        UPDATE novel_audio_segment_artifacts
        SET state = :state, updatedAt = :updatedAt
        WHERE planId = :planId
          AND segmentId = :segmentId
          AND state = 'READY'
          AND EXISTS (
            SELECT 1 FROM novel_audio_chapter_plans
              WHERE planId = :planId
                AND generation = :expectedGeneration
                AND executionAttempt = :expectedExecutionAttempt
          )
        """
    )
    fun invalidateReadyArtifact(
        planId: String,
        segmentId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long = 0L,
        state: String = NovelAudioStates.FAILED,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /**
     * Saves one artifact under the fenced plan attempt.
     *
     * The plan becomes READY only in NovelAudioRepository.finishExecution(),
     * after the download task and every artifact have passed final validation.
     * Keeping that transition out of this method prevents a READY/RUNNING
     * intermediate state when the task update or final validation fails.
     */
    @Transaction
    fun saveArtifactAndUpdatePlan(
        artifact: NovelAudioSegmentArtifactEntity,
        expectedGeneration: Long,
        expectedSegmentIds: List<String>,
        expectedExecutionAttempt: Long = 0L,
        updatedAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (expectedSegmentIds.isEmpty()) return false
        val plan = chapterPlan(artifact.planId) ?: return false
        if (plan.generation != expectedGeneration ||
            plan.executionAttempt != expectedExecutionAttempt ||
            artifact.segmentId !in expectedSegmentIds
        ) return false
        if (plan.state !in setOf(
                NovelAudioStates.PLANNED,
                NovelAudioStates.QUEUED,
                NovelAudioStates.RUNNING,
                NovelAudioStates.PARTIAL
            )
        ) {
            return false
        }
        upsertSegmentArtifact(artifact)
        return true
    }

    @Query(
        """
        SELECT * FROM novel_audio_segment_artifacts
        WHERE planId = :planId
        ORDER BY rowid ASC
        """
    )
    fun segmentArtifacts(planId: String): List<NovelAudioSegmentArtifactEntity>

    @Query(
        """
        SELECT segmentId FROM novel_audio_segment_artifacts
        WHERE planId = :planId AND state != 'READY'
        """
    )
    fun missingOrUnreadySegmentIds(planId: String): List<String>

    @Query(
        """
        SELECT segmentId FROM novel_audio_segment_artifacts
        WHERE planId = :planId
          AND segmentId IN (:segmentIds)
          AND state = 'READY'
        """
    )
    fun readySegmentIdsIn(planId: String, segmentIds: List<String>): List<String>

    @Query(
        """
        SELECT COUNT(*) FROM novel_audio_segment_artifacts
        WHERE planId = :planId AND segmentId IN (:segmentIds) AND state = 'READY'
        """
    )
    fun countReadySegments(planId: String, segmentIds: List<String>): Int

    @Query(
        """
        SELECT * FROM novel_audio_download_tasks
        WHERE physicalBookUrl = :physicalBookUrl
        ORDER BY retention = 'PINNED' DESC, updatedAt DESC
        """
    )
    fun downloadTasks(physicalBookUrl: String): List<NovelAudioDownloadTaskEntity>

    @Query(
        """
        SELECT * FROM novel_audio_download_tasks
        WHERE retention = 'PINNED'
          AND state IN ('QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK', 'PAUSED')
        ORDER BY createdAt ASC, taskId ASC
        """
    )
    fun recoverablePinnedTasks(): List<NovelAudioDownloadTaskEntity>

    @Query(
        """
        SELECT * FROM novel_audio_download_tasks
        WHERE physicalBookUrl = :physicalBookUrl
        ORDER BY chapterIndex ASC, retention = 'PINNED' DESC,
            generation DESC, updatedAt DESC, taskId ASC
        """
    )
    fun tasksForBook(physicalBookUrl: String): List<NovelAudioDownloadTaskEntity>

    @Query(
        """
        SELECT * FROM novel_audio_download_tasks
        WHERE planId = :planId
        ORDER BY retention = 'PINNED' DESC, generation DESC, updatedAt DESC
        """
    )
    fun downloadTasksForPlan(planId: String): List<NovelAudioDownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDownloadTask(task: NovelAudioDownloadTaskEntity): Long

    @Update
    fun updateDownloadTask(task: NovelAudioDownloadTaskEntity)

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = :state, stateReason = :reason, progress = :progress, updatedAt = :updatedAt
        WHERE taskId = :taskId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK')
        """
    )
    fun compareAndSetDownloadTask(
        taskId: String,
        expectedGeneration: Long,
        state: String,
        progress: Int,
        reason: String = "",
        expectedExecutionAttempt: Long = 0L,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = 'RUNNING', stateReason = '', updatedAt = :updatedAt
        WHERE taskId = :taskId AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('QUEUED', 'PARTIAL', 'WAITING_NETWORK')
        """
    )
    fun claimDownloadTask(
        taskId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = :state, stateReason = :reason, progress = 0, updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state = 'READY'
          AND EXISTS (
              SELECT 1 FROM novel_audio_chapter_plans
              WHERE planId = :planId AND generation = :expectedGeneration
                AND executionAttempt = :expectedExecutionAttempt
          )
        """
    )
    fun invalidateReadyDownloadTasks(
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long = 0L,
        state: String = NovelAudioStates.PARTIAL,
        reason: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    /** A worker may repair its unfinished task, but must not affect another task for the plan. */
    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = :state, stateReason = :reason, progress = 0, updatedAt = :updatedAt
        WHERE taskId = :taskId AND planId = :planId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('READY', 'RUNNING')
          AND EXISTS (
              SELECT 1 FROM novel_audio_chapter_plans
              WHERE planId = :planId AND generation = :expectedGeneration
                AND executionAttempt = :expectedExecutionAttempt
          )
        """
    )
    fun invalidateReadyExecutionTask(
        taskId: String,
        planId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        state: String,
        reason: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = :state, stateReason = :reason, progress = :progress,
            executionAttempt = executionAttempt + 1, updatedAt = :updatedAt
        WHERE taskId = :taskId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('FAILED', 'PARTIAL', 'QUEUED', 'RUNNING', 'WAITING_NETWORK', 'PAUSED')
        """
    )
    fun requeueDownloadTask(
        taskId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        state: String = NovelAudioStates.QUEUED,
        progress: Int = 0,
        reason: String = "",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET state = :state, stateReason = :reason, progress = :progress,
            executionAttempt = executionAttempt + 1, updatedAt = :updatedAt
        WHERE taskId = :taskId
          AND generation = :expectedGeneration
          AND executionAttempt = :expectedExecutionAttempt
          AND state IN ('QUEUED', 'RUNNING', 'PARTIAL', 'WAITING_NETWORK', 'PAUSED')
        """
    )
    fun releaseExecutionTask(
        taskId: String,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        state: String = NovelAudioStates.PARTIAL,
        progress: Int = 0,
        reason: String = "CANCELLED",
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET retention = 'PINNED', updatedAt = :updatedAt
        WHERE planId = :planId
          AND generation = :expectedGeneration
        """
    )
    fun promoteTaskRetention(
        planId: String,
        expectedGeneration: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        """
        UPDATE novel_audio_download_tasks
        SET retention = 'PINNED', updatedAt = :updatedAt
        WHERE taskId = :taskId AND retention != 'PINNED'
        """
    )
    fun pinDownloadTask(taskId: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Insert
    fun insertMergeRecord(record: NovelAudioMergeRecordEntity): Long

    @Query(
        """
        SELECT * FROM novel_audio_merge_records
        WHERE workKey = :workKey
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun mergeRecords(workKey: String): List<NovelAudioMergeRecordEntity>

    @Query(
        """
        SELECT * FROM novel_audio_merge_records
        WHERE workKey = :workKey AND state = 'ACTIVE'
        ORDER BY createdAt DESC, id DESC
        """
    )
    fun activeMergeRecords(workKey: String): List<NovelAudioMergeRecordEntity>

    @Query(
        """
        UPDATE novel_audio_merge_records
        SET state = 'REVOKED', revokedAt = :revokedAt
        WHERE id = :id AND state = 'ACTIVE'
        """
    )
    fun revokeMergeRecord(id: Long, revokedAt: Long = System.currentTimeMillis()): Int

    @Transaction
    fun savePlanAndTask(
        plan: NovelAudioChapterPlanEntity,
        task: NovelAudioDownloadTaskEntity?
    ) {
        insertChapterPlan(plan)
        if (task != null) insertDownloadTask(task)
    }

    @Transaction
    fun readySegmentIds(planId: String, segmentIds: List<String>): List<String> {
        if (segmentIds.isEmpty()) return emptyList()
        val readyIds = readySegmentIdsIn(planId, segmentIds).toSet()
        return segmentIds.distinct().filter { it in readyIds }
    }

    @Transaction
    fun missingOrUnreadySegmentIds(
        planId: String,
        expectedSegmentIds: List<String>
    ): List<String> {
        if (expectedSegmentIds.isEmpty()) return emptyList()
        val readyIds = readySegmentIdsIn(planId, expectedSegmentIds).toSet()
        return expectedSegmentIds.distinct().filterNot { it in readyIds }
    }
}
