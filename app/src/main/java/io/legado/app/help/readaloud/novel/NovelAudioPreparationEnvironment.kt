package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.readaloud.offline.NovelAudioArtifactStore
import io.legado.app.help.readaloud.offline.NovelAudioDownloadCoordinator
import io.legado.app.help.readaloud.offline.canDecodeNovelAudio
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerClient
import io.legado.app.help.readaloud.server.NovelAudioServerException
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 当前章与后续章共用的准备环境。
 *
 * 抽出来的唯一目的是让 AUTO 后续章复用同一套凭据、预算账本、分析器和下载器：
 * 原先这些都内联在当前章的准备作业里，AUTO 无法触及，只能复制一份，
 * 而复制会让两条路径的预算口径和 artifact 校验各自漂移。
 *
 * 预算账本固定放在 noBackupFilesDir，与凭据文件分离：
 * 清除或更换 AI 服务凭据不得重置累计额度或云额度熔断。
 */
internal class NovelAudioPreparationEnvironment private constructor(
    val serverScope: String,
    private val characterStore: NovelAudioRoomCharacterStore,
    private val analysis: NovelAudioAnalysisCoordinator,
    private val downloadCoordinator: NovelAudioDownloadCoordinator,
    private val client: NovelAudioServerClient
) {

    /**
     * 为当前章、AUTO 或 PINNED 打开一个批次运行时。
     *
     * 不支持 Runtime Lease 的旧云服务返回一个无 Lease 的兼容批次；支持 Lease
     * 的本地服务则把分析与合成入口绑定到同一个 opaque leaseId。
     */
    suspend fun acquireBatch(
        purpose: String,
        expectedChapterCount: Int
    ): NovelAudioPreparationBatch {
        require(purpose.isNotBlank())
        require(expectedChapterCount in 1..10_000)
        val lease = try {
            client.acquireRuntime(
                sessionId = NovelAudioIdentity.storageKey(
                    "runtime",
                    UUID.randomUUID().toString()
                ),
                purpose = purpose,
                expectedChapterCount = expectedChapterCount
            )
        } catch (error: NovelAudioServerException) {
            if (error.kind == "UNSUPPORTED") {
                return NovelAudioPreparationBatch(this, null, client)
            }
            throw error
        }
        return NovelAudioPreparationBatch(
            environment = withLease(lease.leaseId),
            leaseId = lease.leaseId,
            client = client
        )
    }

    private fun withLease(leaseId: String): NovelAudioPreparationEnvironment {
        return NovelAudioPreparationEnvironment(
            serverScope = serverScope,
            characterStore = characterStore,
            analysis = analysis.withLease(leaseId),
            downloadCoordinator = downloadCoordinator.withLease(leaseId),
            client = client
        )
    }

    /**
     * 为一个章节快照装配单章准备顺序。
     *
     * [persist] 由调用方提供，因为当前章要走 run token fencing，
     * 而后续章走自己的车道；两者的 ownership 判定不同，不能共用一份。
     */
    fun preparer(
        persist: (NovelAudioChapterPlan, String) -> NovelAudioRepository.Execution?
    ): NovelAudioChapterPreparer {
        var execution: NovelAudioRepository.Execution? = null
        val producer = NovelAudioPlanProducer(
            analyze = { snapshot, scope, generation, characters, units, bindings ->
                analysis.analyze(
                    snapshot = snapshot,
                    scope = scope,
                    generation = generation,
                    existingCharacters = characters,
                    parsedUnits = units,
                    existingBindings = bindings
                )
            },
            persist = { plan, retention ->
                execution = persist(plan, retention)
                execution != null
            }
        )
        return NovelAudioChapterPreparer(
            produce = { snapshot, generation, retention, isPlanCurrent ->
                val existingCharacters = characterStore.characters(snapshot.workKey)
                val existingBindings = characterStore
                    .voiceBindings(existingCharacters.map { it.id }.toSet())
                    .map { it.toDomain() }
                producer.produce(
                    snapshot = snapshot,
                    scope = serverScope,
                    generation = generation,
                    existingCharacters = existingCharacters,
                    existingBindings = existingBindings,
                    retention = retention,
                    isCurrent = isPlanCurrent
                )
            },
            execution = { execution },
            download = { token, isAutoAllowed ->
                downloadCoordinator.downloadPlan(
                    execution = token,
                    isAutoAllowed = isAutoAllowed
                )
            }
        )
    }

    companion object {
        private const val AUDIO_DIRECTORY = "novel-audio"

        /** 未配置服务器凭据时返回 null；调用方据此报 MISSING_CREDENTIALS。 */
        fun open(): NovelAudioPreparationEnvironment? {
            val credentials = NovelAudioAndroidConfigStore.open(appCtx).load() ?: return null
            val budgetLedger = NovelAudioBudgetLedger(
                File(appCtx.noBackupFilesDir, "novel-audio-budget.json")
            )
            val client = credentials.newClient(budgetLedger)
            val characterStore = NovelAudioRoomCharacterStore(appDb)
            val artifactRoot = File(appCtx.filesDir, AUDIO_DIRECTORY)
            val decoder: (File) -> Boolean = ::canDecodeNovelAudio
            return NovelAudioPreparationEnvironment(
                serverScope = NovelAudioIdentity.serverScope(credentials.baseUrl),
                characterStore = characterStore,
                analysis = NovelAudioAnalysisCoordinator(
                    analyze = client::analyze,
                    registry = CharacterRegistry(characterStore),
                    voices = client::voices,
                    match = client::match,
                    analyzeWithLease = client::analyze
                ),
                downloadCoordinator = NovelAudioDownloadCoordinator(
                    repository = NovelAudioRepository(appDb),
                    artifactStore = NovelAudioArtifactStore(
                        rootDirectory = artifactRoot,
                        decoder = decoder
                    ),
                    filesRoot = artifactRoot,
                    synthesize = client::synthesize,
                    synthesizeWithLease = client::synthesize,
                    decoder = decoder
                ),
                client = client
            )
        }
    }
}

/**
 * 一次性批次 Lease。即使生成请求取消、换书或服务端 Lease 已过期，也只尝试释放一次，
 * 并且释放异常不会覆盖原始的章节准备结果。
 */
internal class NovelAudioPreparationBatch internal constructor(
    val environment: NovelAudioPreparationEnvironment,
    val leaseId: String?,
    private val client: NovelAudioServerClient
) {
    private val closed = AtomicBoolean(false)

    suspend fun close() {
        if (leaseId.isNullOrBlank() || !closed.compareAndSet(false, true)) return
        kotlin.runCatching {
            client.releaseRuntime(leaseId)
        }.onFailure {
            AppLog.put("AI 听书运行时释放失败", it)
        }
    }
}
