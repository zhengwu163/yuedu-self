package io.legado.app.help.readaloud.novel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 整本生成入口与启动/播放接线的结构性回归。
 *
 * 书架长按进入书籍详情，所以「整本生成」只需挂在三套书籍详情页
 * （BookInfoActivity、BookInfoModernActivity、BookInfoComposeActivity + BookInfoComposeRoute）；
 * 每处都必须走共享入口 NovelAudioBookJobEntry，且只对书架上的书显示。
 */
class NovelAudioBookJobWiringTest {

    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }

    private fun source(path: String) = File(root, "io/legado/app/$path").readText()

    @Test
    fun `all three book detail pages open the shared entry for shelf books`() {
        val classic = source("ui/book/info/BookInfoActivity.kt")
        val modern = source("ui/book/info/BookInfoModernActivity.kt")
        val compose = source("ui/book/info/BookInfoComposeActivity.kt")
        val route = source("ui/book/info/compose/BookInfoComposeRoute.kt")
        for (page in listOf(classic, modern, compose)) {
            assertEquals(1, Regex("NovelAudioBookJobEntry\\.open\\(").findAll(page).count())
        }
        for (page in listOf(classic, modern)) {
            val entry = page.indexOf("NovelAudioBookJobEntry.open(")
            val guard = page.lastIndexOf("if (viewModel.inBookshelf)", entry)
            assertTrue("入口必须只对书架上的书显示", guard in 0 until entry)
        }
        assertTrue(route.contains("actions.onBookJob?.takeIf { state.inBookshelf }"))
        assertTrue(route.contains("stringResource(R.string.novel_audio_book_job_title)"))
    }

    @Test
    fun `app start resumes uploads and sync once off the main thread`() {
        val app = source("App.kt")
        assertEquals(1, Regex("NovelAudioBookJobSync\\.onAppStart\\(").findAll(app).count())
        val sync = source("help/readaloud/novel/NovelAudioBookJobSync.kt")
        val start = sync.indexOf("fun onAppStart(")
        assertTrue(sync.indexOf("Coroutine.async(executeContext = Dispatchers.IO)", start) > start)
    }

    @Test
    fun `book job chapters are imported before any per-chapter preparation`() {
        val coordinator = source("help/readaloud/novel/NovelAudioPreparationCoordinator.kt")
        val bookJob = coordinator.indexOf("NovelAudioBookJobSync.playbackSource(")
        val environment = coordinator.indexOf("NovelAudioPreparationEnvironment.open()")
        val localGate = coordinator.indexOf("localFirstGate.decide(")
        assertTrue(localGate in 0 until bookJob)
        assertTrue(bookJob in 0 until environment)
    }

    @Test
    fun `services are declared with the right types`() {
        val manifest = sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        val upload = manifest.substringAfter(".service.NovelAudioBookJobService\"").substringBefore("/>")
        assertTrue(upload.contains("android:foregroundServiceType=\"dataSync\""))
        assertTrue(upload.contains("android:exported=\"false\""))
        val sync = manifest.substringAfter(".service.NovelAudioBookJobSyncService\"").substringBefore("/>")
        assertTrue(sync.contains("android.permission.BIND_JOB_SERVICE"))
    }
}
