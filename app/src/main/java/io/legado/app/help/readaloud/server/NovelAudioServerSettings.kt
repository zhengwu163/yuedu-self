package io.legado.app.help.readaloud.server

import kotlinx.coroutines.CancellationException

/** 仅留在弹框内存中，不进入 Bundle、saved state、Room 或日志。 */
internal class NovelAudioServerSettingsDraft private constructor(
    val baseUrl: String,
    val token: String,
    val allowInsecureHttp: Boolean
) {
    constructor() : this("", "", false)

    fun withUrl(value: String): NovelAudioServerSettingsDraft =
        if (value == baseUrl) this else NovelAudioServerSettingsDraft(value, "", false)

    fun withToken(value: String) = NovelAudioServerSettingsDraft(baseUrl, value, allowInsecureHttp)
    fun withHttpConsent(value: Boolean) = NovelAudioServerSettingsDraft(baseUrl, token, value)

    fun credentials(): NovelAudioServerCredentials =
        NovelAudioServerCredentials.create(baseUrl.trim(), token, allowInsecureHttp)

    override fun toString() = "NovelAudioServerSettingsDraft(<redacted>)"

    companion object {
        fun from(credentials: NovelAudioServerCredentials?) = credentials?.let {
            NovelAudioServerSettingsDraft(it.baseUrl, it.token, it.baseUrl.startsWith("http://"))
        } ?: NovelAudioServerSettingsDraft()
    }
}

/** IO 调用；健康检查只消费草稿快照，与持久配置和播放授权无关。 */
internal class NovelAudioServerSettings(
    private val store: NovelAudioServerConfigStore,
    private val probe: suspend (NovelAudioServerCredentials) -> ServerHealth = { it.newClient().health() }
) {
    fun load() = NovelAudioServerSettingsDraft.from(store.load())

    fun save(draft: NovelAudioServerSettingsDraft) {
        val pair = draft.credentials()
        store.replace(pair.baseUrl, pair.token, draft.allowInsecureHttp)
    }

    fun clear() = store.clear()

    suspend fun testConnection(draft: NovelAudioServerSettingsDraft) {
        // 项目协程封装会打印收到的异常；必须先移除底层异常的 URL/凭据/cause。
        kotlin.runCatching {
            val health = probe(draft.credentials())
            if (health.status != "ok" || health.apiVersion != "1" ||
                !health.directorReady || !health.ttsReady) {
                throw NovelAudioServerException("UNAVAILABLE")
            }
        }.getOrElse {
            if (it is CancellationException || it is Error) throw it
            throw NovelAudioServerException((it as? NovelAudioServerException)?.kind ?: "UNAVAILABLE")
        }
    }
}
