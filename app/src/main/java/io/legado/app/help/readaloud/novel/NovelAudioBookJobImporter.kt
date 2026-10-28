package io.legado.app.help.readaloud.novel

import io.legado.app.data.dao.NovelAudioBookJobDao
import io.legado.app.data.entities.NovelAudioBookJobChapterEntity
import io.legado.app.data.entities.NovelAudioBookJobEntity
import io.legado.app.data.entities.NovelAudioBookJobImportStates
import io.legado.app.data.entities.NovelAudioBookJobStates
import io.legado.app.data.entities.NovelAudioBookJobUploadStates
import io.legado.app.help.readaloud.analysis.TextUnitParser
import io.legado.app.help.readaloud.server.AliasUpdate
import io.legado.app.help.readaloud.server.BookJobChapterResult
import io.legado.app.help.readaloud.server.BookJobChapterStates
import io.legado.app.help.readaloud.server.BookJobDetail
import io.legado.app.help.readaloud.server.BookJobPiece
import io.legado.app.help.readaloud.server.BookJobStates
import io.legado.app.help.readaloud.server.NewCharacter
import io.legado.app.help.readaloud.server.NovelAudioServerException
import io.legado.app.help.readaloud.server.SynthesisRequest
import io.legado.app.help.readaloud.server.SynthesizedAudio
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest

/**
 * 同步阶段：把电脑生成完的章节导入成本地 READY 计划（PINNED），之后播放全程离线。
 *
 * 电脑的分析结果按手机规则重新落地：新人物走 CharacterRegistry.resolve 拿手机 ID，
 * 计划由手机用当前快照重建，段与电脑小段按（单元，序号）逐一核对文字散列，
 * 任何不一致都不导入，宁可该章重新生成也不播错音频。
 */
