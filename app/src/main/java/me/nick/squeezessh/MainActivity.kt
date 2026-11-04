package me.nick.squeezessh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.terminal.TerminalScreen
import me.nick.squeezessh.ui.HomeScreen
import me.nick.squeezessh.ui.SettingsScreen
import me.nick.squeezessh.ui.theme.SqueezeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as SqueezeApp
        val sessions = app.sessionManager
        val repo = app.settingsRepository

        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val scope = rememberCoroutineScope()
            val openSessions by sessions.sessions.collectAsStateWithLifecycle()

            // Open sessions follow the global terminal theme.
            LaunchedEffect(settings.terminalThemeId) { sessions.applyTheme(settings.terminalTheme) }

            SqueezeTheme(settings.uiTheme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            sessions = openSessions,
                            onOpenLocalShell = {
                                val entry = sessions.openLocalShell(settings.terminalTheme)
                                nav.navigate("terminal/${entry.id}")
                            },
                            onOpenSession = { id -> nav.navigate("terminal/$id") },
                            onCloseSession = { id -> sessions.close(id) },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("terminal/{id}") { backStack ->
                        val entry = backStack.arguments?.getString("id")?.let(sessions::get)
                        if (entry == null) {
                            LaunchedEffect(Unit) { nav.popBackStack() }
                        } else {
                            TerminalScreen(
                                entry = entry,
                                sessionManager = sessions,
                                fontSizeSp = settings.fontSizeSp,
                                onFontSizeChange = { sp -> scope.launch { repo.setFontSize(sp) } },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            onTerminalTheme = { id -> scope.launch { repo.setTerminalTheme(id) } },
                            onUiThemeMode = { mode -> scope.launch { repo.setUiThemeMode(mode) } },
                            onUiTheme = { id -> scope.launch { repo.setUiTheme(id) } },
                            onFontSize = { sp -> scope.launch { repo.setFontSize(sp) } },
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
