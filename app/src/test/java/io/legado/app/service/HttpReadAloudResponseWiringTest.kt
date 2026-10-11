package io.legado.app.service

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 两种真实合成入口均须执行已测试的响应闸门，不能绕过或静音吞掉拒绝。 */
class HttpReadAloudResponseWiringTest {
    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }
    private val source = File(root, "io/legado/app/service/HttpReadAloudService.kt").readText()

    @Test fun `script and url template synthesis both validate responses`() {
        assertEquals(2, Regex("HttpTtsResponseValidator\\.validate\\(").findAll(source).count())
    }

    @Test fun `provider error response body never enters an exception`() {
        assertFalse(source.contains("response.body.string()"))
    }

    @Test fun `fallback and playback errors never log the chapter text`() {
        assertFalse(source.contains("朗读文本："))
        assertFalse(source.contains("AppLog.put(\"朗读错误\\n\${contentList[nowSpeak]}\""))
    }

    @Test fun `rejected synthesis response escapes before silent fallback handling`() {
        val handler = source.indexOf("is HttpTtsResponseException -> throw e")
        val fallback = source.indexOf("TTS下载音频出错")
        assertTrue(handler >= 0 && handler < fallback)
    }

    @Test fun `both download callbacks pause and show the sanitized provider rejection`() {
        assertEquals(2, Regex("reportDownloadError\\(it\\)").findAll(source).count())
        val report = source.substringAfter("private fun reportDownloadError(")
            .substringBefore("private suspend fun")
        assertTrue(report.contains("error is HttpTtsResponseException"))
        assertTrue(report.contains("pauseReadAloud()"))
        assertTrue(report.contains("toastOnUi(error.localizedMessage"))
    }

    @Test fun `queued playback does not swallow a validated provider rejection`() {
        val queue = source.substringAfter("private suspend fun CoroutineScope.playDownloadQueue(")
            .substringBefore("private suspend fun preDownloadAudios(")
        val caught = queue.substringAfter("catch (e: Throwable)")
        assertTrue("队列必须将固定拒绝传给主线程提示，而不能静默返回",
            caught.contains("if (e is HttpTtsResponseException) throw e"))
    }
}
