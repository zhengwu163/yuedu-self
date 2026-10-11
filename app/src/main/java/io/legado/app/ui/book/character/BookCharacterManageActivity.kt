package io.legado.app.ui.book.character

import android.app.Activity
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.readaloud.novel.NovelAudioCharacterScope
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.help.readaloud.novel.NovelAudioIdentity
import io.legado.app.help.readaloud.novel.CharacterRegistry
import io.legado.app.help.readaloud.novel.NovelAudioRoomCharacterStore
import io.legado.app.help.readaloud.novel.NovelAudioPinnedChapterPreparer
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.VoiceAsset
import io.legado.app.data.entities.NovelAudioRetention
import io.legado.app.model.ReadBook
import io.legado.app.model.ReadAloud
import io.legado.app.ui.book.character.compose.CharacterManageScreen
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class BookCharacterManageActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    private var bookUrl: String = ""
    private var characterBookKey by mutableStateOf("")
    private var book: Book? = null
    private var characters by mutableStateOf<List<BookCharacter>>(emptyList())

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(EXTRA_BOOK_URL)
            ?: ReadBook.book?.bookUrl
            ?: ""
        book = appDb.bookDao.getBook(bookUrl)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CharacterManageScreen(
                bookName = book?.name.orEmpty(),
                characters = characters,
                onBack = ::finish,
                onAdd = { openEdit() },
                onOpenCard = { openCard(it.id) },
                onEdit = { openEdit(it.id) },
                onDelete = ::delete,
                onOpenRelations = ::openRelations,
                onFixedVoices = if (characterBookKey == book?.let(NovelAudioIdentity::workKey)) {
                    { fixedVoices() }
                } else null
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (bookUrl.isBlank()) {
            characters = emptyList()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                BookCharacterIdentityMigrator.migrate(book)
                val key = NovelAudioCharacterScope.forManagement(
                    book, intent.getStringExtra(EXTRA_CHARACTER_BOOK_KEY)
                )
                key to appDb.bookCharacterDao.characters(key)
            }
            characterBookKey = data.first
            characters = data.second
        }
    }

    private fun openCard(id: Long) {
        if (bookUrl.isBlank() || id <= 0L) {
            toastOnUi("当前角色不存在")
            return
        }
        startActivity<BookCharacterCardActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
            putExtra(EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
            putExtra(EXTRA_CHARACTER_ID, id)
        }
    }

    private fun fixedVoices() {
        showComposeActionListDialog(
            title = "固定音色",
            labels = listOf("旁白") + characters.map { it.displayName() } + "按新音色重新生成当前章",
            message = "每个角色选定后持续复用。已下载音频保持原音色，重新生成后才会改变。"
        ) { index ->
            if (index == characters.size + 1) {
                val current = book ?: return@showComposeActionListDialog
                if (ReadBook.book?.bookUrl != bookUrl) {
                    toastOnUi("请先打开这本书的阅读页")
                    return@showComposeActionListDialog
                }
                val chapter = ReadBook.durChapterIndex
                // Drop the prepared old player before publishing the replacement frozen plan.
                ReadAloud.stop(this)
                lifecycleScope.launch {
                    toastOnUi("正在按固定音色准备当前章")
                    val ready = withContext(IO) {
                        try { NovelAudioPinnedChapterPreparer.prepare(current, chapter, NovelAudioRetention.PINNED) }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { false }
                    }
                    toastOnUi(if (ready) "当前章准备完成" else "准备失败，请检查电脑服务")
                }
            } else selectFixedVoice(if (index == 0) 0L else characters[index - 1].id)
        }
    }

    private fun selectFixedVoice(characterId: Long) {
        lifecycleScope.launch {
            val credentials = NovelAudioAndroidConfigStore.open(applicationContext).load()
            if (credentials == null) { toastOnUi("请先配置 AI 听书服务"); return@launch }
            val palette = withContext(IO) {
                try { credentials.newClient().voices() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null }
            }
            if (palette.isNullOrEmpty()) { toastOnUi("未能读取电脑上的预置音色"); return@launch }
            val scope = NovelAudioIdentity.serverScope(credentials.baseUrl)
            val selected = withContext(IO) {
                appDb.novelAudioDao.voiceBinding(scope, characterId)?.voiceAssetId
            }
            showComposeSingleChoiceDialog(
                title = if (characterId == 0L) "旁白固定音色" else "角色固定音色",
                labels = palette.map(VoiceAsset::displayName),
                selectedIndex = palette.indexOfFirst { it.voiceAssetId == selected },
                message = "选定后不会再被自动匹配替换；原已下载音频不变。"
            ) { index ->
                palette.getOrNull(index)?.let { voice -> lifecycleScope.launch {
                    withContext(IO) {
                        CharacterRegistry(NovelAudioRoomCharacterStore(appDb))
                            .setVoiceBinding(scope, characterId, voice.voiceAssetId)
                    }
                    ReadAloudConfigChangeNotifier.notifySpeech()
                    toastOnUi("固定音色已保存")
                } }
            }
        }
    }

    private fun openEdit(id: Long = 0L) {
        if (bookUrl.isBlank()) {
            toastOnUi("当前书籍不存在")
            return
        }
        startActivity<BookCharacterEditActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
            putExtra(EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
            if (id > 0) putExtra(EXTRA_CHARACTER_ID, id)
        }
    }

    private fun openRelations() {
        if (characters.size < 2) {
            toastOnUi("至少需要两个角色才能编辑关系网")
            return
        }
        startActivity<BookCharacterRelationActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
            putExtra(EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
        }
    }

    private fun delete(character: BookCharacter) {
        showComposeConfirmDialog(
            title = "删除角色",
            message = "确定删除「${character.displayName()}」？相关关系也会删除。",
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookCharacterDao.deleteCharacterWithRelations(character)
                    }
                    ReadAloudConfigChangeNotifier.notifySpeech()
                    load()
                    setResult(Activity.RESULT_OK)
                }
            }
        )
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_CHARACTER_BOOK_KEY = "characterBookKey"
        const val EXTRA_CHARACTER_ID = "characterId"
    }
}
