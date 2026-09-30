package io.legado.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.readaloud.server.AndroidNovelAudioCredentialBlob
import io.legado.app.help.readaloud.server.NovelAudioAndroidConfigStore
import io.legado.app.help.readaloud.server.NovelAudioCredentialBlob
import io.legado.app.help.readaloud.server.NovelAudioServerConfigStore
import io.legado.app.help.readaloud.server.NovelAudioServerSettings
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.book.read.config.NovelAudioServerConfigDialog
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx

/**
 * Serial, debug-only device test: one Activity, Fragment and ComposeView survive all four states.
 * Never use a production credential store or a whole-device screenshot here.
 * Requires the opt-in NovelAudioThemeTestRunner; production App startup is outside its coverage.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class NovelAudioThemeDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun sameConfigFragmentRefreshesDefaultAccentPackageAndNightAndRestoresState() {
        // Both APKs share the first-loaded j$.util implementation. Keep this API in test L8.
        val fragmentRuntime = Collections.synchronizedMap(mutableMapOf("ready" to true))
        assertEquals(true, fragmentRuntime["ready"])
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Only the debug application may be used", "io.legado.miss.app.debug", context.packageName)
        assertTrue(
            "The four-state theme test requires NovelAudioThemeTestRunner; " +
                "the default runner would start the production Application",
            context.applicationContext is NovelAudioThemeTestApplication
        )
        assertSame("Splitties must use the isolated target Application", context, appCtx)
        val failures = ThemeTestFailures()
        val id = UUID.randomUUID().toString()
        val alias = "legado.novel_audio_server.theme-device-test.$id"
        var ownsAlias = false
        var credentialDir: File? = null
        var snapshot: ThemeDeviceSnapshot? = null
        var library: ThemeLibraryFixture? = null
        var scenario: ActivityScenario<NovelAudioDialogHostActivity>? = null
        val originalFactory = NovelAudioDialogHostActivity.factory
        var factoryInstalled = false
        val probes = AtomicInteger()
        val writes = AtomicInteger()
        val settingsLoads = AtomicInteger()

        try {
            assertNull("Do not share the host with another running test", originalFactory)
            // Capture before any theme API (even localDir() can create directories).
            onThemeMain { snapshot = ThemeDeviceSnapshot.capture(context) }
            val localTheme = ThemeLibraryFixture(context, id)
            library = localTheme
            onThemeMain {
                snapshot!!.protectBackgroundFiles()
                // Builtin configs leave nullable geometry/alpha fields untouched. Fix only these
                // theme keys so the pixel oracle never inherits a user's translucent dialog.
                val edit = context.defaultSharedPreferences.edit()
                for (night in listOf(false, true)) {
                    edit.putInt(ThemeRuntimeKeys.dialogAlpha(night), 100)
                        .putInt(ThemeRuntimeKeys.uiLayoutAlpha(night), 100)
                        .putString(ThemeRuntimeKeys.uiCornerScale(night), "1")
                }
                check(edit.commit()) { "Could not establish opaque theme fixtures" }
            }
            val day = ThemePackageManager.builtinEntryForKit(false)
            val night = ThemePackageManager.builtinEntryForKit(true)
            applyEntry(context, day)

            val privateDir = File(context.noBackupFilesDir, "novel-audio-theme-device-test-$id")
            check(privateDir.mkdir()) { "Credential fixture directory must be new" }
            credentialDir = privateDir
            val credentialFile = File(privateDir, "credentials")
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            check(!keyStore.containsAlias(alias)) { "Keystore fixture alias must be new" }
            ownsAlias = true
            val disk = AndroidNovelAudioCredentialBlob(credentialFile)
            val store = NovelAudioServerConfigStore(object : NovelAudioCredentialBlob {
                override fun read(): ByteArray? = disk.read()
                override fun write(value: ByteArray) {
                    writes.incrementAndGet()
                    error("Theme test must not save or clear credentials")
                }
            }, NovelAudioAndroidConfigStore.cipher(alias))
            assertNull("Isolated store must start unconfigured", store.load())
            val settings = NovelAudioServerSettings(store, probe = {
                probes.incrementAndGet()
                error("Theme test must not perform a health request")
            })
            fun newDialog() = ThemeIsolatedDialog(settings, settingsLoads)
            NovelAudioDialogHostActivity.factory = object : FragmentFactory() {
                override fun instantiate(classLoader: ClassLoader, className: String): Fragment =
                    if (className == ThemeIsolatedDialog::class.java.name) newDialog()
                    else error("Unexpected Fragment in the isolated theme-test host")
            }
            factoryInstalled = true
            val launched = ActivityScenario.launch(NovelAudioDialogHostActivity::class.java)
            scenario = launched
            lateinit var host: NovelAudioDialogHostActivity
            lateinit var fragment: ThemeIsolatedDialog
            lateinit var composeView: View
            lateinit var dialogWindow: Window
            launched.onThemeActivity {
                host = it
                fragment = newDialog()
                fragment.showNow(it.supportFragmentManager, DIALOG_TAG)
                composeView = fragment.requireView()
                // Semantics editing needs no IME. Keep keyboard/suggestions out of pixel captures,
                // without replacing the production Fragment view or its composable content.
                fragment.requireDialog().window!!.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                dialogWindow = requireNotNull(fragment.requireDialog().window)
            }
            rule.waitUntil(10_000) {
                settingsLoads.get() == 1 &&
                    rule.onAllNodesWithText("处理中…").fetchSemanticsNodes().isEmpty()
            }
            rule.waitForIdle()
            for (label in listOf("服务地址", "访问令牌")) {
                val text = rule.onNodeWithText(label).fetchSemanticsNode()
                    .config[SemanticsProperties.EditableText].text
                assertTrue("The isolated dialog must load empty fields", text.isEmpty())
            }
            // Draft only: the primary button must be enabled for an unambiguous accent oracle.
            // The only token ever typed is synthetic, masked, and never persisted.
            rule.onNodeWithText("服务地址").performTextReplacement(ADDRESS)
            rule.onNodeWithText("访问令牌").performTextReplacement("theme-device-fixture-only")
            val external = requireNotNull(context.getExternalFilesDir(null)) {
                "External storage is required for theme screenshots"
            }
            val screenshots = File(external, "novel-audio-theme-device-test/$id")
            check(!screenshots.exists() && screenshots.mkdirs()) { "Screenshot directory must be new" }
            val evidence = linkedMapOf<String, ThemePixelEvidence>()
            fun capture(name: String, config: ThemeConfig.Config) {
                launched.onThemeActivity {
                    assertSame("Host must not be recreated", host, it)
                    assertSame("Fragment must not be replaced", fragment, it.supportFragmentManager.findFragmentByTag(DIALOG_TAG))
                    assertSame("ComposeView must survive the theme change", composeView, fragment.requireView())
                    assertSame("Dialog window must survive the theme change", dialogWindow, fragment.requireDialog().window)
                    assertEquals(config.isNightTheme, AppConfig.isNightTheme)
                    assertEquals(config.accentColor.toColorInt(), ThemeStore.accentColor(it))
                }
                assertEquals("No Fragment reload during theme switching", 1, settingsLoads.get())
                assertNull("Draft must remain unpersisted", store.load())
                evidence[name] = captureAndCheck(
                    name, config, screenshots, failures, dialogWindow, composeView
                )
            }
            failures.attempt("default theme") {
                applyEntry(host, day)
                capture("01-default", requireNotNull(day.packageInfo.config))
            }
            failures.attempt("custom accent") {
                val custom = requireNotNull(day.packageInfo.config).copy(accentColor = "#00796B")
                onThemeMain {
                    ThemeConfig.applyConfig(host, custom, switchNightMode = false, notify = false)
                    ThemeConfig.applyTheme(host)
                }
                capture("02-custom-accent", custom)
            }
            failures.attempt("UUID local theme package") {
                val entry = localTheme.create()
                applyEntry(host, entry)
                onThemeMain {
                    assertEquals(id, context.defaultSharedPreferences.getString(PreferKey.dThemeDirName, null))
                }
                capture("03-local-package", requireNotNull(entry.packageInfo.config))
            }
            failures.attempt("night theme") {
                applyEntry(host, night)
                capture("04-night", requireNotNull(night.packageInfo.config))
            }
            failures.attempt("four distinct visual states") {
                assertEquals("Every state must produce pixel evidence", 4, evidence.size)
                val values = evidence.values.toList()
                assertEquals("Primary button must change accent in all four states", 4, values.map { it.button }.toSet().size)
                assertEquals("Selection must track all four accents", 4, values.map { it.selection }.toSet().size)
                assertEquals("Field surface must refresh in all four states", 4, values.map { it.field }.toSet().size)
                assertEquals("An accent-only change must preserve the panel", values[0].surface, values[1].surface)
                assertTrue("Local package must replace the panel surface", values[1].surface != values[2].surface)
                assertTrue("Night mode must replace the panel surface", values[2].surface != values[3].surface)
                assertTrue("Night panel must be darker", brightness(values[3].surface) < brightness(values[0].surface))
                assertEquals(4, screenshots.listFiles { file -> file.extension == "png" }!!.size)
            }
        } catch (failure: Throwable) {
            failures.record("theme fixture / execution", failure)
        } finally {
            // No cleanup step may prevent any later restoration or its assertions.
            failures.attempt("close scenario") { scenario?.close() }
            failures.attempt("restore host factory") {
                if (factoryInstalled) NovelAudioDialogHostActivity.factory = originalFactory
            }
            failures.attempt("restore theme library") { library?.restore(failures) }
            failures.attempt("restore theme runtime") { snapshot?.restore(context, failures) }
            failures.attempt("credential isolation assertions") {
                assertEquals("No health calls", 0, probes.get())
                assertEquals("No credential writes", 0, writes.get())
            }
            failures.attempt("remove isolated Keystore alias") {
                if (ownsAlias) {
                    KeyStore.getInstance("AndroidKeyStore").apply {
                        load(null)
                        deleteEntry(alias)
                    }
                }
            }
            failures.attempt("assert isolated Keystore alias absent") {
                if (ownsAlias) {
                    assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias))
                }
            }
            credentialDir?.let { dir ->
                // Only exact, test-owned paths; never recursively delete a shared directory.
                for (name in listOf("credentials", "credentials.new", "credentials.bak")) {
                    failures.attempt("remove isolated $name") { deleteThemeTestFile(File(dir, name)) }
                }
                failures.attempt("remove credential fixture directory") { deleteThemeTestFile(dir) }
                failures.attempt("assert credential fixture absent") { assertFalse(dir.exists()) }
            }
            failures.attempt("assert host factory restored") {
                assertSame(originalFactory, NovelAudioDialogHostActivity.factory)
            }
        }
        failures.throwIfAny()
    }

    private fun applyEntry(context: Context, entry: ThemePackageManager.Entry) {
        runBlocking(Dispatchers.Main) {
            AppConfig.themeMode = if (entry.packageInfo.isNightTheme) "2" else "1"
            AppConfig.isEInkMode = false
            // Real package validation/application + real runtime refresh. Avoid RECREATE and
            // BookCover work; neither is needed to exercise same-Fragment ThemeSync updates.
            ThemePackageManager.apply(context, entry, switchNightMode = false, notify = false)
            ThemeConfig.applyTheme(context)
        }
    }

    private fun captureAndCheck(
        name: String,
        config: ThemeConfig.Config,
        directory: File,
        failures: ThemeTestFailures,
        dialogWindow: Window,
        composeView: View
    ): ThemePixelEvidence {
        rule.waitForIdle()
        val field = rule.onNodeWithText("服务地址").performScrollTo()
        field.performTextInputSelection(TextRange(0))
        rule.mainClock.advanceTimeBy(400)
        field.assertIsDisplayed()
        val unselected = captureDialogNode(field, dialogWindow, composeView)
        field.performTextInputSelection(TextRange(0, 15))
        rule.waitForIdle()
        val selected = captureDialogNode(field, dialogWindow, composeView)
        val buttonNode = rule.onNodeWithText("保存").assertIsEnabled().assertIsDisplayed()
        val button = captureDialogNode(buttonNode, dialogWindow, composeView)
        val titleNode = rule.onNodeWithText(TITLE).assertIsDisplayed()
        val title = captureDialogNode(titleNode, dialogWindow, composeView)
        // Match only the controlled dialog's Compose root, never the device or another window.
        val rootNode = rule.onNode(isRoot() and hasAnyDescendant(hasText(TITLE)))
        val image = captureDialogNode(rootNode, dialogWindow, composeView)
        FileOutputStream(File(directory, "$name.png")).use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
            it.fd.sync()
        }
        val accent = Color(config.accentColor.toColorInt())
        val surface = Color((config.cardColor ?: config.bottomBackground).toColorInt())
        val fieldColor = config.mutedColor?.let { Color(it.toColorInt()) }
            ?: Color(ColorUtils.blendColors(surface.toArgb(), accent.toArgb(), if (config.isNightTheme) 0.10f else 0.05f))
        val selection = accent.copy(alpha = 0.4f).compositeOver(fieldColor)
        val measured = ThemePixelEvidence(
            button = dominantThemePixel(button),
            surface = dominantThemePixel(title),
            field = dominantThemePixel(unselected),
            selection = changedSelectionPixels(unselected, selected, fieldColor, selection, name, failures)
        )
        // Save the screenshot before assertions so a color regression still leaves evidence.
        failures.attempt("$name primary button pixels") {
            assertTrue("Primary button must use current accent", closeThemeColor(Color(measured.button), accent))
        }
        failures.attempt("$name panel pixels") {
            assertTrue("Dialog panel must use the current surface", closeThemeColor(Color(measured.surface), surface))
        }
        failures.attempt("$name field pixels") {
            assertTrue("Field must use the current field surface", closeThemeColor(Color(measured.field), fieldColor))
        }
        return measured
    }

    /**
     * The native DialogFragment has its own Window but no Compose DialogWindowProvider.
     * Bind PixelCopy to that Window, using the internal Compose root's window-relative origin.
     */
    @RequiresApi(26)
    private fun captureDialogNode(
        interaction: SemanticsNodeInteraction,
        dialogWindow: Window,
        expectedComposeView: View
    ): ImageBitmap {
        check(Looper.myLooper() != Looper.getMainLooper()) { "PixelCopy must wait off the main thread" }
        val wasDrawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        var lastResult = PixelCopy.ERROR_UNKNOWN
        try {
            // Some test environments disable hardware drawing. Restore their setting even if
            // capture fails; no global renderer setting is left changed by this helper.
            if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            for (attempt in 0 until 3) {
                rule.waitForIdle()
                val node = interaction.fetchSemanticsNode()
                lateinit var rootView: View
                lateinit var source: Rect
                onThemeMain {
                    rootView = (node.root as? ViewRootForTest)?.view
                        ?: error("Theme capture root is not a ViewRootForTest")
                    // AndroidComposeView is a child of the Fragment's ComposeView, not that
                    // ComposeView itself. A popup or another window cannot pass these checks.
                    check(rootView.isDescendantOf(expectedComposeView)) {
                        "Theme capture root escaped the controlled ComposeView"
                    }
                    check(rootView.isAttachedToWindow && expectedComposeView.isAttachedToWindow)
                    check(rootView.rootView === dialogWindow.decorView) {
                        "Theme capture must use the controlled DialogFragment Window"
                    }
                    val bounds = node.boundsInRoot
                    val location = IntArray(2)
                    rootView.getLocationInWindow(location)
                    source = Rect(
                        bounds.left.roundToInt(), bounds.top.roundToInt(),
                        bounds.right.roundToInt(), bounds.bottom.roundToInt()
                    ).apply { offset(location[0], location[1]) }
                    check(!source.isEmpty) { "Theme capture bounds must be non-empty" }
                    check(rootView.themeBoundsInWindow().contains(source))
                    check(expectedComposeView.themeBoundsInWindow().contains(source))
                    check(dialogWindow.decorView.themeBoundsInWindow().contains(source)) {
                        "Theme capture bounds escaped the controlled dialog"
                    }
                }
                awaitThemeDraw(rootView)
                val bitmap = Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.ARGB_8888)
                val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
                val completed = CountDownLatch(1)
                // Synchronous request exceptions are rethrown on the test thread by
                // onThemeMain, preserving the fixture's finally-based restoration.
                onThemeMain {
                    PixelCopy.request(dialogWindow, Rect(source), bitmap, { status ->
                        result.set(status)
                        completed.countDown()
                    }, Handler(Looper.getMainLooper()))
                }
                check(completed.await(10, TimeUnit.SECONDS)) { "Dialog Window PixelCopy timed out" }
                lastResult = result.get()
                if (lastResult == PixelCopy.SUCCESS) return bitmap.asImageBitmap()
                bitmap.recycle() // Callback completed; the copy no longer owns this bitmap.
                if (lastResult != PixelCopy.ERROR_SOURCE_NO_DATA) break
            }
            error("Dialog Window PixelCopy failed with status $lastResult")
        } finally {
            if (!wasDrawingEnabled) HardwareRendererCompat.setDrawingEnabled(false)
        }
    }

    /** Wait for an actual frame, not merely a Compose clock tick or a queued invalidate(). */
    private fun awaitThemeDraw(view: View) {
        val drawn = CountDownLatch(1)
        val committed = Runnable { drawn.countDown() }
        var observer: ViewTreeObserver? = null
        var drawListener: ViewTreeObserver.OnDrawListener? = null
        var frameCallbackRegistered = false
        try {
            onThemeMain {
                check(view.isAttachedToWindow) { "Dialog detached before draw" }
                observer = view.viewTreeObserver
                if (Build.VERSION.SDK_INT >= 29 && view.isHardwareAccelerated) {
                    observer!!.registerFrameCommitCallback(committed)
                    frameCallbackRegistered = true
                } else {
                    val listener = object : ViewTreeObserver.OnDrawListener {
                        private var posted = false
                        override fun onDraw() {
                            if (!posted) {
                                posted = true
                                // Run after this traversal; removing an OnDrawListener inside
                                // onDraw itself is forbidden by ViewTreeObserver.
                                view.post(committed)
                            }
                        }
                    }
                    drawListener = listener
                    observer!!.addOnDrawListener(listener)
                }
                view.invalidate()
            }
            check(drawn.await(10, TimeUnit.SECONDS)) { "Controlled dialog did not draw" }
        } finally {
            observer?.let { capturedObserver ->
                onThemeMain {
                    if (frameCallbackRegistered && Build.VERSION.SDK_INT >= 29 && capturedObserver.isAlive) {
                        capturedObserver.unregisterFrameCommitCallback(committed)
                    }
                    drawListener?.let { listener ->
                        if (capturedObserver.isAlive) capturedObserver.removeOnDrawListener(listener)
                        view.removeCallbacks(committed)
                    }
                }
            }
        }
    }

    // Called only on the main thread, in the same coordinate space used by Window PixelCopy.
    private fun View.themeBoundsInWindow(): Rect {
        val location = IntArray(2)
        getLocationInWindow(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height)
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: View? = this
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    // FragmentManager requires a public, static nested Fragment class, even for showNow(instance).
    class ThemeIsolatedDialog internal constructor(
        private val isolatedSettings: NovelAudioServerSettings,
        private val loads: AtomicInteger
    ) : NovelAudioServerConfigDialog() {
        override fun newSettings(context: Context): NovelAudioServerSettings {
            loads.incrementAndGet()
            return isolatedSettings
        }
    }

    private companion object {
        const val TITLE = "AI 多角色听书服务"
        const val DIALOG_TAG = "novel-audio-theme-device-test"
        const val ADDRESS = "https://theme-fixture.invalid/"
    }
}