class NovelAudioBookJobImporter(
    private val dao: NovelAudioBookJobDao,
    private val registry: CharacterRegistry,
    private val loadSnapshot: suspend (chapterIndex: Int) -> NovelAudioChapterSnapshot?,
    private val fetchDetail: suspend (jobId: String) -> BookJobDetail,
    private val fetchResult: suspend (jobId: String, chapterId: String) -> BookJobChapterResult,
    private val fetchAudio: suspend (jobId: String, audioId: String) -> SynthesizedAudio,
    private val markDownloaded: suspend (jobId: String, chapterId: String) -> Unit,
    /** 保存 PINNED 计划并逐段取音频；全部校验通过、计划变为 READY 时返回 true。 */
    private val savePlan: suspend (
        NovelAudioChapterPlan,
        suspend (SynthesisRequest) -> SynthesizedAudio
    ) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Summary(
        val imported: Int = 0,
        val stale: Int = 0,
        val failed: Int = 0,
        /** 已上传、电脑还没生成完的章节数。 */
        val waiting: Int = 0,
        val hostState: String = ""
    )

    class ImportFailure(val reason: String) : IllegalStateException(reason)

    /**
     * onlyChapter 不为空时只导入这一章（播放到该章时立即同步）。
     * canContinue 在每章之前检查，后台任务据此在系统时限前停下，下次从下一章继续。
     */
    suspend fun sync(jobId: String, onlyChapter: Int? = null, canContinue: () -> Boolean = { true }): Summary {
        val job = dao.job(jobId) ?: return Summary()
        if (job.state !in NovelAudioBookJobStates.ACTIVE) return Summary()
        val detail = try {
            fetchDetail(jobId)
        } catch (error: NovelAudioServerException) {
            if (error.kind == "UNKNOWN_JOB") {
                dao.setJobState(jobId, NovelAudioBookJobStates.CANCELLED, error.kind, clock())
                return Summary()
            }
            throw error
        }
        val host = detail.chapters.associateBy { it.chapterId }
        var summary = Summary(hostState = detail.state)
        var interrupted = false
        for (row in dao.chapters(jobId)) {
            currentCoroutineContext().ensureActive()
            if (onlyChapter != null && row.chapterIndex != onlyChapter) continue
            if (!canContinue()) {
                interrupted = true
                break
            }
            if (row.uploadState != NovelAudioBookJobUploadStates.UPLOADED ||
                row.importState != NovelAudioBookJobImportStates.NONE) continue
            val state = host[row.chapterId]
            when {
                state == null || state.snapshotHash != row.snapshotHash ->
                    summary = summary.copy(waiting = summary.waiting + 1)
                state.state == BookJobChapterStates.FAILED -> {
                    dao.setImportState(jobId, row.chapterIndex, NovelAudioBookJobImportStates.NONE, "",
                        HOST_FAILURE + state.failure.orEmpty(), clock())
                    summary = summary.copy(failed = summary.failed + 1)
                }
                state.state != BookJobChapterStates.DONE ->
                    summary = summary.copy(waiting = summary.waiting + 1)
                else -> summary = when (importChapter(job, row)) {
                    NovelAudioBookJobImportStates.IMPORTED -> summary.copy(imported = summary.imported + 1)
                    NovelAudioBookJobImportStates.STALE -> summary.copy(stale = summary.stale + 1)
                    else -> summary.copy(failed = summary.failed + 1)
                }
            }
        }
        dao.markSynced(jobId, clock())
        if (onlyChapter == null && !interrupted && job.state == NovelAudioBookJobStates.UPLOADED &&
            detail.state == BookJobStates.DONE &&
            dao.chapters(jobId).none {
                // 电脑上失败的章节要用户点「继续」才会重做，不让任务一直挂着。
                it.uploadState == NovelAudioBookJobUploadStates.UPLOADED &&
                    it.importState == NovelAudioBookJobImportStates.NONE &&
                    !it.failure.startsWith(HOST_FAILURE)
            }
        ) {
            dao.setJobState(jobId, NovelAudioBookJobStates.DONE, "", clock())
        }
        return summary
    }

    private suspend fun importChapter(job: NovelAudioBookJobEntity, row: NovelAudioBookJobChapterEntity): String {
        val snapshot = loadSnapshot(row.chapterIndex)
            ?: return record(job, row, NovelAudioBookJobImportStates.NONE, "", "TEXT_UNAVAILABLE")
        if (snapshot.snapshotHash != row.snapshotHash) {
            return record(job, row, NovelAudioBookJobImportStates.STALE, "", "TEXT_CHANGED")
        }
        val result = fetchResult(job.jobId, row.chapterId)
        val prepared = try {
            prepare(job, snapshot, result, registry)
        } catch (error: ImportFailure) {
            return record(job, row, NovelAudioBookJobImportStates.FAILED, "", error.reason)
        } catch (error: NovelAudioCharacterRegistry.AliasConflict) {
            return record(job, row, NovelAudioBookJobImportStates.FAILED, "", "ALIAS_CONFLICT")
        } catch (error: IllegalArgumentException) {
            return record(job, row, NovelAudioBookJobImportStates.FAILED, "", "PLAN_INVALID")
        }
        val ready = savePlan(prepared.plan) { request ->
            val piece = prepared.audioFor[request.text to request.voiceAssetId]
                ?: throw NovelAudioServerException("PROTOCOL")
            fetchAudio(job.jobId, piece.audioId).also {
                if (sha256(it.bytes) != piece.sha256) throw NovelAudioServerException("PROTOCOL")
            }
        }
        if (!ready) return record(job, row, NovelAudioBookJobImportStates.NONE, "", "DOWNLOAD_FAILED")
        registry.rememberVoiceBindings(prepared.bindings.values.toList())
        record(job, row, NovelAudioBookJobImportStates.IMPORTED, prepared.plan.planId, "")
        // 告知失败不影响本地播放；下次同步会再告知一次，电脑据此开始计算 30 天保留期。
        kotlin.runCatching { markDownloaded(job.jobId, row.chapterId) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        return NovelAudioBookJobImportStates.IMPORTED
    }

    private fun record(
        job: NovelAudioBookJobEntity,
        row: NovelAudioBookJobChapterEntity,
        state: String,
        planId: String,
        failure: String
    ): String {
        dao.setImportState(job.jobId, row.chapterIndex, state, planId, failure, clock())
        return state
    }

    internal data class Prepared(
        val plan: NovelAudioChapterPlan,
        val bindings: Map<Long, NovelAudioVoiceBinding>,
        val audioFor: Map<Pair<String, String>, BookJobPiece>
    )

    companion object {
        internal const val HOST_FAILURE = "HOST_"

        /**
         * 播放到某章时是否该走整本任务：已上传、还没导入的章节只从电脑导入，
         * 其余（不在任务内、未上传、已导入但音频失效、正文已变）照常逐章准备。
         */
        fun shouldImportForPlayback(row: NovelAudioBookJobChapterEntity?): Boolean =
            row != null && row.uploadState == NovelAudioBookJobUploadStates.UPLOADED &&
                row.importState == NovelAudioBookJobImportStates.NONE

        /** 纯计算部分，单独可测：电脑结果 → 手机 ID、绑定、计划与「段 → 电脑音频」映射。 */
        internal fun prepare(
            job: NovelAudioBookJobEntity,
            snapshot: NovelAudioChapterSnapshot,
            result: BookJobChapterResult,
            registry: CharacterRegistry
        ): Prepared {
            if (result.snapshotHash != snapshot.snapshotHash) throw ImportFailure("TEXT_CHANGED")
            val units = snapshot.parseUnits()
            if (units.map { it.unitId } != result.units.map { it.unitId }) throw ImportFailure("UNIT_MISMATCH")
            val pieces = units.zip(result.units).flatMap { (unit, hostUnit) ->
                val local = TextUnitParser.splitForSynthesis(snapshot.textSnapshot, unit, job.maxSegmentChars)
                if (local.size != hostUnit.pieces.size) throw ImportFailure("PIECE_MISMATCH")
                local.zip(hostUnit.pieces).map { (piece, hostPiece) ->
                    if (sha256(piece.first.toByteArray(Charsets.UTF_8)) != hostPiece.textSha256) {
                        throw ImportFailure("PIECE_MISMATCH")
                    }
                    hostPiece
                }
            }

            val ids = phoneIds(job.workKey, result, registry)
            val speakers = result.units.associate { unit ->
                unit.unitId to (ids[unit.speakerId] ?: throw ImportFailure("UNKNOWN_SPEAKER"))
            }
            val bindings = result.bindings.associate { binding ->
                val phoneId = ids[binding.characterId.toLong()] ?: throw ImportFailure("UNKNOWN_SPEAKER")
                phoneId to NovelAudioVoiceBinding(job.serverScope, phoneId, binding.voiceAssetId, binding.revision)
            }
            val needed = units.map { if (it.roleType == "narrator") 0L else speakers.getValue(it.unitId) }.toSet()
            if (!bindings.keys.containsAll(needed)) throw ImportFailure("MISSING_BINDING")

            val plan = NovelAudioPlanFactory.create(
                snapshot = snapshot,
                serverScope = job.serverScope,
                generation = snapshot.snapshotHash.hashCode().toLong() and 0x3FFF_FFFFL,
                parsedUnits = units,
                speakerIds = speakers,
                voiceBindings = bindings,
                maxSegmentChars = job.maxSegmentChars
            )
            if (plan.segments.size != pieces.size) throw ImportFailure("PIECE_MISMATCH")
            val audioFor = plan.segments.zip(pieces).associate { (segment, piece) ->
                (segment.text to segment.voiceAssetId) to piece
            }
            return Prepared(plan, bindings, audioFor)
        }

        /** 电脑 ID → 手机 ID：上传时已有的人物 ID 不变；电脑新建的人物交给手机登记。 */
        private fun phoneIds(workKey: String, result: BookJobChapterResult, registry: CharacterRegistry): Map<Long, Long> {
            val ids = mutableMapOf(0L to 0L)
            result.characters.filter { it.phoneKnown }.forEach { ids[it.characterId] = it.characterId }
            val created = result.characters.filterNot { it.phoneKnown }
            if (created.isEmpty()) return ids
            val candidates = created.map {
                NewCharacter(temporaryId = "pc${it.characterId}", displayName = it.displayName, gender = it.gender)
            }
            val aliases = created.map { AliasUpdate("pc${it.characterId}", it.stableAliases) }
            val resolved = try {
                registry.resolve(workKey, candidates, aliases)
            } catch (error: NovelAudioCharacterRegistry.AliasConflict) {
                // 别名与手机已有人物冲突时只按名字登记，音频本身不受影响。
                registry.resolve(workKey, candidates)
            }
            created.forEach { character ->
                ids[character.characterId] = resolved.temporaryToCharacterId["pc${character.characterId}"]
                    ?: throw ImportFailure("UNKNOWN_SPEAKER")
            }
            return ids
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
