package io.legado.app.help.readaloud.speech

import io.legado.app.exception.NoStackTraceException
import okhttp3.Response
import java.util.Locale
import java.util.regex.PatternSyntaxException

/** 固定错误码可写日志；供应商正文可能回显凭据或章节，绝不读取、附加到异常。 */
class HttpTtsResponseException(val errorCode: String, val httpStatus: Int) :
    NoStackTraceException("HTTP TTS 请求失败（$errorCode，HTTP $httpStatus），请检查服务或登录状态")

object HttpTtsResponseValidator {
    /** 只检查响应头，不消费音频流；保留旧服务缺少 Content-Type 的二进制响应兼容性。 */
    fun validate(response: Response, expectedContentType: String? = null) {
        if (!response.isSuccessful) reject(response, "HTTP_TTS_HTTP_STATUS")
        val type = response.header("Content-Type")?.substringBefore(';')?.trim()
            ?.lowercase(Locale.ROOT) ?: return
        if (type.startsWith("text/") || type == "application/json" || type.endsWith("+json")
            || type == "application/xml" || type.endsWith("+xml")) {
            reject(response, "HTTP_TTS_NON_AUDIO")
        }
        if (!expectedContentType.isNullOrBlank()) {
            val matches = try {
                type.matches(expectedContentType.toRegex())
            } catch (_: PatternSyntaxException) {
                reject(response, "HTTP_TTS_CONTENT_TYPE_CONFIG")
            }
            if (!matches) reject(response, "HTTP_TTS_CONTENT_TYPE")
        }
    }

    private fun reject(response: Response, code: String): Nothing {
        // 失败体不交给播放器；关闭网络资源，且关闭异常同样不能泄露供应商信息。
        kotlin.runCatching { response.close() }
        throw HttpTtsResponseException(code, response.code)
    }
}