private data class ThemePixelEvidence(val button: Int, val surface: Int, val field: Int, val selection: Int)

private fun closeThemeColor(actual: Color, expected: Color): Boolean =
    abs(actual.red - expected.red) < 0.015f &&
        abs(actual.green - expected.green) < 0.015f &&
        abs(actual.blue - expected.blue) < 0.015f &&
        abs(actual.alpha - expected.alpha) < 0.015f

private fun brightness(argb: Int): Float = Color(argb).let { it.red + it.green + it.blue }

private fun dominantThemePixel(image: ImageBitmap): Int {
    val pixels = image.toPixelMap()
    val counts = HashMap<Int, Int>()
    // Ignore rounded corners/outline, not arbitrary absolute device coordinates.
    for (y in pixels.height / 5 until pixels.height * 4 / 5) {
        for (x in pixels.width / 10 until pixels.width * 9 / 10) {
            val color = pixels[x, y].toArgb()
            counts[color] = (counts[color] ?: 0) + 1
        }
    }
    return requireNotNull(counts.maxByOrNull { it.value }).key
}

private fun changedSelectionPixels(
    before: ImageBitmap,
    after: ImageBitmap,
    field: Color,
    selection: Color,
    name: String,
    failures: ThemeTestFailures
): Int {
    val first = before.toPixelMap()
    val second = after.toPixelMap()
    val matches = HashMap<Int, Int>()
    failures.attempt("$name selection geometry") {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
    }
    for (y in 0 until minOf(first.height, second.height)) {
        for (x in 0 until minOf(first.width, second.width)) {
            // Pair the same pixels before/after selection. A matching outline or label alone
            // cannot pass: previously plain field pixels must become the 40% accent overlay.
            if (closeThemeColor(first[x, y], field) && closeThemeColor(second[x, y], selection)) {
                val color = second[x, y].toArgb()
                matches[color] = (matches[color] ?: 0) + 1
            }
        }
    }
    failures.attempt("$name selection pixels") {
        assertTrue("At least 100 field pixels must become the 40% current-accent selection", matches.values.sum() > 100)
    }
    return matches.maxByOrNull { it.value }?.key ?: Color.Transparent.toArgb()
}

