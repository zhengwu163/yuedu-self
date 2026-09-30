package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.help.readaloud.analysis.ParsedTextUnit

/**
 * Connects a final local chapter snapshot to a persisted frozen plan.
 *
 * The caller owns the chapter-load generation check. It is evaluated both
 * before analysis and immediately before persistence so a canceled or replaced
 * chapter load cannot commit a late plan.
 */
class NovelAudioPlanProducer(
    private val analyze: suspend (
        snapshot: NovelAudioChapterSnapshot,
        scope: String,
        generation: Long,
        existingCharacters: List<BookCharacter>,
        parsedUnits: List<ParsedTextUnit>,
        existingBindings: List<NovelAudioVoiceBinding>
    ) -> NovelAudioChapterPlan,
    private val persist: (plan: NovelAudioChapterPlan, retention: String) -> Boolean
) {

    suspend fun produce(
        snapshot: NovelAudioChapterSnapshot,
        scope: String,
        generation: Long,
        isCurrent: () -> Boolean,
        existingCharacters: List<BookCharacter> = emptyList(),
        parsedUnits: List<ParsedTextUnit> = snapshot.parseUnits(),
        existingBindings: List<NovelAudioVoiceBinding> = emptyList(),
        retention: String = NovelAudioRetention.AUTO
    ): NovelAudioChapterPlan? {
        require(scope.isNotBlank())
        require(generation >= 0L)
        require(
            retention == NovelAudioRetention.AUTO ||
                retention == NovelAudioRetention.PINNED
        )
        if (!isCurrent()) return null

        val plan = analyze(
            snapshot,
            scope,
            generation,
            existingCharacters,
            parsedUnits,
            existingBindings
        )
        check(plan.generation == generation) {
            "analysis returned a plan for a stale chapter generation"
        }
        if (!isCurrent()) return null
        return plan.takeIf { persist(it, retention) }
    }
}
