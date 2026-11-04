package me.nick.squeezessh.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

fun UiPalette.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Color(primary),
        onPrimary = Color(onPrimary),
        secondary = Color(secondary),
        onSecondary = Color(onSecondary),
        tertiary = Color(tertiary),
        onTertiary = Color(onTertiary),
        error = Color(error),
        onError = Color(onError),
        background = Color(background),
        onBackground = Color(onBackground),
        surface = Color(surface),
        surfaceVariant = Color(surfaceVariant),
        surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh),
        onSurface = Color(onSurface),
        onSurfaceVariant = Color(onSurfaceVariant),
        outline = Color(outline),
    )
}

/** App-wide Material theme derived from a terminal palette. */
@Composable
fun SqueezeTheme(theme: TerminalTheme = BuiltInThemes.default, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ThemeDeriver.derive(theme).toColorScheme(), content = content)
}
