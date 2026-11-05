package me.nick.squeezessh

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.withResumed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.security.AppLock
import me.nick.squeezessh.security.AuthResult
import me.nick.squeezessh.security.AuthRules
import me.nick.squeezessh.security.Biometrics
import me.nick.squeezessh.session.SessionService
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.terminal.TerminalScreen
import me.nick.squeezessh.ui.HostShortcuts
import me.nick.squeezessh.ui.SettingsScreen
import me.nick.squeezessh.ui.backup.BackupScreen
import me.nick.squeezessh.ui.bitwarden.BitwardenScreen
import me.nick.squeezessh.ui.connect.ConnectScreen
import me.nick.squeezessh.ui.hosts.HostEditScreen
import me.nick.squeezessh.ui.hosts.HostsScreen
import me.nick.squeezessh.ui.hosts.QuickConnect
import me.nick.squeezessh.ui.identities.IdentitiesScreen
import me.nick.squeezessh.ui.keys.KeysScreen
import me.nick.squeezessh.ui.security.LockScreen
import me.nick.squeezessh.ui.snippets.SnippetsScreen
import me.nick.squeezessh.ui.theme.BuiltInThemes
import me.nick.squeezessh.ui.theme.SqueezeTheme
import me.nick.squeezessh.ui.theme.ThemeEditorScreen

class MainActivity : FragmentActivity() {
    /** A session a notification asked to open; consumed by the nav graph. */
    private val pendingSessionId = MutableStateFlow<String?>(null)

    /** A host a launcher shortcut asked to connect to; consumed by the nav graph. */
    private val pendingHostId = MutableStateFlow<String?>(null)

    /**
     * Ask the owner to authenticate before something protected, unless [protected] is false or the phone has
     * nothing to authenticate with (then the owner must not be trapped). True means "go ahead".
     */
    private suspend fun authorize(protected: Boolean, title: String): Boolean {
        if (!AuthRules.requiresAuth(protected, Biometrics.isAvailable(this))) return true
        return Biometrics.authenticate(this, title) is AuthResult.Success
    }

    /** Whether the app lock has been opened since it last locked. Only matters when the lock is turned on. */
    private var unlocked by mutableStateOf(false)

