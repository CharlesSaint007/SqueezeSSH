package me.nick.squeezessh

import me.nick.squeezessh.backup.SettingsBackup
import me.nick.squeezessh.backup.toSettings
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.settings.SettingsRepository
import me.nick.squeezessh.ui.hosts.QuickConnect
import me.nick.squeezessh.ui.theme.BuiltInThemes
import me.nick.squeezessh.ui.theme.CustomThemeJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomThemeTest {
    private val custom = BuiltInThemes.GruvboxDark.copy(id = CustomThemeJson.ID_PREFIX + "1", name = "Mine", background = 0xFF101010.toInt())

    @Test
    fun customThemesRoundTripThroughSettingsStorage() {
        val text = SettingsRepository.encodeThemes(listOf(custom))
        assertEquals(listOf(custom), SettingsRepository.decodeThemes(text))
    }

    @Test
    fun brokenStoredThemesAreSkippedNotFatal() {
        assertEquals(emptyList<Any>(), SettingsRepository.decodeThemes("not json"))
        val oneBad = """[{"id":"custom-x","name":"x","foreground":"#zzz","background":"#000","cursor":"#fff","selection":"#111","ansi":[]}]"""
        assertEquals(emptyList<Any>(), SettingsRepository.decodeThemes(oneBad))
    }

    @Test
    fun settingsResolveCustomThemesAndFallBackWhenDeleted() {
        val s = Settings(terminalThemeId = custom.id, customThemes = listOf(custom))
        assertEquals(custom, s.terminalTheme)
        assertEquals(BuiltInThemes.default, s.copy(customThemes = emptyList()).terminalTheme)
    }

    @Test
    fun hexParsing() {
        assertEquals(0xFF112233.toInt(), CustomThemeJson.parseHex("#112233"))
        assertEquals(0xFFAABBCC.toInt(), CustomThemeJson.parseHex("abc"))
        assertEquals("#0A0B0C", CustomThemeJson.toHex(0xFF0A0B0C.toInt()))
        assertThrows(IllegalArgumentException::class.java) { CustomThemeJson.parseHex("#12345") }
    }

    @Test
    fun backupMergeKeepsLocalThemesAndPrefersTheFilesCopy() {
        val local = custom.copy(id = CustomThemeJson.ID_PREFIX + "2", name = "Local only")
        val current = Settings(customThemes = listOf(custom.copy(name = "Old name"), local))
        val restored = SettingsBackup(customThemes = listOf(CustomThemeJson.of(custom))).toSettings(current)
        assertEquals(listOf("Mine", "Local only"), restored.customThemes.map { it.name })
    }

    @Test
    fun quickConnectHistoryMovesToFrontWithoutDuplicates() {
        val h = listOf("a@1", "b@2", "c@3")
        assertEquals(listOf("b@2", "a@1", "c@3"), SettingsRepository.withRecent(h, "b@2"))
        val full = (1..5).map { "u@h$it" }
        assertEquals(listOf("new@h") + full.take(4), SettingsRepository.withRecent(full, "new@h"))
    }

    @Test
    fun quickConnectFormatRoundTrips() {
        for (text in listOf("pi@pi5", "root@10.0.0.1:2222", "host", "u@[fe80::1]:22")) {
            val parsed = QuickConnect.parse(text)!!
            assertEquals(parsed, QuickConnect.parse(parsed.format()))
        }
        assertEquals("u@[fe80::1]", QuickConnect.parse("u@[fe80::1]:22")!!.format())
        assertNull(QuickConnect.parse("bad host"))
    }
}
