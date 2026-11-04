package me.nickreed.squeezessh.ui.theme

import me.nickreed.squeezessh.ui.theme.ColorMath.blend
import me.nickreed.squeezessh.ui.theme.ColorMath.isDark
import me.nickreed.squeezessh.ui.theme.ColorMath.onColor

/**
 * The app-UI palette derived from a [TerminalTheme], as plain ARGB ints. [SqueezeTheme] turns it
 * into a Material 3 ColorScheme; keeping the mapping here keeps it testable without Compose.
 */
data class UiPalette(
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val error: Int,
    val onError: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
)

object ThemeDeriver {
    fun derive(theme: TerminalTheme): UiPalette {
        val bg = theme.background
        val fg = theme.foreground
        val dark = isDark(bg)
        // Tonal surface steps: lighten on dark themes, darken on light themes.
        val toward = if (dark) ColorMath.WHITE else ColorMath.BLACK
        fun tone(amount: Float) = blend(bg, toward, amount)

        return UiPalette(
            isDark = dark,
            primary = theme.blue,
            onPrimary = onColor(theme.blue),
            secondary = theme.magenta,
            onSecondary = onColor(theme.magenta),
            tertiary = theme.cyan,
            onTertiary = onColor(theme.cyan),
            error = theme.red,
            onError = onColor(theme.red),
            background = bg,
            onBackground = fg,
            surface = bg,
            surfaceVariant = tone(0.10f),
            surfaceContainer = tone(0.05f),
            surfaceContainerHigh = tone(0.09f),
            onSurface = fg,
            onSurfaceVariant = blend(fg, bg, 0.25f),
            outline = blend(fg, bg, 0.55f),
        )
    }
}
