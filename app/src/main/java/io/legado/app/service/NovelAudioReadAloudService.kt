package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioSegmentArtifactEntity
import io.legado.app.data.entities.NovelAudioPlanJson
import io.legado.app.data.entities.NovelAudioStates
import io.legado.app.help.readaloud.NovelAudioPreparationState
import io.legado.app.help.readaloud.ReadAloudProgressState
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.novel.NovelAudioPreparationCoordinator
import io.legado.app.help.readaloud.novel.NovelAudioAutoPrefetchDriver
import io.legado.app.help.readaloud.novel.NovelAudioChapterPlan
import io.legado.app.help.readaloud.novel.NovelAudioContinuationPolicy
import io.legado.app.help.readaloud.novel.NovelAudioParagraphCoordinate
import io.legado.app.help.readaloud.novel.NovelAudioPositionMapper
import io.legado.app.help.readaloud.novel.NovelAudioProgressPersister
import io.legado.app.help.readaloud.novel.NovelAudioRepository
import io.legado.app.help.readaloud.novel.NovelAudioSegmentIntent
import io.legado.app.help.readaloud.offline.canDecodeNovelAudio
import io.legado.app.help.readaloud.offline.AudioPrefetchLifecycle
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.help.readaloud.offline.NovelAudioPathCodec
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import java.io.File
import java.security.MessageDigest

/**
 * 已冻结计划的本地播放服务。
 *
 * 该服务只消费 Room 中的完整计划和 READY artifact，不分析正文、不发起 TTS 请求，
 * 也不把空文件或静音文件当成成功产物。
 */
