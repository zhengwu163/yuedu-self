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

    suspend fun prepare(book: Book, chapterIndex: Int, retention: String): Boolean {
        if (book.bookUrl.isBlank() || chapterIndex < 0) return false
        val environment = NovelAudioPreparationEnvironment.open() ?: return false
        val snapshot = snapshotLoader.load(book, chapterIndex) ?: return false
        val result = environment.preparer { plan, planRetention ->
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
}
