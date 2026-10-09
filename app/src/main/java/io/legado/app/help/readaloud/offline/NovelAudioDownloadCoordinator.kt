package io.legado.app.help.readaloud.offline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.help.readaloud.server.SynthesisRequest
import io.legado.app.help.readaloud.server.SynthesizedAudio
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Downloads the real audio for one frozen plan.
 *
 * AUTO work must provide a current-window check. PINNED work is independent
 * from the automatic playback lease and is therefore never rejected by it.
 */
class NovelAudioDownloadCoordinator(
    private val repository: NovelAudioRepository,
    private val artifactStore: NovelAudioArtifactStore,
    private val filesRoot: File,
    private val synthesize: suspend (SynthesisRequest) -> SynthesizedAudio,
    private val retryPolicy: NovelAudioRetryPolicy = NovelAudioRetryPolicy(),
    private val decoder: (File) -> Boolean = ::canDecodeNovelAudio
) {

    suspend fun downloadPlan(
        execution: NovelAudioRepository.Execution,
        isAutoAllowed: () -> Boolean
    ): Result {
        val plan = execution.plan
        val retention = execution.retention
        require(retention == NovelAudioRetention.AUTO || retention == NovelAudioRetention.PINNED)
        val segments = plan.playableSegments
        val expectedSegmentIds = segments.map { it.segmentId }
        val effectiveRetention = execution.retention
        val taskId = execution.taskId
        if (execution.alreadyReady) {
            val invalidIds = repository.invalidExecutionArtifactIds(execution, filesRoot, decoder)
            if (invalidIds.isNotEmpty()) {
                repository.invalidateReadyExecution(execution, invalidIds)
                return Result.Failed("PLAN_NOT_READY")
            }
            return if (repository.validateExecutionArtifacts(execution, filesRoot, decoder) &&
                repository.finishExecution(execution)
            ) Result.Ready else Result.Failed("PLAN_NOT_READY")
        }
        if (segments.isEmpty()) {
            repository.updatePlan(
                planId = plan.planId,
                generation = plan.generation,
                executionAttempt = execution.executionAttempt,
                state = NovelAudioStates.FAILED,
                progress = 0
            )
            repository.updateTask(
                taskId = taskId,
                generation = plan.generation,
                executionAttempt = execution.executionAttempt,
                state = NovelAudioStates.FAILED,
                progress = 0
            )
            return Result.Failed("NO_PLAYABLE_SEGMENTS")
        }
        if (effectiveRetention == NovelAudioRetention.AUTO && !isAutoAllowed()) {
            repository.releaseExecution(execution, reason = "AUTO_LEASE_REVOKED")
            return Result.Cancelled
        }
        val missingSegmentIds = repository
            .missingOrUnreadySegmentIds(plan.planId, expectedSegmentIds)
            .toSet()
        val segmentsToDownload = segments.filter { it.segmentId in missingSegmentIds }
        if (segmentsToDownload.isEmpty()) {
            if (!repository.startExecution(execution)) return Result.Failed("STALE_PLAN")
            if (!repository.validateExecutionArtifacts(execution, filesRoot, decoder) ||
                !repository.finishExecution(execution)
            ) {
                return Result.Failed("STALE_PLAN")
            }
            return Result.Ready
        }
        if (!repository.startExecution(execution)) return Result.Failed("STALE_PLAN")
        return try {
            segmentsToDownload.forEachIndexed { index, segment ->
                if (effectiveRetention == NovelAudioRetention.AUTO && !isAutoAllowed()) {
                    repository.releaseExecution(execution, reason = "AUTO_LEASE_REVOKED")
                    return Result.Cancelled
                }
                val audio = retryPolicy.run {
                    if (!repository.isExecutionCurrent(execution) ||
                        (effectiveRetention == NovelAudioRetention.AUTO && !isAutoAllowed())
                    ) throw CancellationException("novel audio execution expired")
                    synthesize(
                        SynthesisRequest(
                            text = segment.text,
                            voiceAssetId = segment.voiceAssetId,
                            language = segment.language,
                            speed = segment.speed
                        )
                    )
                }
                val stored = artifactStore.commit(
                    serverScope = plan.serverScope,
                    workKey = plan.workKey,
                    physicalBookUrl = plan.physicalBookUrl,
                    chapterIndex = plan.chapterIndex,
                    chapterUrl = plan.chapterUrl,
                    segmentId = segment.segmentId,
                    voiceAssetId = segment.voiceAssetId,
                    bindingRevision = segment.bindingRevision,
                    language = segment.language,
                    speed = segment.speed,
                    audio = audio,
                    isCurrent = {
                        repository.isExecutionCurrent(execution) &&
                            (effectiveRetention == NovelAudioRetention.PINNED || isAutoAllowed())
                    }
                )
                val saved = repository.saveArtifact(
                    artifact = NovelAudioSegmentArtifactEntity(
                        planId = plan.planId,
                        segmentId = segment.segmentId,
                        ttsProfile = stored.ttsProfile,
                        contentType = stored.contentType,
                        sha256 = stored.sha256,
                        size = stored.size,
                        path = stored.path,
                        state = NovelAudioStates.READY
                    ),
                    expectedGeneration = plan.generation,
                    expectedExecutionAttempt = execution.executionAttempt,
                    expectedSegmentIds = expectedSegmentIds,
                    filesRoot = filesRoot,
                    decoder = decoder
                )
                if (!saved) {
                    return Result.Failed("STALE_PLAN")
                }
                if (!repository.updateTask(
                        taskId = taskId,
                        generation = plan.generation,
                        executionAttempt = execution.executionAttempt,
                        state = NovelAudioStates.RUNNING,
                        progress = ((index + 1) * 100) / segmentsToDownload.size
                    )
                ) {
                    return Result.Failed("STALE_PLAN")
                }
            }
            if (!repository.allArtifactsReady(plan.planId, expectedSegmentIds)) {
                repository.updatePlan(
                    planId = plan.planId,
                    generation = plan.generation,
                    executionAttempt = execution.executionAttempt,
                    state = NovelAudioStates.FAILED,
                    progress = 0
                )
                repository.updateTask(
                    taskId = taskId,
                    generation = plan.generation,
                    executionAttempt = execution.executionAttempt,
                    state = NovelAudioStates.FAILED,
                    progress = 0
                )
                Result.Failed("PLAN_NOT_READY")
            } else {
                if (!repository.validateExecutionArtifacts(execution, filesRoot, decoder) ||
                    !repository.finishExecution(execution)
                ) {
                    return Result.Failed("STALE_PLAN")
                }
                Result.Ready
            }
        } catch (error: CancellationException) {
            repository.releaseExecution(execution)
            throw error
        } catch (error: Throwable) {
            repository.updatePlan(
                planId = plan.planId,
                generation = plan.generation,
                executionAttempt = execution.executionAttempt,
                state = NovelAudioStates.FAILED,
                progress = 0
            )
            repository.updateTask(
                taskId = taskId,
                generation = plan.generation,
                executionAttempt = execution.executionAttempt,
                state = NovelAudioStates.FAILED,
                progress = 0
            )
            Result.Failed(downloadFailureReason(error))
        }
    }

    sealed interface Result {
        data object Ready : Result
        data object Cancelled : Result
        data class Failed(val reason: String) : Result
    }
}

