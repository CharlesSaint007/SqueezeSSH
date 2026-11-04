package me.nick.squeezessh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.session.SessionService
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.terminal.TerminalScreen
import me.nick.squeezessh.ui.SettingsScreen
import me.nick.squeezessh.ui.bitwarden.BitwardenScreen
import me.nick.squeezessh.ui.connect.ConnectScreen
import me.nick.squeezessh.ui.hosts.HostEditScreen
import me.nick.squeezessh.ui.hosts.HostsScreen
import me.nick.squeezessh.ui.identities.IdentitiesScreen
import me.nick.squeezessh.ui.keys.KeysScreen
import me.nick.squeezessh.ui.theme.BuiltInThemes
import me.nick.squeezessh.ui.theme.SqueezeTheme

class MainActivity : ComponentActivity() {
    /** A session a notification asked to open; consumed by the nav graph. */
    private val pendingSessionId = MutableStateFlow<String?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Sessions run either way; without the permission they just have no per-session notifications.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        val app = application as SqueezeApp
        val sessions = app.sessionManager
        val repo = app.settingsRepository
        val vault = app.vault
        val bitwarden = app.bitwarden

        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val scope = rememberCoroutineScope()
            val openSessions by sessions.sessions.collectAsStateWithLifecycle()
            val hosts by vault.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
            val identities by vault.identities.collectAsStateWithLifecycle(initialValue = emptyList())
            val keys by vault.keys.collectAsStateWithLifecycle(initialValue = emptyList())
            val syncStatus by bitwarden.status.collectAsStateWithLifecycle()

            // A quick-connect target isn't saved, so it is handed to the connect screen in memory.
            var quickHost by remember { mutableStateOf<HostEntity?>(null) }

            // Open sessions follow the global terminal theme unless their host overrides it.
            LaunchedEffect(settings.terminalThemeId) { sessions.applyTheme(settings.terminalTheme) }