@SuppressLint("UnsafeOptInUsageError")
class NovelAudioReadAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private var prepareJob: Coroutine<*>? = null
    private var prepared: PlaybackPreparation.Ready? = null
    private var currentSegmentIndex = 0
    private var lastPreparationError: BlockReason? = null
    private var pendingPreparationWork: AudioPrefetchLifecycle.Work? = null

    /**
     * 后台播放时 Activity 不在前台，原有「进度事件 → Activity 写 durChapterPos」链断开，
     * 因此服务自己落盘，杀进程后才能从听到的位置续播。
     */
    private val progressPersister = NovelAudioProgressPersister { bookUrl, chapterIndex, position ->
        if (ReadBook.book?.bookUrl == bookUrl && ReadBook.durChapterIndex == chapterIndex) {
            synchronized(ReadBook) {
                ReadBook.durChapterPos = position
                ReadBook.saveRead(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<NovelAudioPreparationState>(EventBus.NOVEL_AUDIO_PREPARATION) { state ->
            val work = pendingPreparationWork ?: return@observeEvent
            val bookUrl = ReadBook.book?.bookUrl.orEmpty()
            val chapterIndex = ReadBook.durChapterIndex
            val generation = NovelAudioPreparationCoordinator.cachedGeneration(
                bookUrl,
                chapterIndex
            )
            if (shouldResumeAfterPreparation(
                    state = state,
                    currentBookUrl = bookUrl,
                    currentChapterIndex = chapterIndex,
                    currentGeneration = generation,
                    hasCurrentWork = AudioPrefetchPlayback.lifecycle.isCurrent(work)
                )
            ) {
                prepareAndPlay(work, readyPlanId = state.planId)
            } else if (shouldHandlePreparationFailure(
                    state = state,
                    currentBookUrl = bookUrl,
                    currentChapterIndex = chapterIndex,
                    currentGeneration = generation,
                    hasCurrentWork = AudioPrefetchPlayback.lifecycle.isCurrent(work)
                )
            ) {
                pendingPreparationWork = null
                pauseReadAloud()
                publishPlaybackState(
                    phase = ReadAloudPlaybackState.PHASE_ERROR,
                    message = state.reason
                )
            }
        }
    }

    override fun onDestroy() {
        pendingPreparationWork = null
        prepareJob?.cancel()
        progressPersister.flush()
        NovelAudioAutoPrefetchDriver.revoke()
        exoPlayer.release()
        super.onDestroy()
        publishPlaybackState(ReadAloudPlaybackState.PHASE_STOPPED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> {
                dispatchPlayback(
                    intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                    intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION),
                    flags
                ) {
                    prepareAndPlay(it)
                }
                return super.onStartCommand(null, flags, startId)
            }

            IntentAction.resume -> {
                dispatchPlayback(
                    intent.getStringExtra(AudioPrefetchPlayback.REQUEST),
                    intent.getStringExtra(AudioPrefetchPlayback.CONTINUATION),
                    flags
                ) {
                    if (prepared == null) prepareAndPlay(it) else resumeReadAloud()
                }
                return super.onStartCommand(null, flags, startId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun play() {
        val local = prepared ?: return
        if (!requestFocus()) return
        super.play()
        if (local.files.isNotEmpty() && exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
        exoPlayer.play()
        publishSegmentProgress()
    }

    override fun playStop() {
        pendingPreparationWork = null
        progressPersister.flush()
        NovelAudioAutoPrefetchDriver.revoke()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        prepared = null
        currentSegmentIndex = 0
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        pendingPreparationWork = null
        progressPersister.flush()
        NovelAudioAutoPrefetchDriver.revoke()
        super.pauseReadAloud(abandonFocus)
        exoPlayer.pause()
        publishPlaybackState(ReadAloudPlaybackState.PHASE_PAUSED, playing = false)
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (prepared != null) {
            exoPlayer.play()
            publishSegmentProgress()
            publishPlaybackState(ReadAloudPlaybackState.PHASE_PLAYING, playing = true)
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        // 语速已冻结在 segment intent 中；改变全局语速不会伪造或重写既有 artifact。
        AppLog.putDebug("AI 听书音频速度已冻结，当前章节不重新合成")
    }

    override fun onUserResumeFromMedia(work: AudioPrefetchLifecycle.Work): Boolean {
        if (prepared == null) prepareAndPlay(work) else resumeReadAloud()
        return true
    }

    override fun prevChapter() {
        playStop()
        super.prevChapter()
    }

    override fun nextChapter() {
        playStop()
        super.nextChapter()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                if (!pause) {
                    exoPlayer.play()
                    publishSegmentProgress()
                }
            }

            Player.STATE_ENDED -> {
                if (currentSegmentIndex < (prepared?.files?.lastIndex ?: -1)) {
                    currentSegmentIndex++
                    publishSegmentProgress()
                } else if (!checkTimerAtChapterEnd()) {
                    nextChapter()
                }
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            currentSegmentIndex < (prepared?.files?.lastIndex ?: -1)
        ) {
            currentSegmentIndex++
            publishSegmentProgress()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        lastPreparationError = BlockReason.DECODE_FAILURE
        prepared?.let { ready ->
            NovelAudioRepository(appDb).invalidateReadyState(
                planId = ready.plan.planId,
                expectedGeneration = ready.plan.generation,
                expectedExecutionAttempt = ready.executionAttempt,
                segmentId = ready.plan.playableSegments
                    .getOrNull(currentSegmentIndex)
                    ?.segmentId
            )
        }
        AppLog.put("AI 听书音频无法解码，已暂停", error)
        pauseReadAloud()
    }

    private fun prepareAndPlay(work: AudioPrefetchLifecycle.Work?, readyPlanId: String? = null) {
        if (work != null &&
            !AudioPrefetchPlayback.lifecycle.isCurrent(work)
        ) return
        if (work != null) {
            pendingPreparationWork = work
            publishPlaybackState(ReadAloudPlaybackState.PHASE_PREPARING, playing = false)
        }
        prepareJob?.cancel()
        prepareJob = execute {
            val physicalBookUrl = ReadBook.book?.bookUrl.orEmpty()
            val chapterIndex = ReadBook.durChapterIndex
            val entity = selectPlaybackPlan(
                readyPlanId = readyPlanId,
                physicalBookUrl = physicalBookUrl,
                chapterIndex = chapterIndex,
                planById = appDb.novelAudioDao::chapterPlan,
                currentPlan = { appDb.novelAudioDao.currentChapterPlan(physicalBookUrl, chapterIndex) }
            ) ?: return@execute PlaybackPreparation.Blocked(BlockReason.MISSING_PLAN)
            if (entity.state != NovelAudioStates.READY) {
                return@execute PlaybackPreparation.Blocked(
                    reason = BlockReason.PLAN_NOT_READY,
                    planId = entity.planId,
                    generation = entity.generation,
                    executionAttempt = entity.executionAttempt
                )
            }
            val plan = NovelAudioPlanJson.decode(entity.planJson)
                ?: return@execute PlaybackPreparation.Blocked(
                    reason = BlockReason.INVALID_PLAN,
                    planId = entity.planId,
                    generation = entity.generation,
                    executionAttempt = entity.executionAttempt
                )
            val artifacts = appDb.novelAudioDao.segmentArtifacts(plan.planId)
            preparePlayback(
                plan = plan,
                artifacts = artifacts,
                filesRoot = File(filesDir, AUDIO_DIRECTORY),
                decoder = ::canDecode,
                executionAttempt = entity.executionAttempt
            )
        }.onSuccess { result ->
            when (result) {
                is PlaybackPreparation.Ready -> {
                    if (work != null &&
                        !AudioPrefetchPlayback.lifecycle.isCurrent(work)
                    ) return@onSuccess
                    lastPreparationError = null
                    pendingPreparationWork = null
                    prepared = result
                    currentSegmentIndex = segmentIndexForChapterPosition(
                        chapterPosition = ReadBook.durChapterPos,
                        segments = result.plan.playableSegments,
                        paragraphs = currentParagraphCoordinates()
                    ).coerceIn(0, result.files.lastIndex)
                    exoPlayer.setMediaItems(
                        result.files.map { MediaItem.fromUri(Uri.fromFile(it)) },
                        currentSegmentIndex,
                        0L
                    )
                    val chapterCount = ReadBook.curTextChapter?.chaptersSize ?: (ReadBook.durChapterIndex + 1)
                    prefetchAssembled(
                        work = work,
                        bookUrl = result.plan.physicalBookUrl,
                        chapterIndex = result.plan.chapterIndex,
                        chapterCount = chapterCount
                    )
                    // 窗口由 prefetchAssembled 更新，驱动必须排在其后才能拿到非空窗口。
                    NovelAudioAutoPrefetchDriver.onChapterReady(
                        work = work,
                        bookUrl = result.plan.physicalBookUrl,
                        chapterIndex = result.plan.chapterIndex
                    )
                    play()
                }

                is PlaybackPreparation.Blocked -> {
                    if (result.planId.isNotBlank() && result.generation >= 0L) {
                        NovelAudioRepository(appDb).invalidateReadyState(
                            planId = result.planId,
                            expectedGeneration = result.generation,
                            expectedExecutionAttempt = result.executionAttempt,
                            segmentId = result.segmentId
                        )
                    }
                    if (result.reason == BlockReason.MISSING_PLAN ||
                        result.reason == BlockReason.PLAN_NOT_READY
                    ) {
                        val hasWork = work != null &&
                            AudioPrefetchPlayback.lifecycle.isCurrent(work)
                        if (hasWork) {
                            pendingPreparationWork = work
                            publishPlaybackState(
                                ReadAloudPlaybackState.PHASE_PREPARING,
                                playing = false
                            )
                        }
                        // 跨章连播不带用户发起标记，准备策略不会自动发起；
                        // 若此处不补一次，界面会停在准备中且永远收不到完成事件。
                        if (NovelAudioContinuationPolicy.shouldPrepareOnBlock(
                                missingOrNotReady = true,
                                hasCurrentWork = hasWork
                            )
                        ) {
                            requestContinuationPreparation()
                        }
                        return@onSuccess
                    }
                    lastPreparationError = result.reason
                    pendingPreparationWork = null
                    prepared = null
                    exoPlayer.clearMediaItems()
                    pauseReadAloud()
                    toastOnUi("AI 听书音频未准备完成")
                    publishPlaybackState(
                        ReadAloudPlaybackState.PHASE_ERROR,
                        message = result.reason.name
                    )
                }
            }
        }.onError {
            lastPreparationError = BlockReason.PREPARATION_FAILURE
            pendingPreparationWork = null
            prepared = null
            pauseReadAloud()
            AppLog.put("AI 听书音频准备失败：${it.localizedMessage}")
            publishPlaybackState(
                ReadAloudPlaybackState.PHASE_ERROR,
                message = BlockReason.PREPARATION_FAILURE.name
            )
        }
    }

    /**
     * 跨章连播补救：为当前章补一次准备请求。
     *
     * 跨章路径不携带用户发起标记，[NovelAudioPreparationPolicy] 不会自动发起准备；
     * 若这里不补，界面会停在准备中并永远等不到完成事件。
     * 正文已缓存时 startCached 会立即开始；否则等待 ReadBook 的最终正文回调。
     */
    private fun requestContinuationPreparation() {
        val bookUrl = ReadBook.book?.bookUrl.orEmpty()
        val chapterIndex = ReadBook.durChapterIndex
        if (bookUrl.isBlank() || chapterIndex < 0) return
        NovelAudioPreparationCoordinator.request(bookUrl, chapterIndex)
        NovelAudioPreparationCoordinator.startCached(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            isCurrent = {
                ReadBook.book?.bookUrl == bookUrl &&
                    ReadBook.durChapterIndex == chapterIndex &&
                    ReadBook.contentLoadFinish
            }
        )
    }

    private fun publishPlaybackState(
        phase: String,
        message: String = "",
        playing: Boolean? = null
    ) {
        val plan = prepared?.plan
        val cue = plan?.playableSegments?.getOrNull(currentSegmentIndex)
        val cueChapterPosition = cue?.let {
            chapterPositionForSegment(it, currentParagraphCoordinates())
        } ?: -1
        postEvent(
            EventBus.READ_ALOUD_PLAYBACK_STATE,
            ReadAloudPlaybackState(
                phase = phase,
                bookUrl = plan?.physicalBookUrl ?: ReadBook.book?.bookUrl.orEmpty(),
                chapterIndex = plan?.chapterIndex ?: ReadBook.durChapterIndex,
                chapterUrl = plan?.chapterUrl.orEmpty(),
                cueIndex = currentSegmentIndex,
                cueCount = plan?.playableSegments?.size ?: 0,
                cueChapterPosition = cueChapterPosition,
                cueKey = cue?.segmentId.orEmpty(),
                cueText = cue?.text.orEmpty(),
                planKey = plan?.planId.orEmpty(),
                message = message,
                playing = playing,
                buffering = phase == ReadAloudPlaybackState.PHASE_PREPARING,
                serviceRunning = isRun
            )
        )
    }

    private fun publishSegmentProgress() {        val plan = prepared?.plan ?: return
        val segment = plan.playableSegments.getOrNull(currentSegmentIndex) ?: return
        val chapterPosition = chapterPositionForSegment(
            segment = segment,
            paragraphs = currentParagraphCoordinates()
        ) ?: return
        upTtsProgress(chapterPosition)
        progressPersister.onProgress(
            bookUrl = plan.physicalBookUrl,
            chapterIndex = plan.chapterIndex,
            chapterPosition = chapterPosition,
            atMillis = System.currentTimeMillis()
        )
        postEvent(
            EventBus.READ_ALOUD_PROGRESS,
            ReadAloudProgressState(
                bookUrl = plan.physicalBookUrl,
                chapterIndex = plan.chapterIndex,
                chapterUrl = plan.chapterUrl,
                chapterPosition = chapterPosition,
                cueIndex = currentSegmentIndex,
                planKey = plan.planId
            )
        )
        publishPlaybackState(
            phase = if (pause) {
                ReadAloudPlaybackState.PHASE_PAUSED
            } else {
                ReadAloudPlaybackState.PHASE_PLAYING
            },
            playing = !pause
        )
    }

    private fun currentParagraphCoordinates(): List<NovelAudioParagraphCoordinate> {
        return ReadBook.curTextChapter?.paragraphs.orEmpty().map {
            NovelAudioParagraphCoordinate(
                sourceIndex = it.sourceIndex,
                chapterPosition = it.chapterPosition,
                textLength = it.length
            )
        }
    }

    private fun canDecode(file: File): Boolean {
        return canDecodeNovelAudio(file)
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<NovelAudioReadAloudService>(actionStr)
    }

    sealed interface PlaybackPreparation {
        data class Ready(
            val plan: NovelAudioChapterPlan,
            val files: List<File>,
            val executionAttempt: Long = 0L
        ) : PlaybackPreparation

        data class Blocked(
            val reason: BlockReason,
            val planId: String = "",
            val generation: Long = -1L,
            val segmentId: String? = null,
            val executionAttempt: Long = 0L
        ) : PlaybackPreparation
    }

    enum class BlockReason {
        MISSING_PLAN,
        PLAN_NOT_READY,
        INVALID_PLAN,
        MISSING_ARTIFACT,
        ARTIFACT_NOT_READY,
        PATH_INVALID,
        FILE_MISSING,
        SIZE_MISMATCH,
        CHECKSUM_MISMATCH,
        UNSUPPORTED_CONTENT_TYPE,
        DECODE_FAILURE,
        PREPARATION_FAILURE
    }

    companion object {
        private const val AUDIO_DIRECTORY = "novel-audio"

        /**
         * 准备完成事件带来的 planId 优先于「本章最新计划」查询：代际号在进程重启后从 1 重新计数，
         * 上次进程留下的高代际失败计划会排在刚准备好的计划前面，导致播放判定未就绪并无限重新准备。
         */
        fun selectPlaybackPlan(
            readyPlanId: String?,
            physicalBookUrl: String,
            chapterIndex: Int,
            planById: (String) -> NovelAudioChapterPlanEntity?,
            currentPlan: () -> NovelAudioChapterPlanEntity?
        ): NovelAudioChapterPlanEntity? {
            val ready = readyPlanId?.takeIf { it.isNotBlank() }?.let(planById)?.takeIf {
                it.physicalBookUrl == physicalBookUrl && it.chapterIndex == chapterIndex
            }
            return ready ?: currentPlan()
        }

        fun shouldResumeAfterPreparation(
            state: NovelAudioPreparationState,
            currentBookUrl: String,
            currentChapterIndex: Int,
            currentGeneration: Long?,
            hasCurrentWork: Boolean
        ): Boolean {
            return hasCurrentWork &&
                state.ready &&
                state.planId.isNotBlank() &&
                state.bookUrl == currentBookUrl &&
                state.chapterIndex == currentChapterIndex &&
                state.generation >= 0L &&
                state.generation == currentGeneration
        }

        fun shouldHandlePreparationFailure(
            state: NovelAudioPreparationState,
            currentBookUrl: String,
            currentChapterIndex: Int,
            currentGeneration: Long?,
            hasCurrentWork: Boolean
        ): Boolean {
            return hasCurrentWork &&
                !state.ready &&
                state.bookUrl == currentBookUrl &&
                state.chapterIndex == currentChapterIndex &&
                state.generation >= 0L &&
                state.generation == currentGeneration
        }

        fun chapterPositionForSegment(
            segment: NovelAudioSegmentIntent,
            paragraphs: List<NovelAudioParagraphCoordinate>
        ): Int? {
            return segment.orderedRanges.firstOrNull()?.let { range ->
                NovelAudioPositionMapper.chapterPosition(range, paragraphs)
            }
        }

        fun segmentIndexForChapterPosition(
            chapterPosition: Int,
            segments: List<NovelAudioSegmentIntent>,
            paragraphs: List<NovelAudioParagraphCoordinate>
        ): Int {
            return NovelAudioPositionMapper.segmentIndexAtOrBefore(
                chapterPosition = chapterPosition.coerceAtLeast(0),
                segmentPositions = segments.map {
                    chapterPositionForSegment(it, paragraphs)
                }
            )
        }

        fun preparePlayback(
            plan: NovelAudioChapterPlan,
            artifacts: List<NovelAudioSegmentArtifactEntity>,
            filesRoot: File,
            decoder: (File) -> Boolean,
            executionAttempt: Long = 0L
        ): PlaybackPreparation {
            fun blocked(
                reason: BlockReason,
                segmentId: String? = null
            ) = PlaybackPreparation.Blocked(
                reason = reason,
                planId = plan.planId,
                generation = plan.generation,
                segmentId = segmentId,
                executionAttempt = executionAttempt
            )

            val playableSegments = plan.playableSegments
            if (playableSegments.isEmpty()) {
                return blocked(BlockReason.INVALID_PLAN)
            }
            val bySegment = artifacts.associateBy { it.segmentId }
            val files = ArrayList<File>(playableSegments.size)
            for (segment in playableSegments) {
                val artifact = bySegment[segment.segmentId]
                    ?: return blocked(BlockReason.MISSING_ARTIFACT, segment.segmentId)
                if (artifact.state != NovelAudioStates.READY) {
                    return blocked(BlockReason.ARTIFACT_NOT_READY, segment.segmentId)
                }
                val contentType = artifact.contentType.substringBefore(';').trim()
                if (runCatching { NovelAudioPathCodec.extensionFor(contentType) }.isFailure) {
                    return blocked(BlockReason.UNSUPPORTED_CONTENT_TYPE, segment.segmentId)
                }
                val file = File(filesRoot, artifact.path)
                val root = runCatching { filesRoot.canonicalFile }.getOrNull()
                    ?: return blocked(BlockReason.PATH_INVALID, segment.segmentId)
                val canonical = runCatching { file.canonicalFile }.getOrNull()
                    ?: return blocked(BlockReason.PATH_INVALID, segment.segmentId)
                if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                    return blocked(BlockReason.PATH_INVALID, segment.segmentId)
                }
                if (!canonical.isFile) {
                    return blocked(BlockReason.FILE_MISSING, segment.segmentId)
                }
                if (canonical.length() <= 0L || canonical.length() != artifact.size) {
                    return blocked(BlockReason.SIZE_MISMATCH, segment.segmentId)
                }
                if (sha256(canonical) != artifact.sha256.lowercase()) {
                    return blocked(BlockReason.CHECKSUM_MISMATCH, segment.segmentId)
                }
                if (!decoder(canonical)) {
                    return blocked(BlockReason.DECODE_FAILURE, segment.segmentId)
                }
                files += canonical
            }
            return PlaybackPreparation.Ready(plan, files, executionAttempt)
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
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