    /** Mirrors the setting so the lifecycle callbacks (outside Compose) can see it. */
    @Volatile
    private var appLockEnabled = false
    private var backgroundedAt: Long? = null

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Locks again once the app has been out of sight for a while, not on every short trip away (the
        // fingerprint prompt itself can pause the activity on older Android versions).
        if (appLockEnabled && AppLock.shouldRelock(backgroundedAt, SystemClock.elapsedRealtime())) unlocked = false
        backgroundedAt = null
    }

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
            val loadedSettings by repo.settings.collectAsStateWithLifecycle<Settings?>(initialValue = null)
            val settings = loadedSettings ?: Settings()
            SideEffect { appLockEnabled = loadedSettings?.requireBiometricToOpen == true }
            // With the app lock on, keep the app out of screenshots and the recent-apps thumbnail.
            SideEffect {
                if (appLockEnabled) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            // Until the settings have loaded we can't know whether to lock, so show nothing rather than flash the app.
            val locked = loadedSettings == null || (loadedSettings?.requireBiometricToOpen == true && !unlocked)
            var lockMessage by remember { mutableStateOf<String?>(null) }
            var lockUnavailable by remember { mutableStateOf(false) }
            var unlockRequest by remember { mutableIntStateOf(0) }
            val settingsLoaded = loadedSettings != null
            // Keyed on settingsLoaded too: `locked` is already true before the settings arrive, so without it this
            // would not run again once they do, and the prompt wouldn't appear until the Unlock button was tapped.
            LaunchedEffect(locked, settingsLoaded, unlockRequest) {
                if (!locked || !settingsLoaded) return@LaunchedEffect
                // Wait until the app is in front: asked for earlier, the system prompt is dropped.
                lifecycle.withResumed { }
                lockUnavailable = false
                when (val r = Biometrics.authenticate(this@MainActivity, "Unlock SqueezeSSH")) {
                    AuthResult.Success -> { unlocked = true; lockMessage = null }
                    AuthResult.Cancelled -> lockMessage = null
                    AuthResult.Unavailable -> lockUnavailable = true
                    is AuthResult.Failed -> lockMessage = r.message
                }
            }
            val scope = rememberCoroutineScope()
            val openSessions by sessions.sessions.collectAsStateWithLifecycle()
            val hosts by vault.hosts.collectAsStateWithLifecycle(initialValue = emptyList())
            val identities by vault.identities.collectAsStateWithLifecycle(initialValue = emptyList())
            val keys by vault.keys.collectAsStateWithLifecycle(initialValue = emptyList())
            val syncStatus by bitwarden.status.collectAsStateWithLifecycle()
            val snippets by vault.snippets.collectAsStateWithLifecycle(initialValue = emptyList())
            val knownHostCount by vault.knownHostCount.collectAsStateWithLifecycle(initialValue = 0)

            // A quick-connect target isn't saved, so it is handed to the connect screen in memory.
            var quickHost by remember { mutableStateOf<HostEntity?>(null) }

            // Open sessions follow the global terminal theme unless their host overrides it.
            // Keyed on the theme itself, so editing the current custom theme's colors updates sessions too.
            LaunchedEffect(settings.terminalTheme) { sessions.applyTheme(settings.terminalTheme) }

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
                val pendingHost by pendingHostId.collectAsStateWithLifecycle()
                LaunchedEffect(pendingHost) {
                    val id = pendingHost ?: return@LaunchedEffect
                    // Look the host up before clearing the request: clearing it restarts this effect, which
                    // would cancel the lookup half way.
                    val exists = vault.host(id) != null
                    pendingHostId.value = null
                    if (exists) {
                        nav.navigate("connect/$id") {
                            popUpTo("hosts")
                            launchSingleTop = true
                        }
                    }
                }
                // Keep the launcher's recent-host shortcuts in step with the host list.
                LaunchedEffect(hosts) { HostShortcuts.update(this@MainActivity, hosts) }
                NavHost(navController = nav, startDestination = "hosts") {
                    composable("hosts") {
                        HostsScreen(
                            hosts = hosts,
                            identities = identities,
                            sessions = openSessions,
                            recentQuick = settings.recentQuickConnects,
                            onForgetQuick = { t -> scope.launch { repo.forgetQuickConnect(t) } },
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
                            // Editing, copying and deleting a protected host all need the owner to authenticate first.
                            onEditHost = { host ->
                                scope.launch { if (authorize(host.requireBiometric, "Edit ${host.nickname}")) nav.navigate("host/${host.id}") }
                            },
                            onCopyHost = { host ->
                                scope.launch { if (authorize(host.requireBiometric, "Copy ${host.nickname}")) nav.navigate("host/new?from=${host.id}") }
                            },
                            onDeleteHost = { host ->
                                scope.launch { if (authorize(host.requireBiometric, "Delete ${host.nickname}")) vault.deleteHost(host) }
                            },
                            onOpenSession = { id -> nav.navigate("terminal/$id") },
                            onCloseSession = { id -> sessions.close(id) },
                            onLocalShell = {
                                val entry = sessions.openLocalShell(settings.terminalTheme)
                                onSessionStarted()
                                nav.navigate("terminal/${entry.id}")
                            },
                            onIdentities = { nav.navigate("identities") },
                            onKeys = { nav.navigate("keys") },
                            onSnippets = { nav.navigate("snippets") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable(
                        "host/{id}?from={from}",
                        arguments = listOf(navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { backStack ->
                        val id = backStack.arguments?.getString("id")
                        // "Copy as new config": a new host pre-filled from this one.
                        val fromId = backStack.arguments?.getString("from")
                        val template = hosts.firstOrNull { it.id == fromId }
                        val existing = hosts.firstOrNull { it.id == id }
                        // Wait for the hosts list before showing an edit form, so it isn't filled in empty.
                        val forwards by produceState<List<ForwardEntity>?>(null, id, fromId) {
                            value = when {
                                fromId != null -> vault.forwards(fromId).map { it.copy(id = UUID.randomUUID().toString()) }
                                id == null || id == "new" -> emptyList()
                                else -> vault.forwards(id)
                            }
                        }
                        if ((id == "new" && (fromId == null || template != null)) || existing != null) {
                            HostEditScreen(
                                themes = settings.themes.all,
                                existing = existing,
                                template = template,
                                allHosts = hosts,
                                identities = identities,
                                snippets = snippets,
                                initialForwards = forwards,
                                onSave = { host, fwds -> scope.launch { vault.saveHost(host, fwds) }; nav.back() },
                                onDelete = { host ->
                                    scope.launch {
                                        if (authorize(host.requireBiometric, "Delete ${host.nickname}")) {
                                            vault.deleteHost(host)
                                            nav.back()
                                        }
                                    }
                                },
                                onRequestDisableBiometric = { host -> authorize(host.requireBiometric, "Turn off fingerprint for ${host.nickname}") },
                                onManageIdentities = { nav.navigate("identities") },
                                onPinShortcut = if (HostShortcuts.canPin(this@MainActivity)) {
                                    { host -> HostShortcuts.pin(this@MainActivity, host) }
                                } else {
                                    null
                                },
                                biometricAvailable = Biometrics.isAvailable(this@MainActivity),
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
                                // Asked for the host and for every protected jump host on the way to it.
                                authenticate = { hop ->
                                    when (val r = Biometrics.authenticate(this@MainActivity, "Connect to ${hop.nickname}")) {
                                        AuthResult.Success -> null
                                        AuthResult.Cancelled -> "Authentication cancelled."
                                        AuthResult.Unavailable ->
                                            "\"${hop.nickname}\" requires a fingerprint or screen lock, but this phone has none set up. " +
                                                "Set one up, or turn the requirement off in the host's settings."
                                        is AuthResult.Failed -> r.message
                                    }
                                },
                                onConnected = { backend ->
                                    val override = settings.themes.find(h.themeId)
                                    val entry = sessions.open(
                                        label = h.nickname,
                                        backend = backend,
                                        theme = override ?: settings.terminalTheme,
                                        themeOverridden = override != null,
                                        hostId = h.id.takeIf { it != QUICK_ID },
                                        isProtected = h.requireBiometric,
                                    )
                                    onSessionStarted()
                                    if (h.id != QUICK_ID) HostShortcuts.reportUsed(this@MainActivity, h.id)
                                    // Only targets that actually connected go into the history, not typos.
                                    if (h.id == QUICK_ID) {
                                        val target = QuickConnect(h.username, h.address, h.port).format()
                                        scope.launch { repo.rememberQuickConnect(target) }
                                    }
                                    nav.navigate("terminal/${entry.id}") { popUpTo("hosts") }
                                },
                                onBack = { nav.back() },
                            )
                        }
                    }
                    composable("terminal/{id}") { backStack ->
                        val entry = backStack.arguments?.getString("id")?.let(sessions::get)
                        if (entry == null) {
                            // The session is gone (closed from its notification, or it ended). Leave, unless this
                            // screen is already on its way out and something else has taken its place.
                            LaunchedEffect(Unit) { if (nav.currentBackStackEntry?.id == backStack.id) nav.back() }
                        } else {
                            TerminalScreen(
                                entry = entry,
                                sessionManager = sessions,
                                fontSizeSp = settings.fontSizeSp,
                                onFontSizeChange = { sp -> scope.launch { repo.setFontSize(sp) } },
                                volumeKeysFontSize = settings.volumeKeysFontSize,
                                snippets = snippets,
                                onManageSnippets = { nav.navigate("snippets") },
                                onReconnect = entry.hostId?.let { hostId ->
                                    {
                                        // A new connection replaces the dead session, landing back on a terminal.
                                        sessions.close(entry.id)
                                        nav.navigate("connect/$hostId") { popUpTo("hosts") }
                                    }
                                },
                                autoReconnect = settings.autoReconnect,
                                exitBehavior = settings.exitBehavior,
                                onRememberExitBehavior = { b -> scope.launch { repo.setExitBehavior(b) } },
                                onLeave = { keepRunning ->
                                    if (!keepRunning) sessions.close(entry.id)
                                    // Back, the exit dialog and the session ending can all ask to leave; only
                                    // the first one may pop, or a second pop would take the host list too.
                                    if (nav.currentBackStackEntry?.id == backStack.id) nav.back()
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
                    composable("snippets") {
                        SnippetsScreen(
                            snippets = snippets,
                            onSave = { sn -> scope.launch { vault.saveSnippet(sn) } },
                            onDelete = { sn -> scope.launch { vault.deleteSnippet(sn) } },
                            onBack = { nav.back() },
                        )
                    }
                    composable("theme/{id}") { backStack ->
                        val base = settings.themes.byId(backStack.arguments?.getString("id"))
                        ThemeEditorScreen(
                            base = base,
                            onSave = { theme ->
                                scope.launch {
                                    repo.saveCustomTheme(theme)
                                    // Saving makes the theme current, so the change is visible straight away.
                                    repo.setTerminalTheme(theme.id)
                                }
                                nav.back()
                            },
                            onDelete = { theme -> scope.launch { repo.deleteCustomTheme(theme.id) }; nav.back() },
                            onBack = { nav.back() },
                        )
                    }
                    composable("backup") {
                        BackupScreen(
                            backup = app.backup,
                            // Restored keys may need their private halves from the vault.
                            onImported = { bitwarden.requestSync(0) },
                            // A backup can overwrite or remove protected hosts, so it needs the same proof.
                            authorizeImport = { authorize(true, "Import backup") },
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
                            onAutoReconnect = { on -> scope.launch { repo.setAutoReconnect(on) } },
                            biometricAvailable = Biometrics.isAvailable(this@MainActivity),
                            onRequireBiometricToOpen = { on ->
                                scope.launch {
                                    if (!on) {
                                        // Once on, turning it off takes the same proof as getting in.
                                        if (authorize(settings.requireBiometricToOpen, "Turn off app lock")) {
                                            repo.setRequireBiometricToOpen(false)
                                        }
                                        return@launch
                                    }
                                    // Prove it works before turning it on, so the app can't lock its owner out.
                                    when (val r = Biometrics.authenticate(this@MainActivity, "Turn on app lock")) {
                                        AuthResult.Success -> { unlocked = true; repo.setRequireBiometricToOpen(true) }
                                        else -> if (r is AuthResult.Failed) android.widget.Toast.makeText(this@MainActivity, r.message, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onBitwarden = { nav.navigate("bitwarden") },
                            onBackup = { nav.navigate("backup") },
                            knownHostCount = knownHostCount,
                            onClearKnownHosts = { scope.launch { vault.clearKnownHosts() } },
                            onEditTheme = { id -> nav.navigate("theme/$id") },
                            onBack = { nav.back() },
                        )
                    }
                }

                // Drawn over everything, so nothing of the app (including a running session) shows while locked.
                if (locked) {
                    LockScreen(
                        message = lockMessage,
                        unavailable = lockUnavailable,
                        onUnlock = { unlockRequest++ },
                        onTurnOffLock = { scope.launch { repo.setRequireBiometricToOpen(false) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            SessionService.ACTION_OPEN_SESSION -> pendingSessionId.value = intent.getStringExtra(SessionService.EXTRA_SESSION_ID)
            HostShortcuts.ACTION_CONNECT -> pendingHostId.value = intent.getStringExtra(HostShortcuts.EXTRA_HOST_ID)
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
