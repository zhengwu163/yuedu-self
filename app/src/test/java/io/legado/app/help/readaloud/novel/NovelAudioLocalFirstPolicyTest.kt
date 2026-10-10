package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.data.entities.NovelAudioStates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地优先与离线零网络的判定边界。
 *
 * 判定只看本地 Room 状态与网络可用性，且不得因为「离线」就退化成静音或空音频：
 * 缺段离线时必须明确进入等待网络，而不是交给播放器一个不完整的清单。
 */
class NovelAudioLocalFirstPolicyTest {

    @Test
    fun `pinned snapshot remains reusable after the reader generation resets`() {
        val decision = NovelAudioLocalFirstPolicy.decide(
            NovelAudioStates.READY, 98765L, 1L, true, false,
            planSnapshotHash = "snapshot-a", expectedSnapshotHash = "snapshot-a"
        )
        assertEquals(NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL, decision)
    }

    @Test
    fun `changed content is rejected even when the transient generation matches`() {
        val decision = NovelAudioLocalFirstPolicy.decide(
            NovelAudioStates.READY, 1L, 1L, true, false,
            planSnapshotHash = "snapshot-a", expectedSnapshotHash = "snapshot-b"
        )
        assertEquals(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK, decision)
    }

    @Test
    fun `known snapshot identity never falls back to an unverified generation`() {
        for (hash in listOf(null, "")) {
            val decision = NovelAudioLocalFirstPolicy.decide(
                NovelAudioStates.READY, 1L, 1L, true, false,
                planSnapshotHash = hash, expectedSnapshotHash = "snapshot-a"
            )
            assertEquals(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK, decision)
        }
    }

    @Test
    fun `matching snapshot never permits incomplete audio`() {
        val decision = NovelAudioLocalFirstPolicy.decide(
            NovelAudioStates.READY, 98765L, 1L, false, false,
            planSnapshotHash = "snapshot-a", expectedSnapshotHash = "snapshot-a"
        )
        assertEquals(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK, decision)
    }

    @Test
    fun `complete local plan plays without any network use`() {
        val decision = NovelAudioLocalFirstPolicy.decide(
            planState = NovelAudioStates.READY,
            planGeneration = 7L,
            expectedGeneration = 7L,
            allArtifactsReady = true,
            online = false
        )

        assertEquals(NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL, decision)
        assertFalse(decision.requiresNetwork)
    }

    @Test
    fun `ready plan for another generation is not reused`() {
        val decision = NovelAudioLocalFirstPolicy.decide(
            planState = NovelAudioStates.READY,
            planGeneration = 6L,
            expectedGeneration = 7L,
            allArtifactsReady = true,
            online = true
        )

        assertEquals(NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE, decision)
    }

    @Test
    fun `ready plan with a missing artifact never plays locally`() {
        val online = NovelAudioLocalFirstPolicy.decide(
            planState = NovelAudioStates.READY,
            planGeneration = 7L,
            expectedGeneration = 7L,
            allArtifactsReady = false,
            online = true
        )
        val offline = NovelAudioLocalFirstPolicy.decide(
            planState = NovelAudioStates.READY,
            planGeneration = 7L,
            expectedGeneration = 7L,
            allArtifactsReady = false,
            online = false
        )

        assertEquals(NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE, online)
        assertEquals(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK, offline)
    }

    @Test
    fun `offline incomplete plan waits for network instead of calling out`() {
        listOf(
            NovelAudioStates.PLANNED,
            NovelAudioStates.QUEUED,
            NovelAudioStates.RUNNING,
            NovelAudioStates.PARTIAL,
            NovelAudioStates.FAILED
        ).forEach { state ->
            val decision = NovelAudioLocalFirstPolicy.decide(
                planState = state,
                planGeneration = 7L,
                expectedGeneration = 7L,
                allArtifactsReady = false,
                online = false
            )

            assertEquals(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK, decision)
            assertFalse(decision.requiresNetwork)
        }
    }

    @Test
    fun `absent plan offline waits and online prepares`() {
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            NovelAudioLocalFirstPolicy.decide(
                planState = null,
                planGeneration = null,
                expectedGeneration = 7L,
                allArtifactsReady = false,
                online = false
            )
        )
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            NovelAudioLocalFirstPolicy.decide(
                planState = null,
                planGeneration = null,
                expectedGeneration = 7L,
                allArtifactsReady = false,
                online = true
            )
        )
    }

    @Test
    fun `only prepare remote is allowed to touch the network`() {
        assertTrue(NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE.requiresNetwork)
        assertFalse(NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL.requiresNetwork)
        assertFalse(NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK.requiresNetwork)
    }

    @Test
    fun `pinned retention does not change the local first decision`() {
        NovelAudioRetention.let {
            listOf(it.AUTO, it.PINNED).forEach { _ ->
                assertEquals(
                    NovelAudioLocalFirstPolicy.Decision.PLAY_LOCAL,
                    NovelAudioLocalFirstPolicy.decide(
                        planState = NovelAudioStates.READY,
                        planGeneration = 3L,
                        expectedGeneration = 3L,
                        allArtifactsReady = true,
                        online = true
                    )
                )
            }
        }
    }

    @Test
    fun `unknown expected generation never reuses a local plan`() {
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.PREPARE_REMOTE,
            NovelAudioLocalFirstPolicy.decide(
                planState = NovelAudioStates.READY,
                planGeneration = 7L,
                expectedGeneration = null,
                allArtifactsReady = true,
                online = true
            )
        )
        assertEquals(
            NovelAudioLocalFirstPolicy.Decision.WAIT_FOR_NETWORK,
            NovelAudioLocalFirstPolicy.decide(
                planState = NovelAudioStates.READY,
                planGeneration = 7L,
                expectedGeneration = null,
                allArtifactsReady = true,
                online = false
            )
        )
    }
}
