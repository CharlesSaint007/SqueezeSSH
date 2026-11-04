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
import me.nick.squeezessh.ui.theme.BuiltInThemes
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
) {
    val terminalTheme: TerminalTheme get() = BuiltInThemes.byId(terminalThemeId)

    /** The palette the app UI is drawn with, after applying [uiThemeMode]. */
    val uiTheme: TerminalTheme
        get() = when (uiThemeMode) {
            UiThemeMode.FollowTerminal -> terminalTheme
            UiThemeMode.Separate -> BuiltInThemes.byId(uiThemeId)
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

    private companion object {
        val TERMINAL_THEME = stringPreferencesKey("terminal_theme")
        val UI_THEME_MODE = stringPreferencesKey("ui_theme_mode")
        val UI_THEME = stringPreferencesKey("ui_theme")
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val EXIT_BEHAVIOR = stringPreferencesKey("exit_behavior")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
    }
}
