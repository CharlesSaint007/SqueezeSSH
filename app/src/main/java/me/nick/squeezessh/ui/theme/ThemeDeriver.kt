package me.nick.squeezessh.ui.theme

import me.nick.squeezessh.ui.theme.ColorMath.blend
import me.nick.squeezessh.ui.theme.ColorMath.isDark
import me.nick.squeezessh.ui.theme.ColorMath.onColor

/**
 * The app-UI palette derived from a [TerminalTheme], as plain ARGB ints. [SqueezeTheme] turns it
 * into a Material 3 ColorScheme; keeping the mapping here keeps it testable without Compose.
 */
data class UiPalette(
    val isDark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val inverseSurface: Int,
    val inverseOnSurface: Int,
    val inversePrimary: Int,
    val outline: Int,
    val outlineVariant: Int,
)

object ThemeDeriver {
    fun derive(theme: TerminalTheme): UiPalette {
        val bg = theme.background
        val fg = theme.foreground
        val dark = isDark(bg)
        // Tonal surface steps: lighten on dark themes, darken on light themes.
        val toward = if (dark) ColorMath.WHITE else ColorMath.BLACK
        fun tone(amount: Float) = blend(bg, toward, amount)

        // Containers (FABs, selected chips and segments, switch tracks) are the accent mixed into the background,
        // so they read as tinted surfaces rather than solid accent blocks.
        fun container(accent: Int) = blend(bg, accent, if (dark) 0.35f else 0.25f)
        // Text on a container: the accent lifted toward the foreground when that is readable, else black/white.
        fun onContainer(accent: Int): Int {
            val c = container(accent)
            val tinted = blend(accent, fg, 0.5f)
            return if (ColorMath.contrast(tinted, c) >= 4.5) tinted else onColor(c)
        }

        return UiPalette(
            isDark = dark,
            primary = theme.blue,
            onPrimary = onColor(theme.blue),
            primaryContainer = container(theme.blue),
            onPrimaryContainer = onContainer(theme.blue),
            secondary = theme.magenta,
            onSecondary = onColor(theme.magenta),
            secondaryContainer = container(theme.magenta),
            onSecondaryContainer = onContainer(theme.magenta),
            tertiary = theme.cyan,
            onTertiary = onColor(theme.cyan),
            tertiaryContainer = container(theme.cyan),
            onTertiaryContainer = onContainer(theme.cyan),
            error = theme.red,
            onError = onColor(theme.red),
            errorContainer = container(theme.red),
            onErrorContainer = onContainer(theme.red),
            background = bg,
            onBackground = fg,
            surface = bg,
            surfaceVariant = tone(0.10f),
            surfaceContainerLowest = tone(0.0f),
            surfaceContainerLow = tone(0.03f),
            surfaceContainer = tone(0.05f),
            surfaceContainerHigh = tone(0.09f),
            surfaceContainerHighest = tone(0.13f),
            onSurface = fg,
            onSurfaceVariant = blend(fg, bg, 0.25f),
            inverseSurface = fg,
            inverseOnSurface = bg,
            inversePrimary = blend(theme.blue, if (dark) ColorMath.BLACK else ColorMath.WHITE, 0.4f),
            outline = blend(fg, bg, 0.55f),
            outlineVariant = blend(fg, bg, 0.8f),
        )
    }
}
