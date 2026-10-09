package io.legado.app.help.readaloud.offline

import io.legado.app.help.readaloud.server.NovelAudioServerException
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAudioDownloadCoordinatorTest {

    @Test
    fun `server failures keep their kind in the download failure reason`() {
        // 真机联调只能看到 reason；仅类名无法区分超时、限流与本地额度。
        assertEquals(
            "NovelAudioServerException:TIMEOUT",
            downloadFailureReason(NovelAudioServerException("TIMEOUT"))
        )
        assertEquals(
            "NovelAudioServerException:CONCURRENCY_LIMIT",
            downloadFailureReason(NovelAudioServerException("CONCURRENCY_LIMIT"))
        )
    }

    @Test
    fun `other failures still report only the class name`() {
        assertEquals("IllegalStateException", downloadFailureReason(IllegalStateException("secret")))
    }
}
