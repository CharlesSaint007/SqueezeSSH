package me.nick.squeezessh.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.nick.squeezessh.MainActivity
import me.nick.squeezessh.R
import me.nick.squeezessh.SqueezeApp

/**
 * Keeps the process alive while sessions run, so SSH connections survive the app being backgrounded or its
 * activity destroyed. Runs as a `specialUse` foreground service: unlike `dataSync`, it has no time limit on
 * Android 15+, which matters for sessions left open for hours.
 *
 * It shows one notification per session (tap to reopen it, or Disconnect) grouped under a summary, and stops
 * itself when the last session closes.
 */
class SessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as SqueezeApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var shownIds = emptySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // Must be called promptly after startForegroundService(), before any flow emits.
        startInForeground(summaryNotification(app.sessionManager.sessions.value))

        scope.launch {
            app.sessionManager.sessions.collect { sessions ->
                if (sessions.none { it.running }) {
                    stopSelf()
                } else {
                    render(sessions)
                }
            }
        }
        scope.launch {
            combine(
                app.settingsRepository.settings.map { it.keepAwake }.distinctUntilChanged(),
                app.sessionManager.sessions.map { list -> list.any { it.running } }.distinctUntilChanged(),
            ) { keepAwake, anyRunning -> keepAwake && anyRunning }
                .collect { hold -> setLocks(hold) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> intent.getStringExtra(EXTRA_SESSION_ID)?.let { app.sessionManager.close(it) }
            ACTION_DISCONNECT_ALL -> app.sessionManager.sessions.value.forEach { app.sessionManager.close(it.id) }
        }
        // If the system kills the process the sessions are gone with it; there is nothing to restart into.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setLocks(false)
        scope.cancel()
        NotificationManagerCompat.from(this).let { nm -> shownIds.forEach { nm.cancel(it.hashCode()) } }
        super.onDestroy()
    }

    /** Removing the task from Recents leaves sessions running; the notifications are the way back in. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, SUMMARY_ID, notification, type)
    }

    @SuppressLint("MissingPermission") // Checked in canNotify().
    private fun render(sessions: List<SessionInfo>) {
        val nm = NotificationManagerCompat.from(this)
        startInForeground(summaryNotification(sessions))
        val running = sessions.filter { it.running }
        if (canNotify()) {
            running.forEach { nm.notify(it.id.hashCode(), sessionNotification(it)) }
        }
        val ids = running.map { it.id }.toSet()
        (shownIds - ids).forEach { nm.cancel(it.hashCode()) }
        shownIds = ids
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun summaryNotification(sessions: List<SessionInfo>): Notification {
        val running = sessions.count { it.running }
        val text = when (running) {
            0 -> "Starting…"
            1 -> "1 session running"
            else -> "$running sessions running"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent(sessionId = null))
            .addAction(0, "Disconnect all", serviceIntent(ACTION_DISCONNECT_ALL, null))
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun sessionNotification(session: SessionInfo): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle(session.label)
            .setContentText(session.title?.takeIf { it.isNotBlank() } ?: "Connected")
            // Shows "connected 12m" style elapsed time without the app having to update it.
            .setWhen(session.startedAt)
            .setUsesChronometer(true)
            .setContentIntent(openIntent(session.id))
            .addAction(0, "Disconnect", serviceIntent(ACTION_DISCONNECT, session.id))
            .setGroup(GROUP_KEY)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun openIntent(sessionId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(if (sessionId != null) ACTION_OPEN_SESSION else Intent.ACTION_MAIN)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // A distinct request code per session so each notification opens its own session.
        return PendingIntent.getActivity(
            this,
            sessionId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String, sessionId: String?): PendingIntent =
        PendingIntent.getService(
            this,
            (action + sessionId).hashCode(),
            Intent(this, SessionService::class.java).setAction(action).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Held deliberately for as long as sessions run. Released by setLocks(false), which runs when the setting is
    // turned off, when the last session ends, and from onDestroy(); lint can't see across those calls.
    @SuppressLint("WakelockTimeout", "Wakelock")
    private fun setLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SqueezeSSH:sessions")
                    .apply { acquire() }
            }
            if (wifiLock == null) {
                @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF is the only mode that keeps Wi-Fi up with the screen off.
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SqueezeSSH:sessions")
                    .apply { acquire() }
            }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            wifiLock = null
        }
    }

    companion object {
        const val ACTION_OPEN_SESSION = "me.nick.squeezessh.action.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "session_id"
        private const val ACTION_DISCONNECT = "me.nick.squeezessh.action.DISCONNECT"
        private const val ACTION_DISCONNECT_ALL = "me.nick.squeezessh.action.DISCONNECT_ALL"
        private const val CHANNEL_ID = "sessions"
        private const val GROUP_KEY = "me.nick.squeezessh.SESSIONS"
        private const val SUMMARY_ID = 1

        /** Ensure the service is running; call whenever a session is kept in the background. */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Active sessions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SSH sessions keep running in the background"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