            SqueezeTheme(settings.uiTheme) {
                val nav = rememberNavController()
                val pending by pendingSessionId.collectAsStateWithLifecycle()
                LaunchedEffect(pending) {
                    val id = pending ?: return@LaunchedEffect
                    pendingSessionId.value = null
                    if (sessions.get(id) != null) {
                        nav.navigate("terminal/$id") {
                            popUpTo("hosts")
                            launchSingleTop = true
                        }
                    }
                }
                NavHost(navController = nav, startDestination = "hosts") {
                    composable("hosts") {
                        HostsScreen(
                            hosts = hosts,
                            identities = identities,
                            sessions = openSessions,
                            onConnect = { host -> nav.navigate("connect/${host.id}") },
                            onQuickConnect = { q ->
                                quickHost = HostEntity(
                                    id = QUICK_ID,
                                    nickname = q.host,
                                    address = q.host,
                                    port = q.port,
                                    username = q.username,
                                )
                                nav.navigate("connect/$QUICK_ID")
                            },
                            onAddHost = { nav.navigate("host/new") },
                            onEditHost = { host -> nav.navigate("host/${host.id}") },
                            onOpenSession = { id -> nav.navigate("terminal/$id") },
                            onCloseSession = { id -> sessions.close(id) },
                            onLocalShell = {
                                val entry = sessions.openLocalShell(settings.terminalTheme)
                                onSessionStarted()
                                nav.navigate("terminal/${entry.id}")
                            },
                            onIdentities = { nav.navigate("identities") },
                            onKeys = { nav.navigate("keys") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("host/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id")
                        val existing = hosts.firstOrNull { it.id == id }
                        // Wait for the hosts list before showing an edit form, so it isn't filled in empty.
                        if (id == "new" || existing != null) {
                            HostEditScreen(
                                existing = existing,
                                allHosts = hosts,
                                identities = identities,
                                onSave = { host -> scope.launch { vault.saveHost(host) }; nav.back() },
                                onDelete = { host -> scope.launch { vault.deleteHost(host) }; nav.back() },
                                onManageIdentities = { nav.navigate("identities") },
                                onBack = { nav.back() },
                            )
                        }
                    }
                    composable("connect/{id}") { backStack ->
                        val id = backStack.arguments?.getString("id")
                        val host by produceState<HostEntity?>(null, id) {
                            value = if (id == QUICK_ID) quickHost else id?.let { vault.host(it) }
                        }
                        host?.let { h ->
                            ConnectScreen(
                                host = h,
                                connections = app.connections,
                                onConnected = { backend ->
                                    val override = h.themeId?.let(BuiltInThemes::byId)
                                    val entry = sessions.open(
                                        label = h.nickname,
                                        backend = backend,
                                        theme = override ?: settings.terminalTheme,
                                        themeOverridden = override != null,
                                    )
                                    onSessionStarted()
                                    nav.navigate("terminal/${entry.id}") { popUpTo("hosts") }
                                },
                                onBack = { nav.back() },
                            )
                        }
                    }
                    composable("terminal/{id}") { backStack ->
                        val entry = backStack.arguments?.getString("id")?.let(sessions::get)
                        if (entry == null) {
                            LaunchedEffect(Unit) { nav.back() }
                        } else {
                            TerminalScreen(
                                entry = entry,
                                sessionManager = sessions,
                                fontSizeSp = settings.fontSizeSp,
                                onFontSizeChange = { sp -> scope.launch { repo.setFontSize(sp) } },
                                volumeKeysFontSize = settings.volumeKeysFontSize,
                                exitBehavior = settings.exitBehavior,
                                onRememberExitBehavior = { b -> scope.launch { repo.setExitBehavior(b) } },
                                onLeave = { keepRunning ->
                                    if (!keepRunning) sessions.close(entry.id)
                                    nav.back()
                                },
                            )
                        }
                    }
                    composable("identities") {
                        IdentitiesScreen(
                            identities = identities,
                            keys = keys,
                            onSave = { identity, password -> scope.launch { vault.saveIdentity(identity, password) } },
                            onDelete = { identity -> scope.launch { vault.deleteIdentity(identity) } },
                            onKeys = { nav.navigate("keys") },
                            onBack = { nav.back() },
                        )
                    }
                    composable("keys") {
                        KeysScreen(
                            keys = keys,
                            vault = vault,
                            bitwarden = bitwarden,
                            syncStatus = syncStatus,
                            onOpenBitwarden = { nav.navigate("bitwarden") },
                            onBack = { nav.back() },
                        )
                    }
                    composable("bitwarden") {
                        BitwardenScreen(sync = bitwarden, status = syncStatus, onBack = { nav.back() })
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = settings,
                            onTerminalTheme = { id -> scope.launch { repo.setTerminalTheme(id) } },
                            onUiThemeMode = { mode -> scope.launch { repo.setUiThemeMode(mode) } },
                            onUiTheme = { id -> scope.launch { repo.setUiTheme(id) } },
                            onFontSize = { sp -> scope.launch { repo.setFontSize(sp) } },
                            onExitBehavior = { b -> scope.launch { repo.setExitBehavior(b) } },
                            onKeepAwake = { on -> scope.launch { repo.setKeepAwake(on) } },
                            onVolumeKeysFontSize = { on -> scope.launch { repo.setVolumeKeysFontSize(on) } },
                            onBitwarden = { nav.navigate("bitwarden") },
                            onBack = { nav.back() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == SessionService.ACTION_OPEN_SESSION) {
            pendingSessionId.value = intent.getStringExtra(SessionService.EXTRA_SESSION_ID)
        }
    }

    /**
     * A session just opened: start the foreground service that keeps it alive in the background, and on
     * Android 13+ ask (once) for permission to show its notification.
     */
    private fun onSessionStarted() {
        SessionService.ensureRunning(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val QUICK_ID = "quick"
    }
}

/**
 * Go back one screen, but never pop the start destination: a quick double tap on a back arrow would otherwise
 * empty the back stack and leave a blank window.
 */
private fun NavHostController.back() {
    if (previousBackStackEntry != null) popBackStack()
}