private class ThemeTestFailures {
    private val failures = mutableListOf<Throwable>()
    fun record(step: String, failure: Throwable) {
        failures.add(AssertionError(step, failure))
    }
    fun attempt(step: String, action: () -> Unit) {
        kotlin.runCatching(action).onFailure { record(step, it) }
    }
    fun throwIfAny() {
        if (failures.isEmpty()) return
        val details = failures.joinToString(separator = "\n") { failure ->
            generateSequence(failure) { it.cause }
                .joinToString(" <- ") { cause ->
                    "${cause::class.java.simpleName}: ${cause.message.orEmpty()}"
                }
        }
        throw AssertionError(details, failures.first())
    }
}

private fun onThemeMain(action: () -> Unit) {
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        kotlin.runCatching(action).onFailure { failure = it }
    }
    // Report on the test thread so a failed assertion cannot kill the main thread and bypass
    // finally (also works with instrumentation implementations that do not shuttle exceptions).
    failure?.let { throw it }
}

private fun ActivityScenario<NovelAudioDialogHostActivity>.onThemeActivity(
    action: (NovelAudioDialogHostActivity) -> Unit
) {
    var failure: Throwable? = null
    onActivity { host -> kotlin.runCatching { action(host) }.onFailure { failure = it } }
    failure?.let { throw it }
}

