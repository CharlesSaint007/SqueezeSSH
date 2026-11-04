package me.nick.squeezessh

import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.settings.UiThemeMode
import me.nick.squeezessh.ui.theme.BuiltInThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsTest {
    @Test
    fun defaultsAskOnExitAndDoNotHoldWakeLocks() {
        val s = Settings()
        assertEquals(ExitBehavior.Ask, s.exitBehavior)
        assertFalse(s.keepAwake)
    }

    @Test
    fun uiThemeFollowsTerminalUnlessSeparate() {
        val follow = Settings(terminalThemeId = BuiltInThemes.GruvboxDark.id, uiThemeId = BuiltInThemes.DefaultLight.id)
        assertEquals(BuiltInThemes.GruvboxDark, follow.uiTheme)

        val separate = follow.copy(uiThemeMode = UiThemeMode.Separate)
        assertEquals(BuiltInThemes.DefaultLight, separate.uiTheme)
        assertEquals(BuiltInThemes.GruvboxDark, separate.terminalTheme)
    }
}
