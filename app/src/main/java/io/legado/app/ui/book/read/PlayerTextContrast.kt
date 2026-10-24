package io.legado.app.ui.book.read

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.legado.app.utils.MIN_FONT_SURFACE_CONTRAST

/** Recheck authored text after the player derives its own darker/lighter surfaces. */
internal fun readablePlayerTextColor(
    preferred: Color,
    surfaces: List<Color>,
    dayText: Color,
    nightText: Color
): Color {
    require(surfaces.isNotEmpty())
    fun minimumContrast(text: Color): Double = surfaces.minOf { surface ->
        val background = surface.copy(alpha = 1f)
        val fg = text.compositeOver(background).luminance().toDouble()
        val bg = background.luminance().toDouble()
        (maxOf(fg, bg) + 0.05) / (minOf(fg, bg) + 0.05)
    }
    if (minimumContrast(preferred) >= MIN_FONT_SURFACE_CONTRAST) return preferred
    val translucent = listOf(dayText, nightText).map { it.copy(alpha = preferred.alpha) }
        .maxBy { minimumContrast(it) }
    if (minimumContrast(translucent) >= MIN_FONT_SURFACE_CONTRAST) return translucent
    // Very faint authored alpha must not make the fallback unreadable as well.
    return listOf(dayText, nightText).maxBy { minimumContrast(it) }
}