private enum class ThemePrefType { STRING, INT, BOOLEAN }

private data class ThemePrefSlot(val key: String, val type: ThemePrefType) {
    fun read(prefs: SharedPreferences): Any? {
        if (!prefs.contains(key)) return null
        return when (type) {
            ThemePrefType.STRING -> requireNotNull(prefs.getString(key, null))
            ThemePrefType.INT -> prefs.getInt(key, 0)
            ThemePrefType.BOOLEAN -> prefs.getBoolean(key, false)
        }
    }
}

/** A missing key is null; stored strings, including "", are preserved with their actual type. */
private fun themePreferenceWhitelist(): List<ThemePrefSlot> {
    val strings = mutableSetOf(
        PreferKey.themeMode, PreferKey.dThemeName, PreferKey.dNThemeName,
        PreferKey.dThemeDirName, PreferKey.dNThemeDirName,
        PreferKey.bgImage, PreferKey.bgImageN, PreferKey.bgImageCrop, PreferKey.bgImageNCrop,
        PreferKey.bookInfoBgImage, PreferKey.bookInfoBgImageN,
        PreferKey.panelBgImage, PreferKey.panelBgImageN, PreferKey.panelBgScaleType, PreferKey.panelBgScaleTypeN,
        PreferKey.panelBorderColor, PreferKey.panelBorderColorN
    )
    val integers = mutableSetOf(
        PreferKey.cPrimary, PreferKey.cAccent, PreferKey.cBackground, PreferKey.cBBackground,
        PreferKey.cNPrimary, PreferKey.cNAccent, PreferKey.cNBackground, PreferKey.cNBBackground,
        PreferKey.bgImageBlurring, PreferKey.bgImageNBlurring, PreferKey.panelBorderAlpha, PreferKey.panelBorderAlphaN
    )
    val booleans = mutableSetOf(PreferKey.tNavBar, PreferKey.tNavBarN)
    val runtimeTypes = linkedMapOf<String, ThemePrefType>()
    for (night in listOf(false, true)) {
        listOf(
            ThemeRuntimeKeys.uiFontPath(night), ThemeRuntimeKeys.titleFontPath(night),
            ThemeRuntimeKeys.uiFontColor(night), ThemeRuntimeKeys.titleFontColor(night),
            ThemeRuntimeKeys.uiCornerScale(night), ThemeRuntimeKeys.themeCardColor(night),
            ThemeRuntimeKeys.themeMutedColor(night), ThemeRuntimeKeys.themeSearchFieldBackgroundColor(night),
            ThemeRuntimeKeys.themeTabBackgroundColor(night), ThemeRuntimeKeys.themeShelfColor(night)
        ).forEach { runtimeTypes[it] = ThemePrefType.STRING }
        listOf(
            ThemeRuntimeKeys.fontScale(night), ThemeRuntimeKeys.uiLayoutAlpha(night),
            ThemeRuntimeKeys.dialogAlpha(night), ThemeRuntimeKeys.themeCardShadow(night),
            ThemeRuntimeKeys.themeCardBackgroundBlur(night)
        ).forEach { runtimeTypes[it] = ThemePrefType.INT }
        listOf(ThemeRuntimeKeys.uiCornerSearchFollow(night), ThemeRuntimeKeys.uiCornerReplyFollow(night))
            .forEach { runtimeTypes[it] = ThemePrefType.BOOLEAN }
    }
    // Fail before mutation if production adds a runtime key whose storage type we haven't audited.
    check(runtimeTypes.keys == ThemeRuntimeKeys.allKeys()) { "Theme runtime whitelist needs auditing" }
    ThemeRuntimeKeys.allKeys().forEach { key ->
        when (runtimeTypes.getValue(key)) {
            ThemePrefType.STRING -> strings.add(key)
            ThemePrefType.INT -> integers.add(key)
            ThemePrefType.BOOLEAN -> booleans.add(key)
        }
    }
    return strings.map { ThemePrefSlot(it, ThemePrefType.STRING) } +
        integers.map { ThemePrefSlot(it, ThemePrefType.INT) } +
        booleans.map { ThemePrefSlot(it, ThemePrefType.BOOLEAN) }
}

