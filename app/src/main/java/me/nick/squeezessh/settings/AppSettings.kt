package me.nick.squeezessh.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.nick.squeezessh.ui.theme.BuiltInThemes
import me.nick.squeezessh.ui.theme.CustomThemeJson
import me.nick.squeezessh.ui.theme.ThemeCatalog
import me.nick.squeezessh.ui.theme.TerminalTheme

/** How the app UI picks its colors. */
enum class UiThemeMode {
    /** The app UI uses the terminal theme's palette. */
    FollowTerminal,

    /** The app UI uses its own theme, picked separately. */
    Separate,
}

/** What leaving a running session's screen does. */
enum class ExitBehavior {
    /** Ask each time: keep running in the background, or disconnect. */
    Ask,
    KeepRunning,
    Disconnect,
}

data class Settings(
    val terminalThemeId: String = BuiltInThemes.default.id,
    val uiThemeMode: UiThemeMode = UiThemeMode.FollowTerminal,
    val uiThemeId: String = BuiltInThemes.default.id,
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    val exitBehavior: ExitBehavior = ExitBehavior.Ask,
    /** Hold a partial wake lock while sessions run, so keepalives are sent with the screen off. */
    val keepAwake: Boolean = false,
    /** In a terminal, the volume keys change the font size instead of the volume. */
    val volumeKeysFontSize: Boolean = true,
    /** Reconnect by itself when a session's connection drops while its screen is open. */
    val autoReconnect: Boolean = true,
    /** Quick-connect targets (`user@host:port`), newest first. */
    val recentQuickConnects: List<String> = emptyList(),
    /** Themes made in the theme editor. */
    val customThemes: List<TerminalTheme> = emptyList(),
) {
    val themes: ThemeCatalog get() = ThemeCatalog(customThemes)
    val terminalTheme: TerminalTheme get() = themes.byId(terminalThemeId)

    /** The palette the app UI is drawn with, after applying [uiThemeMode]. */
    val uiTheme: TerminalTheme
        get() = when (uiThemeMode) {
            UiThemeMode.FollowTerminal -> terminalTheme
            UiThemeMode.Separate -> themes.byId(uiThemeId)
        }

    companion object {
        const val DEFAULT_FONT_SIZE_SP = 14
        const val MIN_FONT_SIZE_SP = 6
        const val MAX_FONT_SIZE_SP = 32
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            terminalThemeId = p[TERMINAL_THEME] ?: BuiltInThemes.default.id,
            uiThemeMode = p[UI_THEME_MODE]?.let { runCatching { UiThemeMode.valueOf(it) }.getOrNull() }
                ?: UiThemeMode.FollowTerminal,
            uiThemeId = p[UI_THEME] ?: BuiltInThemes.default.id,
            fontSizeSp = p[FONT_SIZE] ?: Settings.DEFAULT_FONT_SIZE_SP,
            exitBehavior = p[EXIT_BEHAVIOR]?.let { runCatching { ExitBehavior.valueOf(it) }.getOrNull() }
                ?: ExitBehavior.Ask,
            keepAwake = p[KEEP_AWAKE] ?: false,
            volumeKeysFontSize = p[VOLUME_KEYS_FONT] ?: true,
            autoReconnect = p[AUTO_RECONNECT] ?: true,
            recentQuickConnects = p[RECENT_QUICK]?.split('\n')?.filter { it.isNotBlank() }.orEmpty(),
            customThemes = p[CUSTOM_THEMES]?.let(::decodeThemes).orEmpty(),
        )
    }

    suspend fun setTerminalTheme(id: String) = store.edit { it[TERMINAL_THEME] = id }
    suspend fun setUiThemeMode(mode: UiThemeMode) = store.edit { it[UI_THEME_MODE] = mode.name }
    suspend fun setUiTheme(id: String) = store.edit { it[UI_THEME] = id }
    suspend fun setFontSize(sp: Int) = store.edit {
        it[FONT_SIZE] = sp.coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP)
    }

    suspend fun setExitBehavior(behavior: ExitBehavior) = store.edit { it[EXIT_BEHAVIOR] = behavior.name }
    suspend fun setKeepAwake(enabled: Boolean) = store.edit { it[KEEP_AWAKE] = enabled }
    suspend fun setVolumeKeysFontSize(enabled: Boolean) = store.edit { it[VOLUME_KEYS_FONT] = enabled }
    suspend fun setAutoReconnect(enabled: Boolean) = store.edit { it[AUTO_RECONNECT] = enabled }

    /** Move [target] to the front of the quick-connect history, keeping the last [MAX_RECENT_QUICK]. */
    suspend fun rememberQuickConnect(target: String) = store.edit {
        val current = it[RECENT_QUICK]?.split('\n')?.filter(String::isNotBlank).orEmpty()
        it[RECENT_QUICK] = withRecent(current, target).joinToString("\n")
    }

    suspend fun forgetQuickConnect(target: String) = store.edit {
        val current = it[RECENT_QUICK]?.split('\n')?.filter(String::isNotBlank).orEmpty()
        it[RECENT_QUICK] = current.filter { t -> t != target }.joinToString("\n")
    }

    /** Add or update a custom theme. */
    suspend fun saveCustomTheme(theme: TerminalTheme) = store.edit {
        val list = it[CUSTOM_THEMES]?.let(::decodeThemes).orEmpty()
        val updated = if (list.any { t -> t.id == theme.id }) list.map { t -> if (t.id == theme.id) theme else t } else list + theme
        it[CUSTOM_THEMES] = encodeThemes(updated)
    }

    /** Delete a custom theme; anything that used it falls back to the default theme. */
    suspend fun deleteCustomTheme(id: String) = store.edit {
        it[CUSTOM_THEMES] = encodeThemes(it[CUSTOM_THEMES]?.let(::decodeThemes).orEmpty().filter { t -> t.id != id })
        if (it[TERMINAL_THEME] == id) it[TERMINAL_THEME] = BuiltInThemes.default.id
        if (it[UI_THEME] == id) it[UI_THEME] = BuiltInThemes.default.id
    }

    /** Replace every setting at once (restoring a backup). */
    suspend fun replace(s: Settings) = store.edit {
        it[TERMINAL_THEME] = s.terminalThemeId
        it[UI_THEME_MODE] = s.uiThemeMode.name
        it[UI_THEME] = s.uiThemeId
        it[FONT_SIZE] = s.fontSizeSp.coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP)
        it[EXIT_BEHAVIOR] = s.exitBehavior.name
        it[KEEP_AWAKE] = s.keepAwake
        it[VOLUME_KEYS_FONT] = s.volumeKeysFontSize
        it[AUTO_RECONNECT] = s.autoReconnect
        it[CUSTOM_THEMES] = encodeThemes(s.customThemes)
    }

    internal companion object {
        val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val UI_THEME_MODE = stringPreferencesKey("ui_theme_mode")
        val UI_THEME = stringPreferencesKey("ui_theme")
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val EXIT_BEHAVIOR = stringPreferencesKey("exit_behavior")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val VOLUME_KEYS_FONT = booleanPreferencesKey("volume_keys_font_size")
        val RECENT_QUICK = stringPreferencesKey("recent_quick_connects")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        private val themeJson = Json { ignoreUnknownKeys = true }

        fun encodeThemes(themes: List<TerminalTheme>): String =
            themeJson.encodeToString(ListSerializer(CustomThemeJson.serializer()), themes.map(CustomThemeJson::of))

        /** Themes that fail to parse are skipped rather than losing the whole list. */
        fun decodeThemes(text: String): List<TerminalTheme> =
            runCatching { themeJson.decodeFromString(ListSerializer(CustomThemeJson.serializer()), text) }
                .getOrDefault(emptyList()).mapNotNull { it.toTheme() }
        const val MAX_RECENT_QUICK = 5

        /** [target] moved to the front of [history], without duplicates, capped at [MAX_RECENT_QUICK]. */
        fun withRecent(history: List<String>, target: String): List<String> =
            (listOf(target) + history.filter { it != target }).take(MAX_RECENT_QUICK)
    }
}
