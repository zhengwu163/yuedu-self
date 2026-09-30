package io.legado.app.help.readaloud.server

import io.legado.app.help.readaloud.novel.NovelAudioBudgetLedger
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URL 与令牌只能作为同一配置快照使用。 */
internal class NovelAudioServerCredentials private constructor(val baseUrl: String, val token: String) {
    fun newClient(): NovelAudioServerClient = NovelAudioServerClient(
        baseUrl = baseUrl,
        tokenProvider = { token },
        timeoutLimitMillis = 45_000,
        budgetLedger = null
    )

    fun newClient(budgetLedger: NovelAudioBudgetLedger): NovelAudioServerClient =
        NovelAudioServerClient(
            baseUrl = baseUrl,
            tokenProvider = { token },
            timeoutLimitMillis = 45_000,
            budgetLedger = budgetLedger
        )

    // 禁止 data class 自动生成包含地址/令牌的 toString。
    override fun toString(): String = "NovelAudioServerCredentials(<redacted>)"

    companion object {
        fun create(baseUrl: String, token: String, allowInsecureHttp: Boolean = false): NovelAudioServerCredentials {
            val parsed = parseBaseUrl(baseUrl)
            validateToken(token)
            if (parsed.scheme == "http" && !allowInsecureHttp) throw NovelAudioServerException("CONFIG")
            return NovelAudioServerCredentials(parsed.toString(), token)
        }

        internal fun parseBaseUrl(value: String): HttpUrl {
            if (value.length > 4096) throw NovelAudioServerException("CONFIG")
            return value.toHttpUrlOrNull()?.takeIf {
                it.toString().length <= 4096 && it.username.isEmpty() && it.password.isEmpty() &&
                    it.query == null && it.fragment == null &&
                    it.pathSegments.lastOrNull { part -> part.isNotEmpty() } != "v1"
            } ?: throw NovelAudioServerException("CONFIG")
        }

        internal fun validateToken(value: String) {
            if (value.length !in 1..4096 || value.any { it.code !in 33..126 }) {
                throw NovelAudioServerException("CONFIG")
            }
        }
    }
}
