package io.legado.app

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.offline.AudioPrefetchPlayback
import io.legado.app.help.readaloud.server.AndroidNovelAudioCredentialBlob
import io.legado.app.help.readaloud.server.NovelAudioCredentialBlob
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerSettings
import io.legado.app.help.readaloud.server.ServerHealth
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.config.NovelAudioServerConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudConfigGroup
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.utils.eventObservable
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.model.MultipleFailureException

/** Uses the real Fragment, coroutine chain and Keystore, with isolated file/alias and no cloud calls. */
@RunWith(AndroidJUnit4::class)
class NovelAudioConfigDialogDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<NovelAudioDialogHostActivity>
    private lateinit var store: NovelAudioServerConfigStore
    private lateinit var file: File
    private lateinit var alias: String
    private var previousEngine: String? = null
    private var engineCaptured = false
    private val probes = AtomicInteger()
    private val notifications = AtomicInteger()
    private val failNextWrite = AtomicBoolean()
    @Volatile private var afterWrite: () -> Unit = {}
    private var unblockWrite: CountDownLatch? = null
    private var unblockProbe: CompletableDeferred<Unit>? = null
    private var probe: suspend () -> ServerHealth = {
        throw io.legado.app.help.readaloud.server.NovelAudioServerException("UNAVAILABLE")
    }
    private val observer = Observer<Bundle> {
        if (it.getString(EventBus.READ_ALOUD_CONFIG_SCOPE) == EventBus.READ_ALOUD_CONFIG_SCOPE_ENGINE) {
            notifications.incrementAndGet()
        }
    }
    private var observerRegistered = false
    private val viewJobs = CopyOnWriteArrayList<Job>()

    @Before
    fun setUp() {
        // Both APKs share the first-loaded j$.util.DesugarCollections. Keep the
        // FragmentManager API reachable in the test APK's independently shrunk L8 output.
        val fragmentRuntime = Collections.synchronizedMap(mutableMapOf("ready" to true))
        assertEquals(true, fragmentRuntime["ready"])
        // Instrumentation starts a fresh process; never replace a book owned by a live reader.
        assertNull(ReadBook.book)
        previousEngine = AppConfig.ttsEngine
        engineCaptured = true
        AppConfig.ttsEngine = SpeechVoiceCatalogRepository.playbackEngineGroups(emptyList())
            .first().options.first().toRoute().toJson()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        alias = "legado.novel_audio_server.ui-test.$id"
        file = File(context.noBackupFilesDir, "novel-audio-ui-test-$id")
        val disk = AndroidNovelAudioCredentialBlob(file)
        store = NovelAudioServerConfigStore(object : NovelAudioCredentialBlob {
            override fun read() = disk.read()
            override fun write(value: ByteArray) {
                if (failNextWrite.getAndSet(false)) throw IOException("fixture storage failure")
                disk.write(value)
                afterWrite()
            }
        }, NovelAudioAndroidConfigStore.cipher(alias))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            eventObservable<Bundle>(EventBus.READ_ALOUD_CONFIG_CHANGED).observeForever(observer)
            observerRegistered = true
        }
        NovelAudioDialogHostActivity.factory = object : FragmentFactory() {
            override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                if (className == IsolatedNovelAudioServerConfigDialog::class.java.name) newDialog()
                else super.instantiate(classLoader, className)
        }
        scenario = ActivityScenario.launch(NovelAudioDialogHostActivity::class.java)
    }

    @After
    fun tearDown() {
        val errors = mutableListOf<Throwable>()
        fun cleanup(block: () -> Unit) {
            kotlin.runCatching(block).exceptionOrNull()?.let(errors::add)
        }
        cleanup { unblockWrite?.countDown() }
        cleanup { unblockProbe?.complete(Unit) }
        cleanup { if (::scenario.isInitialized) scenario.close() }
        cleanup {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                NovelAudioDialogHostActivity.factory = null
                NovelAudioDialogHostActivity.inspectSavedState = {}
                // Closing a failed scenario must not leave a fixture's IO job alive.
                viewJobs.forEach { it.cancel() }
            }
        }
        var jobsFinished = false
        cleanup {
            awaitJobs(viewJobs.toList())
            jobsFinished = true
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        cleanup {
            if (observerRegistered) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    eventObservable<Bundle>(EventBus.READ_ALOUD_CONFIG_CHANGED).removeObserver(observer)
                }
            }
        }
        cleanup { if (engineCaptured) AudioPrefetchPlayback.lifecycle.revoke() }
        cleanup {
            if (engineCaptured) {
                AppConfig.ttsEngine = previousEngine
                assertEquals(previousEngine, AppConfig.ttsEngine)
            }
        }
        // A timed-out worker is a test failure, not permission to delete files it still owns.
        if (jobsFinished) {
            cleanup {
                if (::alias.isInitialized) {
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
                }
            }
            if (::file.isInitialized) {
                listOf(file, File(file.path + ".new"), File(file.path + ".bak")).forEach { target ->
                    cleanup { check(!target.exists() || target.delete()) { "fixture file cleanup failed" } }
                }
            }
        }
        MultipleFailureException.assertEmpty(errors)
    }

    private fun awaitJobs(jobs: List<Job>) {
        // Run on the instrumentation thread so main-thread completion can drain normally.
        runBlocking { withTimeout(10_000) { jobs.joinAll() } }
    }

    private fun newDialog() = IsolatedNovelAudioServerConfigDialog(
        NovelAudioServerSettings(store, probe = {
            probes.incrementAndGet()
            probe()
        }),
        trackViewJob = { viewJobs.add(it) }
    )

    private fun open() {
        scenario.onActivity { newDialog().showNow(it.supportFragmentManager, "settings") }
        awaitLoaded()
    }

    private fun awaitLoaded() {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("处理中…").fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    private fun fill() {
        rule.onNodeWithText("服务地址").performTextReplacement(URL)
        rule.onNodeWithText("访问令牌").performTextReplacement(TOKEN)
    }

    @Test
    fun saveReloadAndConfirmedClearUseTheRealEncryptedStore() {
        open()
        fill()
        val request = AudioPrefetchPlayback.lifecycle.requestUserPlay("book://settings-fixture")
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("已保存。请在朗读引擎中选择 AI 多角色听书").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(TOKEN, store.load()!!.token)
        assertFalse(AudioPrefetchPlayback.lifecycle.isPendingUserPlay(request, "book://settings-fixture"))
        assertFalse(file.readText().contains(TOKEN))
        scenario.recreate()
        awaitLoaded()
        rule.onNodeWithText("服务地址").assertTextContains(URL)
        val clearRequest = AudioPrefetchPlayback.lifecycle.requestUserPlay("book://settings-fixture")
        rule.onNodeWithText("清除配置").performScrollTo().performClick()
        rule.onNodeWithText("清除 AI 听书服务配置？").assertIsDisplayed()
        assertNotNull(store.load())
        assertTrue(AudioPrefetchPlayback.lifecycle.isPendingUserPlay(clearRequest, "book://settings-fixture"))
        rule.onNodeWithText("清除", substring = false).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("已清除服务配置").fetchSemanticsNodes().isNotEmpty() }
        assertNull(store.load())
        assertFalse(AudioPrefetchPlayback.lifecycle.isPendingUserPlay(clearRequest, "book://settings-fixture"))
        scenario.recreate()
        awaitLoaded()
        rule.onNodeWithText(URL).assertDoesNotExist()
        assertEquals(0, probes.get())
    }

    @Test
    fun failedHealthKeepsDraftAndDoesNotSaveOrRevokePlayback() {
        open()
        fill()
        val request = AudioPrefetchPlayback.lifecycle.requestUserPlay("book://settings-fixture")
        rule.onNodeWithText("测试连接").performScrollTo().performClick()
        rule.waitUntil(10_000) { probes.get() == 1 }
        awaitLoaded()
        assertNull(store.load())
        assertTrue(AudioPrefetchPlayback.lifecycle.isPendingUserPlay(request, "book://settings-fixture"))
        rule.onNodeWithText("服务地址").assertTextContains(URL)
        // Saving after the failed health check proves that the token draft was retained.
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("已保存。请在朗读引擎中选择 AI 多角色听书").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(TOKEN, store.load()!!.token)
    }

    @Test
    fun unsavedCredentialIsNotWrittenIntoSavedStateOrRestored() {
        open()
        fill()
        var savedBytes = byteArrayOf()
        NovelAudioDialogHostActivity.inspectSavedState = { state ->
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(state)
                savedBytes = parcel.marshall()
            } finally {
                parcel.recycle()
            }
        }
        scenario.recreate()
        awaitLoaded()
        assertTrue(savedBytes.isNotEmpty())
        assertFalse(String(savedBytes, Charsets.UTF_16LE).contains(TOKEN))
        assertFalse(String(savedBytes, Charsets.UTF_8).contains(TOKEN))
        assertNull(store.load())
        rule.onNodeWithText(URL).assertDoesNotExist()
    }

    @Test
    fun selectedChapterEngineHasAnAccurateSettingsSummary() {
        scenario.onActivity { host ->
            ReadAloudConfigDialog().apply { selectGroup(ReadAloudConfigGroup.Engine) }
                .showNow(host.supportFragmentManager, "read-aloud")
        }
        rule.onNodeWithText("AI 多角色听书", substring = false).assertIsDisplayed()
    }

    @Test
    fun speakEngineDialogShowsTheConfiguredEngineGroups() {
        scenario.onActivity { host ->
            SpeakEngineDialog().showNow(host.supportFragmentManager, "speak-engine")
        }
        assertTrue(
            rule.onAllNodesWithText("AI 多角色听书", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
    }

    @Test
    fun committedSaveStillNotifiesOnceWhenViewIsRecreated() = commitAcrossRecreation(clear = false)

    @Test
    fun committedClearStillNotifiesOnceWhenViewIsRecreated() = commitAcrossRecreation(clear = true)

    private fun commitAcrossRecreation(clear: Boolean) {
        if (clear) store.replace(URL, TOKEN)
        open()
        if (!clear) fill()
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1).also { unblockWrite = it }
        val oldViews = viewJobs.toList()
        afterWrite = {
            committed.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "fixture write timed out" }
        }
        try {
            if (clear) {
                rule.onNodeWithText("清除配置").performScrollTo().performClick()
                rule.onNodeWithText("清除", substring = false).performClick()
            } else {
                rule.onNodeWithText("保存").performClick()
            }
            assertTrue("写盘应已提交", committed.await(10, TimeUnit.SECONDS))
            scenario.recreate()
        } finally {
            release.countDown()
        }
        awaitJobs(oldViews)
        awaitLoaded()
        if (clear) assertNull(store.load()) else assertEquals(TOKEN, store.load()!!.token)
        rule.waitUntil(5_000) { notifications.get() > 0 }
        rule.runOnIdle { assertEquals(1, notifications.get()) }
        // Old UI completion must not replace the freshly loaded view's notice.
        rule.onNodeWithText("已清除服务配置").assertDoesNotExist()
        rule.onNodeWithText("已保存。请在朗读引擎中选择 AI 多角色听书").assertDoesNotExist()
    }

    @Test
    fun failedSaveKeepsPreviousPairAndEditableDraft() {
        store.replace("https://previous.invalid/", "previous-fixture-token")
        open()
        fill()
        failNextWrite.set(true)
        rule.onNodeWithText("保存").performClick()
        awaitStorageError()
        assertEquals("previous-fixture-token", store.load()!!.token)
        rule.onNodeWithText("服务地址").assertTextContains(URL)
        assertEquals(0, notifications.get())
        rule.onNodeWithText("保存").performClick()
        rule.waitUntil(10_000) { notifications.get() == 1 }
        assertEquals(TOKEN, store.load()!!.token)
    }

    @Test
    fun failedClearKeepsSavedPairAndDraft() {
        store.replace(URL, TOKEN)
        open()
        failNextWrite.set(true)
        rule.onNodeWithText("清除配置").performScrollTo().performClick()
        rule.onNodeWithText("清除", substring = false).performClick()
        awaitStorageError()
        assertEquals(TOKEN, store.load()!!.token)
        rule.onNodeWithText("服务地址").assertTextContains(URL)
        assertEquals(0, notifications.get())
    }

    private fun awaitStorageError() {
        val message = io.legado.app.help.readaloud.server.NovelAudioServerException("STORAGE").message!!
        rule.waitUntil(10_000) { rule.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        awaitLoaded()
    }

    @Test
    fun lateHealthResultDoesNotChangeRecreatedView() {
        val release = CompletableDeferred<Unit>().also { unblockProbe = it }
        val finished = AtomicBoolean()
        probe = {
            withContext(NonCancellable) {
                release.await()
                finished.set(true)
                ServerHealth("ok", "1", true, true)
            }
        }
        open()
        fill()
        rule.onNodeWithText("测试连接").performScrollTo().performClick()
        rule.waitUntil(10_000) { probes.get() == 1 }
        val oldViews = viewJobs.toList()
        scenario.recreate()
        awaitLoaded()
        release.complete(Unit)
        rule.waitUntil(10_000) { finished.get() }
        awaitJobs(oldViews)
        rule.waitForIdle()
        rule.onNodeWithText("连接正常，分析与语音服务均已就绪；尚未保存").assertDoesNotExist()
        rule.onNodeWithText(URL).assertDoesNotExist()
        assertNull(store.load())
        assertEquals(0, notifications.get())
    }

    private companion object {
        const val URL = "https://dialog-fixture.invalid/"
        const val TOKEN = "dialog-fixture-secret-916"
    }

    class IsolatedNovelAudioServerConfigDialog internal constructor(
        private val isolatedSettings: NovelAudioServerSettings,
        private val trackViewJob: (Job) -> Unit
    ) : NovelAudioServerConfigDialog() {
        override fun newSettings(context: Context): NovelAudioServerSettings = isolatedSettings

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            trackViewJob(checkNotNull(viewLifecycleOwner.lifecycleScope.coroutineContext[Job]))
        }
    }
}
