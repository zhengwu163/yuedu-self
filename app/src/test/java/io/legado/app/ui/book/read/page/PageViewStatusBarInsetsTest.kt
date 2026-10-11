package io.legado.app.ui.book.read.page

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression contract for the EPUB legacy reader's visible system-bar spacer. */
class PageViewStatusBarInsetsTest {
    private val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.isDirectory }
    private val page = File(root, "io/legado/app/ui/book/read/page/PageView.kt").readText()
    private val statusBarUpdate = page.substringAfter("fun upStatusBar()")
        .substringBefore("fun upNavigationBar()")

    @Test
    fun `EPUB format must not discard the visible system status bar inset`() {
        assertFalse("EPUB content was placed behind the camera despite a visible status bar",
            statusBarUpdate.contains("isEpub"))
        assertTrue(page.contains("binding.vwStatusBar.applyStatusBarPadding()"))
    }

    @Test
    fun `explicit fullscreen and multiwindow retain their existing spacer rules`() {
        assertTrue(statusBarUpdate.contains("ReadBookConfig.hideStatusBar"))
        assertTrue(statusBarUpdate.contains("isInMultiWindow"))
    }

    @Test
    fun `status bar changes reapply cutout insets instead of retaining duplicate top space`() {
        assertTrue("Cutout padding must be recalculated after the spacer visibility changes",
            statusBarUpdate.contains("ViewCompat.requestApplyInsets(binding.vwRoot)"))
    }
}