private fun SharedPreferences.Editor.putThemeValue(key: String, value: Any?) {
    when (value) {
        null -> remove(key)
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Boolean -> putBoolean(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Set<*> -> {
            check(value.all { it is String }) { "Unexpected theme set type" }
            putStringSet(key, value.map { it as String }.toSet())
        }
        else -> error("Unexpected theme preference type")
    }
}

private class ThemeDeviceSnapshot private constructor(
    private val preferences: Map<ThemePrefSlot, Any?>,
    private val themeStore: Map<String, Any?>,
    private val mode: String?,
    private val eink: Boolean,
    private val accentCache: Int,
    private val fields: Map<Field, Any?>
) {
    fun protectBackgroundFiles() {
        // ThemeConfig is @Keep. Its first applyConfig otherwise calls clearBg(), enumerating
        // and deleting user background files. Resolve every named field before ANY mutation;
        // a changed/shrunk implementation must fail closed, not run destructive cleanup.
        fields.keys.single { it.name == "needClearImg" }.setBoolean(null, false)
    }

    fun restore(context: Context, failures: ThemeTestFailures) {
        preferences.forEach { (slot, value) ->
            failures.attempt("restore theme preference ${slot.key}") {
                onThemeMain {
                    val prefs = context.defaultSharedPreferences
                    // In particular, do not rewrite unchanged themeMode: its listener can apply
                    // a user's appearance kit asynchronously. Missing originals are removed.
                    val unchanged = kotlin.runCatching { slot.read(prefs) == value }.getOrDefault(false)
                    if (!unchanged) {
                        val edit = prefs.edit()
                        edit.putThemeValue(slot.key, value)
                        check(edit.commit()) { "Theme preference commit failed" }
                    }
                }
            }
        }
        failures.attempt("restore in-memory mode") {
            onThemeMain {
                AppConfig.themeMode = mode
                AppConfig.isEInkMode = eink
            }
        }
        // Restore exact ThemeConfig cache values (including a lazy/null configSnapshot), rather
        // than re-parsing/re-saving the library or resolving the user's asset files.
        fields.forEach { (field, value) ->
            failures.attempt("restore ThemeConfig.${field.name}") { onThemeMain { field.set(null, value) } }
        }
        failures.attempt("invalidate derived panel bitmap") { onThemeMain { UiCorner.loadPanelBitmap(null) } }
        failures.attempt("restore exact app_themes snapshot") {
            onThemeMain {
                val edit = ThemeStore.prefs(context).edit().clear()
                themeStore.forEach { (key, value) -> edit.putThemeValue(key, value) }
                check(edit.commit()) { "app_themes restore failed" }
            }
        }
        failures.attempt("restore static accent and notify") {
            onThemeMain {
                ThemeStore.accentColor = accentCache
                ThemeSync.bump()
            }
        }
        // applyTheme() must NOT follow the exact snapshot: it changes values_changed,
        // is_configured and derived colors. The captured caches already restore that state.
        preferences.forEach { (slot, value) ->
            failures.attempt("assert restored theme preference ${slot.key}") {
                assertTrue("Theme preference must match its original presence/type/value", slot.read(context.defaultSharedPreferences) == value)
            }
        }
        failures.attempt("assert exact restored app_themes") {
            // This is the dedicated theme file, not defaultSharedPreferences.
            assertTrue("app_themes must match exactly", ThemeStore.prefs(context).all == themeStore)
        }
        failures.attempt("assert restored memory mode/accent") {
            onThemeMain {
                assertTrue(AppConfig.themeMode == mode)
                assertEquals(eink, AppConfig.isEInkMode)
                assertEquals(accentCache, ThemeStore.accentColor)
            }
        }
        fields.forEach { (field, value) ->
            failures.attempt("assert restored ThemeConfig.${field.name}") {
                onThemeMain {
                    // Do not use assertSame here: its failure message would print the entire
                    // user's theme list. Check identity without formatting the cached values.
                    if (field.name == "configSnapshot") {
                        assertTrue("Theme library cache identity must match", value === field.get(null))
                    } else {
                        assertTrue("Theme cache must match its snapshot", field.get(null) == value)
                    }
                }
            }
        }
    }

    companion object {
        fun capture(context: Context): ThemeDeviceSnapshot {
            val fields = listOf(
                "needClearImg", "configSnapshot", "usableBgImageCacheKey", "usableBgImageCacheValue",
                "effectiveUiFontColorCache", "effectiveTitleFontColorCache"
            ).associate { name ->
                val field = ThemeConfig::class.java.getDeclaredField(name).apply { isAccessible = true }
                field to field.get(null)
            }
            val themeStore = ThemeStore.prefs(context).all.mapValues { (_, value) ->
                if (value is Set<*>) value.toSet() else value
            }
            return ThemeDeviceSnapshot(
                themePreferenceWhitelist().associateWith { it.read(context.defaultSharedPreferences) },
                themeStore, AppConfig.themeMode, AppConfig.isEInkMode, ThemeStore.accentColor, fields
            )
        }
    }
}

/**
 * Own only one UUID package. Preserve the theme library as raw bytes, not a Gson round-trip
 * (normalization would discard formatting, unknown fields and possibly duplicate user entries).
 */
private class ThemeLibraryFixture(context: Context, private val id: String) {
    private val target = File(ThemeConfig.configFilePath)
    private val staging = File(target.parentFile, ".${target.name}.staging")
    private val backup = File(target.parentFile, ".${target.name}.backup")
    private val restoreFile = File(target.parentFile, ".${target.name}.$id.restore")
    private val root = File(requireNotNull(context.getExternalFilesDir(null)), "themePackages")
    private val day = File(root, "day")
    private val night = File(root, "night")
    private val parentExisted = listOf(day, night, root).associateWith { it.exists() }
    private val directory = File(day, id)
    private val original: ByteArray?
    private val originalTimestamp: Long
    private var ownsDirectory = false
    private var libraryTouched = false
    private var entry: ThemePackageManager.Entry? = null

    init {
        check(!staging.exists() && !backup.exists()) { "Pending theme library transaction; refusing to disturb it" }
        check(!restoreFile.exists() && !directory.exists()) { "Theme fixture paths must be new" }
        check(!target.exists() || target.isFile) { "Theme library is not a regular file" }
        check(target.canonicalFile == File(target.parentFile!!.canonicalFile, target.name)) {
            "Refusing to follow a theme library symlink"
        }
        original = if (target.exists()) target.readBytes() else null
        originalTimestamp = target.lastModified()
    }

    fun create(): ThemePackageManager.Entry {
        check(hasOriginalBytes()) { "Theme library changed since snapshot; refusing to add a fixture" }
        // No package-directory enumeration or nullable fallback to the user's book/panel assets.
        val config = AppearanceKitManager.darkPurpleDayConfig().copy(
            themeName = id, backgroundImgPath = "", bookInfoBackgroundImgPath = "",
            panelBackgroundImgPath = "", uiFontPath = "", titleFontPath = "",
            dialogAlpha = 100, uiLayoutAlpha = 100, uiCornerScale = 1f
        )
        check(ThemeConfig.configList.none { it.themeName == id }) { "UUID theme name collision" }
        check(day.exists() || day.mkdirs()) { "Could not prepare package parent" }
        check(directory.mkdir()) { "Theme package directory must be exclusively test-owned" }
        ownsDirectory = true
        libraryTouched = true // Also recover if addFromConfig throws after its first disk write.
        val created = runBlocking { ThemePackageManager.addFromConfig(config) }
        entry = created
        check(created.source == ThemePackageManager.Source.LOCAL && created.dirName == id)
        check(created.localDir?.canonicalFile == directory.canonicalFile)
        val manifest = File(directory, "theme.json")
        check(manifest.isFile) { "A real local package manifest is required" }
        // Reload only this test's manifest, then apply that disk-backed entry.
        val pkg = GSON.fromJson(manifest.readText(), ThemePackageManager.Package::class.java)
        check(pkg.name == id && pkg.dirName == id && !pkg.isNightTheme)
        check(pkg.config?.accentColor == config.accentColor)
        return created.copy(packageInfo = pkg)
    }

    fun restore(failures: ThemeTestFailures) {
        failures.attempt("delete UUID package through manager") {
            entry?.let {
                check(ownsDirectory && it.localDir?.canonicalFile == directory.canonicalFile)
                runBlocking { ThemePackageManager.deleteLocal(it) }
            }
        }
        failures.attempt("remove partial UUID manifest") {
            if (ownsDirectory) deleteThemeTestFile(File(directory, "theme.json"))
        }
        failures.attempt("remove partial UUID directory") {
            if (ownsDirectory) deleteThemeTestFile(directory)
        }
        failures.attempt("restore raw themeConfig.json") {
            if (libraryTouched && !hasOriginalBytes()) {
                if (original == null) {
                    deleteThemeTestFile(target)
                } else {
                    check(restoreFile.createNewFile()) { "Restore staging must be test-owned" }
                    FileOutputStream(restoreFile).use {
                        it.write(original)
                        it.fd.sync()
                    }
                    check(restoreFile.readBytes().contentEquals(original)) { "Theme library restore staging mismatch" }
                    // Same-filesystem atomic replace; no delete-then-write gap for the user library.
                    Os.rename(restoreFile.absolutePath, target.absolutePath)
                    check(target.setLastModified(originalTimestamp)) { "Could not restore theme library timestamp" }
                }
            }
        }
        // API add/delete may leave transaction sidecars on an exception. Initially they were
        // absent. Remove them only after raw recovery succeeds; otherwise retain recovery data.
        for (file in listOf(staging, backup, restoreFile)) {
            failures.attempt("restore absence of ${file.name}") {
                if (libraryTouched) {
                    check(hasOriginalBytes()) { "Keep theme library recovery data until raw restore succeeds" }
                    deleteThemeTestFile(file)
                }
            }
        }
        parentExisted.forEach { (dir, existed) ->
            failures.attempt("restore package parent ${dir.name}") {
                // File.delete refuses nonempty directories: never delete anybody else's package.
                if (!existed) deleteThemeTestFile(dir)
            }
        }
        failures.attempt("assert raw themeConfig.json restored") {
            assertTrue("Theme library presence and bytes must match exactly", hasOriginalBytes())
        }
        for (file in listOf(staging, backup, restoreFile, directory)) {
            failures.attempt("assert fixture path absent ${file.name}") { assertFalse(file.exists()) }
        }
        parentExisted.forEach { (dir, existed) ->
            failures.attempt("assert package parent presence ${dir.name}") { assertEquals(existed, dir.exists()) }
        }
    }

    private fun hasOriginalBytes(): Boolean =
        if (original == null) !target.exists() else target.isFile && target.readBytes().contentEquals(original)
}

private fun deleteThemeTestFile(file: File) {
    check(!file.exists() || file.delete()) { "Could not remove test-owned theme fixture ${file.name}" }
}
