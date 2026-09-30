package io.legado.app.help.readaloud.novel

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.NetworkUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

/**
 * 启动时恢复用户此前明确要求的固定下载。
 *
 * 只恢复 PINNED：这是用户显式下达的下载指令，与 AUTO 的播放授权副产物不同，
 * 不应等到再次听书才继续。因此「打开应用即可能继续下载」是有意行为。
 *
 * 离线时不启动：恢复必然需要合成缺失分段，离线启动只会白跑一轮并留下失败记录。
 * 进程内只允许恢复一次，避免多处触发把同一批任务排成双份而撞并发上限。
 */
object NovelAudioPinnedRecoveryStarter {

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Coroutine.async(executeContext = Dispatchers.IO) {
            if (!NetworkUtils.isAvailable()) return@async
            NovelAudioPinnedRecovery.create { bookUrl, chapterIndex ->
                val book = appDb.bookDao.getBook(bookUrl) ?: return@create false
                NovelAudioPinnedChapterPreparer.prepare(
                    book = book,
                    chapterIndex = chapterIndex,
                    retention = io.legado.app.data.entities.NovelAudioRetention.PINNED
                )
            }.recover()
        }.onError {
            AppLog.put("AI 听书固定下载恢复失败", it)
        }
    }
}
