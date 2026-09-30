package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioChapterPlanEntity
import io.legado.app.data.entities.NovelAudioDownloadTaskEntity
import io.legado.app.data.entities.NovelAudioStates
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAudioPersistenceStateTest {

    @Test
    fun stateReasonsDistinguishAutoExpiryFromNetworkAndUserCancellation() {
        assertEquals("PARTIAL", NovelAudioStates.PARTIAL)
        assertEquals("WAITING_NETWORK", NovelAudioStates.WAITING_NETWORK)
        assertEquals("PAUSED", NovelAudioStates.PAUSED)
        assertEquals("CANCELLED", NovelAudioStates.CANCELLED)
        assertEquals("EXPIRED", NovelAudioStates.EXPIRED)
    }

    @Test
    fun taskDefaultsKeepReasonEmptyForExistingRows() {
        assertEquals("", NovelAudioDownloadTaskEntity().stateReason)
        assertEquals("", NovelAudioChapterPlanEntity().stateReason)
    }
}
