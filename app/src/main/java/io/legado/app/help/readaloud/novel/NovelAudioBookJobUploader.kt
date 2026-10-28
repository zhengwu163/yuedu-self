package io.legado.app.help.readaloud.novel

import io.legado.app.data.dao.NovelAudioBookJobDao
import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.readaloud.analysis.TextUnitParser
import io.legado.app.help.readaloud.server.BookJobAlias
import io.legado.app.help.readaloud.server.BookJobBinding
import io.legado.app.help.readaloud.server.BookJobChapterUpload
import io.legado.app.help.readaloud.server.BookJobCharacter
import io.legado.app.help.readaloud.server.BookJobCreateRequest
import io.legado.app.help.readaloud.server.BookJobCreated
import io.legado.app.help.readaloud.server.BookJobMerge
import io.legado.app.help.readaloud.server.BookJobUnit
import io.legado.app.help.readaloud.server.NovelAudioServerException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 整本生成的章节顺序：当前章 → 书末 → 第 1 章 → 当前章前一章。 */
object NovelAudioBookJobOrder {
    fun uploadOrder(chapterCount: Int, currentIndex: Int): List<Int> {
        require(chapterCount >= 0)
        if (chapterCount == 0) return emptyList()
        val current = currentIndex.coerceIn(0, chapterCount - 1)
        return (current until chapterCount) + (0 until current)
    }

    /** 发给电脑的章节 ID；只在本任务内有效，正文变化由快照散列另行校验。 */
    fun chapterId(chapterIndex: Int): String = "c$chapterIndex"
}

/** 建任务：把手机已有的人物、别名、合并与音色绑定一起交给电脑，电脑在此基础上继续分析。 */
class NovelAudioBookJobCreator(
    private val dao: NovelAudioBookJobDao,
    private val characterStore: NovelAudioCharacterStore,
    private val createRemote: suspend (BookJobCreateRequest) -> BookJobCreated,
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun create(
        workKey: String,
        physicalBookUrl: String,
        title: String,
        serverScope: String,
        chapterCount: Int,
        currentIndex: Int
    ): NovelAudioBookJobEntity {
        require(chapterCount in 1..MAX_CHAPTERS)
        val characters = characterStore.characters(workKey)
        val ids = characters.map { it.id }.toSet()
        val created = createRemote(BookJobCreateRequest(
            bookId = workKey,
            title = title.ifBlank { workKey }.take(MAX_TITLE),
            expectedChapterCount = chapterCount,
            characters = characters.map { BookJobCharacter(it.id.toString(), it.name, it.gender) },
            aliases = characterStore.aliases(workKey).filter { it.characterId in ids }.map {
                BookJobAlias(it.characterId.toString(), it.alias, it.normalizedAlias)
            },
            merges = characterStore.activeMerges(workKey).map {
                BookJobMerge(it.absorbedCharacterId.toString(), it.primaryCharacterId.toString())
            },
            bindings = phoneBindings(characterStore, workKey, serverScope, createdUpTo = null)
        ))
        val now = clock()
        val job = NovelAudioBookJobEntity(
            jobId = created.jobId,
            serverScope = serverScope,
            workKey = workKey,
            physicalBookUrl = physicalBookUrl,
            title = title,
            state = NovelAudioBookJobStates.UPLOADING,
            maxSegmentChars = created.maxSegmentChars,
            chapterCount = chapterCount,
            createdAt = now,
            updatedAt = now
        )
        dao.createJob(job, NovelAudioBookJobOrder.uploadOrder(chapterCount, currentIndex)
            .mapIndexed { position, index ->
                NovelAudioBookJobChapterEntity(
                    jobId = job.jobId,
                    chapterIndex = index,
                    chapterId = NovelAudioBookJobOrder.chapterId(index),
                    position = position,
                    updatedAt = now
                )
            })
        return job
    }

    companion object {
        private const val MAX_CHAPTERS = 10_000
        private const val MAX_TITLE = 256

        /**
         * 手机上的旁白与人物音色绑定。createdUpTo 不为空时只取建任务前已有的人物：
         * 这些人物在电脑上 ID 相同，手动改音色后可以直接同步；电脑新建的人物 ID 可能不同，不同步。
         */
        fun phoneBindings(
            store: NovelAudioCharacterStore,
            workKey: String,
            serverScope: String,
            createdUpTo: Long?
        ): List<BookJobBinding> {
            val ids = store.characters(workKey)
                .filter { createdUpTo == null || it.createdAt <= createdUpTo }
                .map { it.id }.toSet()
            return store.voiceBindings(ids + 0L)
                .filter { it.scope == serverScope && (it.characterId == 0L || it.characterId in ids) }
                .map { BookJobBinding(it.characterId.toString(), it.voiceAssetId, it.revision) }
        }
    }
}

