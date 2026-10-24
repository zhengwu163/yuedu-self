package io.legado.app.ui.book.read

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudPlayerPanelColorsTest {
    private val darkSurfaces = listOf(Color(49, 38, 54), Color(64, 50, 69), Color(42, 34, 48))

    private fun contrast(foreground: Color, background: Color): Double {
        val fg = foreground.compositeOver(background).luminance().toDouble()
        val bg = background.luminance().toDouble()
        return (maxOf(fg, bg) + 0.05) / (minOf(fg, bg) + 0.05)
    }

    @Test fun darkenedPlayerDoesNotKeepDayBlackText() {
        for (alpha in listOf(0.94f, 0.88f, 0.66f)) {
            val result = readablePlayerTextColor(Color.Black.copy(alpha = alpha), darkSurfaces, Color.Black, Color.White)
            assertTrue(darkSurfaces.all { contrast(result, it) >= 4.5 })
        }
    }

}
