package me.nick.squeezessh.settings

import me.nick.squeezessh.terminal.KeyRepeatConfig
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
    /** Ask for a fingerprint / screen lock when the app is opened (and again after it's been in the background). */
    val requireBiometricToOpen: Boolean = false,
    /** Show hosts under their group, collapsible by tapping the group name. Independent of the sort choice. */
    val groupHosts: Boolean = false,
    /** Keys ([me.nick.squeezessh.ui.hosts.HostGrouping.keyOf]) of the groups the user has collapsed. */
    val collapsedGroups: Set<String> = emptySet(),
    /** Holding a key-bar key repeats it, like a hardware keyboard. */
    val keyRepeat: Boolean = true,
    /** How long a key-bar key is held before it starts repeating. */
    val keyRepeatDelayMs: Int = DEFAULT_REPEAT_DELAY_MS,
    /** Repeated keystrokes per second once repeating. */
    val keyRepeatPerSecond: Int = DEFAULT_REPEAT_PER_SECOND,
    /** Themes made in the theme editor. */
    val customThemes: List<TerminalTheme> = emptyList(),
) {
    val keyRepeatConfig: KeyRepeatConfig get() = KeyRepeatConfig(keyRepeat, keyRepeatDelayMs, keyRepeatPerSecond)
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
        const val DEFAULT_REPEAT_DELAY_MS = 400
        const val MIN_REPEAT_DELAY_MS = 150
        const val MAX_REPEAT_DELAY_MS = 1000
        const val DEFAULT_REPEAT_PER_SECOND = 20
        const val MIN_REPEAT_PER_SECOND = 5
        const val MAX_REPEAT_PER_SECOND = 40
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
            requireBiometricToOpen = p[REQUIRE_BIOMETRIC] ?: false,
            keyRepeat = p[KEY_REPEAT] ?: true,
            keyRepeatDelayMs = (p[KEY_REPEAT_DELAY] ?: Settings.DEFAULT_REPEAT_DELAY_MS)
                .coerceIn(Settings.MIN_REPEAT_DELAY_MS, Settings.MAX_REPEAT_DELAY_MS),
            keyRepeatPerSecond = (p[KEY_REPEAT_RATE] ?: Settings.DEFAULT_REPEAT_PER_SECOND)
                .coerceIn(Settings.MIN_REPEAT_PER_SECOND, Settings.MAX_REPEAT_PER_SECOND),
            groupHosts = p[GROUP_HOSTS] ?: false,
            collapsedGroups = p[COLLAPSED_GROUPS] ?: emptySet(),
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
    suspend fun setKeyRepeat(enabled: Boolean) = store.edit { it[KEY_REPEAT] = enabled }
    suspend fun setKeyRepeatDelay(ms: Int) = store.edit {
        it[KEY_REPEAT_DELAY] = ms.coerceIn(Settings.MIN_REPEAT_DELAY_MS, Settings.MAX_REPEAT_DELAY_MS)
    }
    suspend fun setKeyRepeatRate(perSecond: Int) = store.edit {
        it[KEY_REPEAT_RATE] = perSecond.coerceIn(Settings.MIN_REPEAT_PER_SECOND, Settings.MAX_REPEAT_PER_SECOND)
    }
    suspend fun setGroupHosts(enabled: Boolean) = store.edit { it[GROUP_HOSTS] = enabled }

    /** Collapse or expand one group of the host list. */
    suspend fun setGroupCollapsed(key: String, collapsed: Boolean) = store.edit {
        val current = it[COLLAPSED_GROUPS] ?: emptySet()
        it[COLLAPSED_GROUPS] = if (collapsed) current + key else current - key
    }
    suspend fun setRequireBiometricToOpen(enabled: Boolean) = store.edit { it[REQUIRE_BIOMETRIC] = enabled }

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
        it[KEY_REPEAT] = s.keyRepeat
        it[KEY_REPEAT_DELAY] = s.keyRepeatDelayMs.coerceIn(Settings.MIN_REPEAT_DELAY_MS, Settings.MAX_REPEAT_DELAY_MS)
        it[KEY_REPEAT_RATE] = s.keyRepeatPerSecond.coerceIn(Settings.MIN_REPEAT_PER_SECOND, Settings.MAX_REPEAT_PER_SECOND)
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
        val KEY_REPEAT = booleanPreferencesKey("key_repeat")
        val KEY_REPEAT_DELAY = intPreferencesKey("key_repeat_delay_ms")
        val KEY_REPEAT_RATE = intPreferencesKey("key_repeat_per_second")
        val GROUP_HOSTS = booleanPreferencesKey("group_hosts")
        val COLLAPSED_GROUPS = stringSetPreferencesKey("collapsed_groups")
        val REQUIRE_BIOMETRIC = booleanPreferencesKey("require_biometric_to_open")
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
