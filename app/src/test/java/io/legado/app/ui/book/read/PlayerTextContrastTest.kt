package io.legado.app.ui.book.read

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTextContrastTest {
    @Test fun dayThemeKeepsItsReadableText() {
        val original = Color.Black.copy(alpha = 0.94f)
        assertEquals(original, readablePlayerTextColor(original,
            listOf(Color.White, Color(245, 241, 233)), Color.Black, Color.White))
    }

    @Test fun customReadableFontColorIsNotOverwritten() {
        val original = Color(34, 102, 0)
        assertEquals(original, readablePlayerTextColor(original,
            listOf(Color.White, Color(245, 241, 233)), Color.Black, Color.White))
    }

    @Test fun nightThemeKeepsItsReadableAuthoredText() {
        val original = Color(230, 221, 231).copy(alpha = 0.94f)
        assertEquals(original, readablePlayerTextColor(original,
            listOf(Color(49, 38, 54), Color(64, 50, 69)), Color.Black, Color.White))
    }

    @Test fun faintFallbackIsCheckedAfterAlphaComposition() {
        val result = readablePlayerTextColor(Color.White.copy(alpha = 0.05f),
            listOf(Color.White), Color.Black, Color.White)
        val rendered = result.compositeOver(Color.White).luminance()
        assertTrue((1.0 + 0.05) / (rendered + 0.05) >= 4.5)
    }
}
