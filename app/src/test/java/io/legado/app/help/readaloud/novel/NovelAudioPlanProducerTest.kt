package io.legado.app.help.readaloud.novel

import io.legado.app.help.readaloud.role.ReadAloudPreprocessRuleConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAudioPlanProducerTest {

    @Test
    fun `producer persists a plan only while the chapter generation is current`() = runBlocking {
        val snapshot = NovelAudioChapterSnapshotFactory.fromStrings(
            bookUrl = "book://one",
            chapterIndex = 0,
            chapterUrl = "chapter",
            strings = listOf("旁白"),
            rules = ReadAloudPreprocessRuleConfig().freeze()
        )
        var persistedRetention: String? = null
        val producer = NovelAudioPlanProducer(
            analyze = { source, scope, generation, _, parsedUnits, _ ->
                NovelAudioPlanFactory.create(
                    snapshot = source,
                    serverScope = scope,
                    generation = generation,
                    parsedUnits = parsedUnits
                )
            },
            persist = { plan, retention ->
                persistedRetention = retention
                assertEquals(11L, plan.generation)
                true
            }
        )

        val result = producer.produce(
            snapshot = snapshot,
            scope = "scope",
            generation = 11L,
            retention = "PINNED",
            isCurrent = { true }
        )

        assertEquals(11L, result?.generation)
        assertEquals("PINNED", persistedRetention)

        val stale = producer.produce(
            snapshot = snapshot,
            scope = "scope",
            generation = 12L,
            isCurrent = { false }
        )
        assertNull(stale)
    }
}
