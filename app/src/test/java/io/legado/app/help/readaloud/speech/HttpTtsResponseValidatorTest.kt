package io.legado.app.help.readaloud.speech

import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class HttpTtsResponseValidatorTest {
    private class TrackedBody(private val payload: ByteArray) : ResponseBody() {
        var closed = false
        var reads = 0
        private val data = object : ForwardingSource(Buffer().write(payload)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reads++
                return super.read(sink, byteCount)
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = payload.size.toLong()
        override fun source(): BufferedSource = data
    }

    private fun response(status: Int, type: String?, body: ResponseBody): Response =
        Response.Builder().request(Request.Builder().url("https://example.invalid/tts").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body).apply { type?.let { header("Content-Type", it) } }.build()

    private fun rejected(status: Int, type: String?, expected: String?, code: String) {
        val canary = "private-credential-and-chapter-canary"
        val body = TrackedBody(canary.toByteArray())
        val error = assertThrows(HttpTtsResponseException::class.java) {
            HttpTtsResponseValidator.validate(response(status, type, body), expected)
        }
        assertEquals(code, error.errorCode)
        assertEquals(status, error.httpStatus)
        assertFalse(error.message.orEmpty().contains(canary))
        assertEquals("错误响应正文不得读取或进入异常", 0, body.reads)
        assertTrue("失败响应必须关闭", body.closed)
    }

    @Test fun `non success binary response fails before playback`() =
        rejected(503, "audio/ogg", null, "HTTP_TTS_HTTP_STATUS")

    @Test fun `http failure takes precedence over json error payload`() =
        rejected(404, "application/json", null, "HTTP_TTS_HTTP_STATUS")

    @Test fun `successful json response is not synthesized audio`() =
        rejected(200, "application/json; charset=utf-8", null, "HTTP_TTS_NON_AUDIO")

    @Test fun `problem json response is not synthesized audio`() =
        rejected(200, "application/problem+json", null, "HTTP_TTS_NON_AUDIO")

    @Test fun `case insensitive html response is rejected`() =
        rejected(200, "Text/HTML; charset=UTF-8", null, "HTTP_TTS_NON_AUDIO")

    @Test fun `xml response is not synthesized audio`() =
        rejected(200, "application/xml", null, "HTTP_TTS_NON_AUDIO")

    @Test fun `provider content type mismatch is fixed and sanitized`() =
        rejected(200, "application/octet-stream", "audio/.*", "HTTP_TTS_CONTENT_TYPE")

    @Test fun `invalid provider content type pattern has an actionable fixed code`() =
        rejected(200, "audio/ogg", "[", "HTTP_TTS_CONTENT_TYPE_CONFIG")

    @Test fun `audio response stays unread and open for the decoder`() {
        val bytes = byteArrayOf(79, 103, 103, 83, 0, 1)
        val body = TrackedBody(bytes)
        val r = response(200, "audio/ogg; codecs=opus", body)
        HttpTtsResponseValidator.validate(r, "audio/.*")
        assertFalse(body.closed)
        assertEquals(0, body.reads)
        assertArrayEquals(bytes, r.body.bytes())
    }

    @Test fun `legacy binary response without content type remains supported`() {
        val body = TrackedBody(byteArrayOf(1, 2, 3))
        response(200, null, body).use { HttpTtsResponseValidator.validate(it, "audio/.*") }
    }

    @Test fun `explicitly accepted octet stream remains supported`() {
        val body = TrackedBody(byteArrayOf(1, 2, 3))
        response(200, "application/octet-stream", body).use {
            HttpTtsResponseValidator.validate(it, "audio/.*|application/octet-stream")
        }
    }
}
