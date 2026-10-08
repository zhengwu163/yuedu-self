package io.legado.app.help.readaloud.novel

import io.legado.app.data.entities.Book

/**
 * PINNED 单章准备的生产入口。
 *
 * 复用共享准备环境与只读快照 loader，因此手动下载与当前章、AUTO 后续章
 * 共用同一套凭据、预算账本与 artifact 校验，不会出现第二套预算口径。
 *
 * 与 AUTO 的差别只有两点：retention 固定为 PINNED，且不参与车道仲裁——
 * 手动下载不依赖播放授权，暂停听书不应中断它。
 */
object NovelAudioPinnedChapterPreparer {

    private val snapshotLoader = NovelAudioChapterSnapshotLoader.create()

    internal suspend fun openBatch(
        book: Book,
        expectedChapterCount: Int
    ): NovelAudioChapterBatch? {
        if (book.bookUrl.isBlank() || expectedChapterCount !in 1..10_000) return null
        val environment = NovelAudioPreparationEnvironment.open() ?: return null
        return NovelAudioChapterBatchImpl(
            book = book,
            runtimeBatch = environment.acquireBatch(
                purpose = "pinned",
                expectedChapterCount = expectedChapterCount
            )
        )
    }

    suspend fun prepare(book: Book, chapterIndex: Int, retention: String): Boolean {
        if (book.bookUrl.isBlank() || chapterIndex < 0) return false
        val batch = openBatch(book, expectedChapterCount = 1) ?: return false
        return try {
            batch.prepare(chapterIndex, retention)
        } finally {
            batch.close()
        }
    }

    private class NovelAudioChapterBatchImpl(
        private val book: Book,
        private val runtimeBatch: NovelAudioPreparationBatch
    ) : NovelAudioChapterBatch {

        override suspend fun prepare(chapterIndex: Int, retention: String): Boolean {
            if (chapterIndex < 0) return false
            val snapshot = snapshotLoader.load(book, chapterIndex) ?: return false
            val result = runtimeBatch.environment.preparer { plan, planRetention ->
                NovelAudioRepository(io.legado.app.data.appDb)
                    .savePlanForExecution(plan, planRetention)
            }.prepare(
                snapshot = snapshot,
                // 手动下载没有阅读页代次，与后续章一致由快照散列派生稳定代次。
                generation = snapshot.snapshotHash.hashCode().toLong() and 0x3FFF_FFFFL,
                retention = retention,
                isCurrent = { true }
            )
            return result is NovelAudioChapterPreparer.Result.Ready
        }

        override suspend fun close() {
            runtimeBatch.close()
        }
    }
}

internal interface NovelAudioChapterBatch {
    suspend fun prepare(chapterIndex: Int, retention: String): Boolean

    suspend fun close()
}