/**
 * 上传阶段：逐章取快照、解析单元、按电脑声明的段长切小段并上传；不调用分析接口。
 * 已上传的章节不会重传，所以中途断网或进程被杀后再次调用即可续传。
 */
class NovelAudioBookJobUploader(
    private val dao: NovelAudioBookJobDao,
    private val loadSnapshot: suspend (chapterIndex: Int) -> NovelAudioChapterSnapshot?,
    private val upload: suspend (jobId: String, chapterId: String, BookJobChapterUpload) -> Boolean,
    private val markUploaded: suspend (jobId: String) -> String,
    private val canContinue: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class Outcome { UPLOADED, STOPPED, WAITING_NETWORK, JOB_GONE }

    suspend fun run(jobId: String, onChapter: (uploaded: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        while (true) {
            currentCoroutineContext().ensureActive()
            val job = dao.job(jobId) ?: return Outcome.JOB_GONE
            if (job.state != NovelAudioBookJobStates.UPLOADING) return Outcome.STOPPED
            if (!canContinue()) return Outcome.WAITING_NETWORK
            val next = dao.nextChapterInUploadState(jobId, NovelAudioBookJobUploadStates.PENDING)
                ?: break
            val snapshot = loadSnapshot(next.chapterIndex)
            val body = snapshot?.let { chapterBody(it, next.chapterIndex, job.maxSegmentChars) }
            if (snapshot == null || body == null) {
                dao.setUploadState(jobId, next.chapterIndex, NovelAudioBookJobUploadStates.MISSING, "", clock())
                continue
            }
            try {
                upload(jobId, next.chapterId, body)
            } catch (error: NovelAudioServerException) {
                return handle(jobId, error)
            }
            dao.setUploadState(jobId, next.chapterIndex, NovelAudioBookJobUploadStates.UPLOADED,
                snapshot.snapshotHash, clock())
            val chapters = dao.chapters(jobId)
            onChapter(chapters.count { it.uploadState == NovelAudioBookJobUploadStates.UPLOADED }, chapters.size)
        }
        try {
            markUploaded(jobId)
        } catch (error: NovelAudioServerException) {
            return handle(jobId, error)
        }
        dao.setJobState(jobId, NovelAudioBookJobStates.UPLOADED, "", clock())
        return Outcome.UPLOADED
    }

    private fun handle(jobId: String, error: NovelAudioServerException): Outcome = when (error.kind) {
        // 电脑上的任务已被删除或取消：手机侧同步结束，不再重试。
        "UNKNOWN_JOB", "JOB_CLOSED" -> {
            dao.setJobState(jobId, NovelAudioBookJobStates.CANCELLED, error.kind, clock())
            Outcome.JOB_GONE
        }
        "TIMEOUT", "UNAVAILABLE" -> Outcome.WAITING_NETWORK
        else -> throw error
    }

    enum class Pass { AGAIN, WAIT, STOP }

    companion object {
        /**
         * 前台服务跑完一轮后怎么办：有任务前进就马上再跑；只差 Wi‑Fi 就等；
         * 未配置电脑、任务被暂停或等太久就退出，下次打开 App 再续传，避免空转。
         */
        fun nextPass(outcomes: List<Outcome>, waits: Int, maxWaits: Int): Pass = when {
            outcomes.any { it == Outcome.UPLOADED || it == Outcome.JOB_GONE } -> Pass.AGAIN
            Outcome.WAITING_NETWORK in outcomes && waits < maxWaits -> Pass.WAIT
            else -> Pass.STOP
        }

        /** 返回 null 表示该章没有可朗读的单元。 */
        fun chapterBody(
            snapshot: NovelAudioChapterSnapshot,
            chapterIndex: Int,
            maxSegmentChars: Int
        ): BookJobChapterUpload? {
            val units = snapshot.parseUnits().map { unit ->
                BookJobUnit(
                    unitId = unit.unitId,
                    text = unit.text,
                    roleType = unit.roleType,
                    // 与 NovelAudioPlanFactory.create 同一切分，导入时按（单元，序号）一一对应。
                    pieces = TextUnitParser.splitForSynthesis(snapshot.textSnapshot, unit, maxSegmentChars)
                        .map { it.first }
                )
            }
            return if (units.isEmpty()) null else BookJobChapterUpload(chapterIndex, snapshot.snapshotHash, units)
        }
    }
}