internal fun canDecodeNovelAudio(file: File): Boolean {
    if (!file.isFile || file.length() <= 0L) return false
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return runCatching {
        extractor.setDataSource(file.absolutePath)
        var audioTrack = -1
        var audioFormat: MediaFormat? = null
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                audioTrack = index
                audioFormat = format
                break
            }
        }
        if (audioTrack < 0 || audioFormat == null) return@runCatching false

        extractor.selectTrack(audioTrack)
        val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@runCatching false
        codec = MediaCodec.createDecoderByType(mime)
        codec?.configure(audioFormat, null, null, 0)
        codec?.start()

        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MILLIS
        var inputEnded = false
        var outputEnded = false
        var decodedBytes = 0L
        while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
            if (!inputEnded) {
                val inputIndex = codec?.dequeueInputBuffer(DECODE_TIMEOUT_US) ?: -1
                if (inputIndex >= 0) {
                    val input = codec?.getInputBuffer(inputIndex)
                    input?.clear()
                    val sampleSize = input?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec?.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    } else {
                        codec?.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0
                        )
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec?.dequeueOutputBuffer(info, DECODE_TIMEOUT_US) ?: -1) {
                MediaCodec.INFO_TRY_AGAIN_LATER,
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    decodedBytes += info.size.toLong().coerceAtLeast(0L)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec?.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        outputEnded && decodedBytes > 0L
    }.getOrDefault(false)
        .also {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
}

private const val DECODE_TIMEOUT_US = 10_000L
private const val DECODE_TIMEOUT_MILLIS = 10_000L

/**
 * 下载失败原因：服务端异常附带 kind（如 TIMEOUT、CONCURRENCY_LIMIT），
 * 否则真机只能看到笼统的类名，无法区分超时、限流与本地额度。
 */
internal fun downloadFailureReason(error: Throwable): String {
    val name = error::class.simpleName.orEmpty()
    return if (error is NovelAudioServerException) "$name:${error.kind}" else name
}

internal object NovelAudioIdentityForDownload {
    fun taskId(planId: String): String =
        io.legado.app.help.readaloud.novel.NovelAudioIdentity.storageKey(planId, "download")
}

class NovelAudioRetryPolicy(
    private val maxAttempts: Int = 3,
    private val delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {
    init {
        require(maxAttempts in 1..3)
    }

    suspend fun <T> run(operation: suspend () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= maxAttempts || !isRetryable(error)) throw error
                delayMillis(attempt * 250L)
                attempt++
            }
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        return error is NovelAudioServerException &&
            error.kind in setOf("TIMEOUT", "UNAVAILABLE", "RATE_LIMIT")
    }
}
