package io.legado.app.help.readaloud.novel

import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.NovelAudioDao
import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioDownloadTaskEntity
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioStates
import java.io.File
import java.security.MessageDigest
import java.util.Locale

class NovelAudioRepository(
    private val database: AppDatabase,
    private val dao: NovelAudioDao = database.novelAudioDao
) {

    /** Returned inside the enqueue transaction; a worker must never refresh this token. */
    data class Execution(
        val plan: NovelAudioChapterPlan,
        val executionAttempt: Long,
        val retention: String,
        val alreadyReady: Boolean = false
    ) {
        val taskId: String = NovelAudioIdentity.storageKey(plan.planId, "download")
    }

    fun updateTask(
        taskId: String,
        generation: Long,
        executionAttempt: Long,
        state: String,
        progress: Int,
        reason: String = ""
    ): Boolean {
        return dao.compareAndSetDownloadTask(
            taskId = taskId,
            expectedGeneration = generation,
            expectedExecutionAttempt = executionAttempt,
            state = state,
            progress = progress.coerceIn(0, 100),
            reason = reason
        ) > 0
    }

    fun updatePlan(
        planId: String,
        generation: Long,
        executionAttempt: Long,
        state: String,
        progress: Int,
        reason: String = ""
    ): Boolean {
        return dao.compareAndSetPlanState(
            planId = planId,
            expectedGeneration = generation,
            expectedExecutionAttempt = executionAttempt,
            state = state,
            progress = progress.coerceIn(0, 100),
            reason = reason
        ) > 0
    }

    /**
     * Invalidates a previously published READY result after the filesystem no
     * longer matches its Room metadata. Both tokens belong to the player's
     * original read, so a late error cannot demote a repaired attempt.
     */
    fun invalidateReadyState(
        planId: String,
        expectedGeneration: Long,
        segmentId: String? = null,
        expectedExecutionAttempt: Long = 0L
    ): Boolean {
        var changed = false
        database.runInTransaction {
            if (!segmentId.isNullOrBlank()) {
                changed = dao.invalidateReadyArtifact(
                    planId = planId,
                    segmentId = segmentId,
                    expectedGeneration = expectedGeneration,
                    expectedExecutionAttempt = expectedExecutionAttempt
                ) > 0
            }
            changed = dao.invalidateReadyPlan(
                planId = planId,
                expectedGeneration = expectedGeneration,
                expectedExecutionAttempt = expectedExecutionAttempt,
                reason = "ARTIFACT_INVALID"
            ) > 0 || changed
            // A completed task must be repairable together with its damaged plan.
            // Only READY tasks of this generation change; running work and retention stay intact.
            changed = dao.invalidateReadyDownloadTasks(
                planId = planId,
                expectedGeneration = expectedGeneration,
                expectedExecutionAttempt = expectedExecutionAttempt,
                state = NovelAudioStates.PARTIAL,
                reason = "ARTIFACT_INVALID"
            ) > 0 || changed
        }
        return changed
    }

    fun allArtifactsReady(planId: String, segmentIds: List<String>): Boolean {
        return segmentIds.isNotEmpty() &&
            dao.readySegmentIds(planId, segmentIds).size == segmentIds.size
    }

    fun missingOrUnreadySegmentIds(
        planId: String,
        expectedSegmentIds: List<String>
    ): List<String> {
        return dao.missingOrUnreadySegmentIds(planId, expectedSegmentIds)
    }

    fun saveNewPlan(
        plan: NovelAudioChapterPlan,
        retention: String = NovelAudioRetention.AUTO,
        taskJson: String = ""
    ): Boolean {
        return savePlanForExecution(plan, retention, taskJson) != null
    }

    fun savePlanForExecution(
        plan: NovelAudioChapterPlan,
        retention: String = NovelAudioRetention.AUTO,
        taskJson: String = ""
    ): Execution? {
        require(retention == NovelAudioRetention.AUTO || retention == NovelAudioRetention.PINNED)
        val entity = plan.toEntity(retention)
        val task = NovelAudioDownloadTaskEntity(
            taskId = NovelAudioIdentity.storageKey(plan.planId, "download"),
            planId = plan.planId,
            physicalBookUrl = plan.physicalBookUrl,
            chapterIndex = plan.chapterIndex,
            scope = plan.serverScope,
            generation = plan.generation,
            state = NovelAudioStates.QUEUED,
            retention = retention,
            taskJson = taskJson
        )
        var execution: Execution? = null
        database.runInTransaction {
            val inserted = dao.insertChapterPlan(entity) != -1L
            if (inserted) {
                check(dao.insertDownloadTask(task) != -1L) {
                    "novel audio new plan has a conflicting download task"
                }
                execution = Execution(plan, entity.executionAttempt, retention)
            } else {
                val existing = dao.chapterPlan(plan.planId)
                if (existing?.generation == plan.generation &&
                    existing.state == NovelAudioStates.READY
                ) {
                    if (retention == NovelAudioRetention.PINNED) {
                        dao.promotePlanRetention(plan.planId, plan.generation)
                        dao.promoteTaskRetention(plan.planId, plan.generation)
                    }
                    execution = Execution(
                        plan,
                        existing.executionAttempt,
                        if (retention == NovelAudioRetention.PINNED) retention else existing.retention,
                        alreadyReady = true
                    )
                    return@runInTransaction
                }
                if (existing?.generation == plan.generation &&
                    existing.state in setOf(
                        NovelAudioStates.FAILED,
                        NovelAudioStates.PARTIAL
                    ) &&
                    dao.requeueFailedPlan(
                        planId = plan.planId,
                        expectedGeneration = plan.generation,
                        expectedExecutionAttempt = existing.executionAttempt
                    ) > 0
                ) {
                    val taskRequeued = dao.requeueDownloadTask(
                        taskId = task.taskId,
                        expectedGeneration = plan.generation,
                        expectedExecutionAttempt = existing.executionAttempt
                    ) > 0
                    check(existing.executionAttempt < Long.MAX_VALUE) { "novel audio attempt exhausted" }
                    val nextAttempt = existing.executionAttempt + 1
                    val nextRetention = if (retention == NovelAudioRetention.PINNED) {
                        retention
                    } else {
                        existing.retention
                    }
                    val taskAvailable = taskRequeued ||
                        dao.insertDownloadTask(
                            task.copy(executionAttempt = nextAttempt, retention = nextRetention)
                        ) != -1L
                    check(taskAvailable) {
                        "novel audio failed plan has no requeueable download task"
                    }
                    if (retention == NovelAudioRetention.PINNED) {
                        dao.promotePlanRetention(
                            planId = plan.planId,
                            expectedGeneration = plan.generation
                        )
                        dao.promoteTaskRetention(
                            planId = plan.planId,
                            expectedGeneration = plan.generation
                        )
                    }
                    execution = Execution(plan, nextAttempt, nextRetention)
                }
            }
        }
        return execution
    }

    fun startExecution(execution: Execution): Boolean {
        var started = false
        database.runInTransaction {
            val plan = dao.chapterPlan(execution.plan.planId) ?: return@runInTransaction
            if (plan.generation != execution.plan.generation ||
                plan.executionAttempt != execution.executionAttempt ||
                plan.state !in setOf(
                    NovelAudioStates.PLANNED, NovelAudioStates.QUEUED,
                    NovelAudioStates.PARTIAL, NovelAudioStates.WAITING_NETWORK
                )
            ) return@runInTransaction
            if (dao.claimDownloadTask(
                    execution.taskId, plan.generation, execution.executionAttempt
                ) == 0
            ) return@runInTransaction
            check(updatePlan(
                plan.planId, plan.generation, execution.executionAttempt,
                NovelAudioStates.RUNNING, plan.progress
            ))
            started = true
        }
        return started
    }

    fun isExecutionCurrent(execution: Execution): Boolean {
        val plan = dao.chapterPlan(execution.plan.planId) ?: return false
        return plan.generation == execution.plan.generation &&
            plan.executionAttempt == execution.executionAttempt &&
            plan.state == NovelAudioStates.RUNNING &&
            dao.downloadTasksForPlan(plan.planId).any {
                it.taskId == execution.taskId && it.generation == plan.generation &&
                    it.executionAttempt == execution.executionAttempt &&
                    it.state == NovelAudioStates.RUNNING
            }
    }

    /**
     * Releases a cancelled execution as one fenced state transition.
     * Plan and task receive the same new attempt, so an old worker cannot
     * continue writing after the cancellation has been observed.
     */
    fun releaseExecution(execution: Execution, reason: String = "CANCELLED"): Boolean {
        var released = false
        database.runInTransaction {
            check(execution.executionAttempt < Long.MAX_VALUE) {
                "novel audio attempt exhausted"
            }
            val planUpdated = dao.releaseExecutionPlan(
                planId = execution.plan.planId,
                expectedGeneration = execution.plan.generation,
                expectedExecutionAttempt = execution.executionAttempt,
                reason = reason
            )
            if (planUpdated == 0) return@runInTransaction
            val taskUpdated = dao.releaseExecutionTask(
                taskId = execution.taskId,
                expectedGeneration = execution.plan.generation,
                expectedExecutionAttempt = execution.executionAttempt,
                reason = reason
            )
            check(taskUpdated > 0) {
                "novel audio execution release lost its download task"
            }
            released = true
        }
        return released
    }

    fun invalidExecutionArtifactIds(
        execution: Execution,
        filesRoot: File,
        decoder: (File) -> Boolean
    ): List<String> {
        val plan = dao.chapterPlan(execution.plan.planId) ?: return emptyList()
        if (plan.generation != execution.plan.generation ||
            plan.executionAttempt != execution.executionAttempt
        ) return emptyList()
        val artifacts = dao.segmentArtifacts(plan.planId).associateBy { it.segmentId }
        return execution.plan.playableSegments.mapNotNull { segment ->
            val artifact = artifacts[segment.segmentId]
            if (artifact == null ||
                artifact.state != NovelAudioStates.READY ||
                kotlin.runCatching {
                    validateArtifactFile(artifact, filesRoot, decoder)
                    true
                }.getOrDefault(false).not()
            ) {
                segment.segmentId
            } else {
                null
            }
        }
    }

    fun invalidateReadyExecution(
        execution: Execution,
        invalidSegmentIds: List<String>,
        reason: String = "ARTIFACT_INVALID"
    ): Boolean {
        if (invalidSegmentIds.isEmpty()) return false
        var invalidated = false
        database.runInTransaction {
            // Establish READY ownership before touching artifacts. A stale repair is a no-op.
            if (dao.invalidateReadyPlan(
                    planId = execution.plan.planId,
                    expectedGeneration = execution.plan.generation,
                    expectedExecutionAttempt = execution.executionAttempt,
                    state = NovelAudioStates.PARTIAL,
                    progress = 0,
                    reason = reason
                ) == 0
            ) return@runInTransaction
            invalidSegmentIds.forEach { segmentId ->
                dao.invalidateReadyArtifact(
                    planId = execution.plan.planId,
                    segmentId = segmentId,
                    expectedGeneration = execution.plan.generation,
                    expectedExecutionAttempt = execution.executionAttempt,
                    state = NovelAudioStates.FAILED,
                    updatedAt = System.currentTimeMillis()
                )
            }
            // The last artifact can make the plan READY before this worker's task finishes.
            // Repair only that owned task; any ownership mismatch rolls back all invalidation.
            check(
                dao.invalidateReadyExecutionTask(
                    taskId = execution.taskId,
                    planId = execution.plan.planId,
                    expectedGeneration = execution.plan.generation,
                    expectedExecutionAttempt = execution.executionAttempt,
                    state = NovelAudioStates.PARTIAL,
                    reason = reason
                ) > 0
            )
            invalidated = true
        }
        return invalidated
    }

    fun validateExecutionArtifacts(
        execution: Execution,
        filesRoot: File,
        decoder: (File) -> Boolean
    ): Boolean {
        val plan = dao.chapterPlan(execution.plan.planId) ?: return false
        if (plan.generation != execution.plan.generation ||
            plan.executionAttempt != execution.executionAttempt
        ) return false
        val ids = execution.plan.playableSegments.map { it.segmentId }
        if (ids.isEmpty()) return false
        val artifacts = dao.segmentArtifacts(plan.planId).associateBy { it.segmentId }
        return ids.all { id ->
            val artifact = artifacts[id] ?: return@all false
            artifact.state == NovelAudioStates.READY && kotlin.runCatching {
                validateArtifactFile(artifact, filesRoot, decoder)
                true
            }.getOrDefault(false)
        }
    }

    /** Completion is atomic with the attempt/state check, including reuse of READY audio. */
    fun finishExecution(execution: Execution): Boolean {
        var finished = false
        database.runInTransaction {
            val plan = dao.chapterPlan(execution.plan.planId) ?: return@runInTransaction
            val task = dao.downloadTasksForPlan(plan.planId)
                .firstOrNull { it.taskId == execution.taskId } ?: return@runInTransaction
            if (plan.generation != execution.plan.generation ||
                plan.executionAttempt != execution.executionAttempt ||
                task.generation != plan.generation ||
                task.executionAttempt != execution.executionAttempt ||
                plan.state !in setOf(NovelAudioStates.RUNNING, NovelAudioStates.READY) ||
                task.state !in setOf(NovelAudioStates.RUNNING, NovelAudioStates.READY) ||
                !allArtifactsReady(plan.planId, execution.plan.playableSegments.map { it.segmentId })
            ) return@runInTransaction
            if (task.state != NovelAudioStates.READY) {
                check(updateTask(task.taskId, task.generation, execution.executionAttempt,
                    NovelAudioStates.READY, 100))
            }
            if (plan.state != NovelAudioStates.READY) {
                check(updatePlan(plan.planId, plan.generation, execution.executionAttempt,
                    NovelAudioStates.READY, 100))
            }
            finished = true
        }
        return finished
    }

    fun currentPlan(physicalBookUrl: String, chapterIndex: Int): NovelAudioChapterPlan? {
        return dao.currentChapterPlan(physicalBookUrl, chapterIndex)
            ?.let { NovelAudioPlanJson.decode(it.planJson) }
    }

    fun recoverablePinnedTasks(): List<NovelAudioDownloadTaskEntity> {
        return dao.recoverablePinnedTasks()
    }

    fun tasksForBook(physicalBookUrl: String): List<NovelAudioDownloadTaskEntity> {
        return dao.tasksForBook(physicalBookUrl)
    }

    fun saveArtifact(
        artifact: NovelAudioSegmentArtifactEntity,
        expectedGeneration: Long,
        expectedExecutionAttempt: Long,
        expectedSegmentIds: List<String>,
        filesRoot: File,
        decoder: (File) -> Boolean
    ): Boolean {
        require(artifact.ttsProfile.isNotBlank())
        require(artifact.contentType.isNotBlank())
        require(artifact.sha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(artifact.size > 0)
        require(artifact.path.isNotBlank())
        require(artifact.state == NovelAudioStates.READY)
        require(artifact.segmentId in expectedSegmentIds)
        validateArtifactFile(artifact, filesRoot, decoder)
        return dao.saveArtifactAndUpdatePlan(
            artifact = artifact,
            expectedGeneration = expectedGeneration,
            expectedExecutionAttempt = expectedExecutionAttempt,
            expectedSegmentIds = expectedSegmentIds
        )
    }

    fun pin(planId: String) {
        database.runInTransaction {
            dao.pinChapterPlan(planId)
            dao.downloadTasksForPlan(planId).forEach { task ->
                dao.pinDownloadTask(task.taskId)
            }
        }
    }
}

private fun validateArtifactFile(
    artifact: NovelAudioSegmentArtifactEntity,
    filesRoot: File,
    decoder: (File) -> Boolean
) {
    require(artifact.size > 0 && artifact.path.isNotBlank())
    require(artifact.ttsProfile.isNotBlank() && artifact.contentType.isNotBlank())
    val root = filesRoot.canonicalFile
    val file = File(filesRoot, artifact.path).canonicalFile
    require(file.path.startsWith(root.path + File.separator))
    require(file.isFile && file.length() == artifact.size)
    require(sha256(file) == artifact.sha256.lowercase(Locale.ROOT))
    require(decoder(file))
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
}

fun NovelAudioChapterPlan.toEntity(retention: String): NovelAudioChapterPlanEntity {
    return NovelAudioChapterPlanEntity(
        planId = planId,
        workKey = workKey,
        physicalBookUrl = physicalBookUrl,
        chapterIndex = chapterIndex,
        chapterUrl = chapterUrl,
        scope = serverScope,
        generation = generation,
        state = NovelAudioStates.PLANNED,
        retention = retention,
        snapshotHash = snapshotHash,
        rulesVersion = rulesVersion,
        planJson = NovelAudioPlanJson.encode(this),
        progress = 0
    )
}
