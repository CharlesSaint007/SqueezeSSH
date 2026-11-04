package me.nickreed.squeezessh

import me.nickreed.squeezessh.ui.theme.BuiltInThemes
import me.nickreed.squeezessh.ui.theme.ColorMath
import me.nickreed.squeezessh.ui.theme.ThemeDeriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeDeriverTest {
    @Test
    fun darkAndLightAreDetectedFromBackground() {
        assertTrue(ThemeDeriver.derive(BuiltInThemes.GruvboxDark).isDark)
        assertTrue(ThemeDeriver.derive(BuiltInThemes.SolarizedDark).isDark)
        assertTrue(ThemeDeriver.derive(BuiltInThemes.TokyoNight).isDark)
        assertTrue(ThemeDeriver.derive(BuiltInThemes.DefaultDark).isDark)
        assertFalse(ThemeDeriver.derive(BuiltInThemes.DefaultLight).isDark)
    }

    @Test
    fun bodyTextIsReadableOnEveryBuiltInTheme() {
        // WCAG AA for normal text is 4.5:1.
        for (theme in BuiltInThemes.all) {
            val p = ThemeDeriver.derive(theme)
            val ratio = ColorMath.contrast(p.onSurface, p.surface)
            assertTrue("${theme.name} onSurface/surface contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun onPrimaryIsReadableOnEveryBuiltInTheme() {
        // Buttons/labels: require at least 3:1 (WCAG AA large text / UI components).
        for (theme in BuiltInThemes.all) {
            val p = ThemeDeriver.derive(theme)
            val ratio = ColorMath.contrast(p.onPrimary, p.primary)
            assertTrue("${theme.name} onPrimary/primary contrast $ratio < 3.0", ratio >= 3.0)
        }
    }

    @Test
    fun primaryComesFromTerminalBlue() {
        val theme = BuiltInThemes.GruvboxDark
        assertEquals(theme.blue, ThemeDeriver.derive(theme).primary)
    }

    @Test
    fun unknownThemeIdFallsBackToDefault() {
        assertEquals(BuiltInThemes.default, BuiltInThemes.byId("nope"))
        assertEquals(BuiltInThemes.default, BuiltInThemes.byId(null))
    }
}
