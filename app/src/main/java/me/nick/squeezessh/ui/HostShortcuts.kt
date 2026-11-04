package me.nick.squeezessh.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import me.nick.squeezessh.MainActivity
import me.nick.squeezessh.R
import me.nick.squeezessh.data.HostEntity

/**
 * Launcher shortcuts that connect straight to a host: the most recently used hosts appear when the app icon is
 * long-pressed, and any host can be pinned to the home screen from its settings.
 */
object HostShortcuts {
    const val ACTION_CONNECT = "me.nick.squeezessh.action.CONNECT"
    const val EXTRA_HOST_ID = "host_id"
    private const val MAX_RECENT = 4

    fun canPin(context: Context) = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Ask the launcher to pin [host]; the launcher shows its own confirmation. */
    fun pin(context: Context, host: HostEntity) {
        ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, host), null)
    }

    /** Tell the launcher a host's shortcut was used, so it can rank its suggestions. */
    fun reportUsed(context: Context, hostId: String) {
        ShortcutManagerCompat.reportShortcutUsed(context, hostId)
    }

    /** Publish the most recently connected hosts, and refresh the labels of any pinned ones. */
    fun update(context: Context, hosts: List<HostEntity>) {
        val recent = hosts.filter { it.lastConnectedAt != null }
            .sortedByDescending { it.lastConnectedAt }
            .take(minOf(MAX_RECENT, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)))
        ShortcutManagerCompat.setDynamicShortcuts(context, recent.mapIndexed { i, h -> shortcut(context, h, rank = i) })
        // Pinned shortcuts can't be removed by the app, but they can be updated (renamed host) or disabled
        // (deleted host) so they don't open something that no longer exists.
        val byId = hosts.associateBy { it.id }
        val pinned = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val gone = pinned.filter { it.id !in byId }.map { it.id }
        if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, "This host was deleted")
        val live = pinned.mapNotNull { byId[it.id] }
        if (live.isNotEmpty()) ShortcutManagerCompat.updateShortcuts(context, live.map { shortcut(context, it) })
    }

    private fun shortcut(context: Context, host: HostEntity, rank: Int = 0): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, host.id)
            .setShortLabel(host.nickname.take(24))
            .setLongLabel("Connect to ${host.nickname}")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_host))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(ACTION_CONNECT)
                    .putExtra(EXTRA_HOST_ID, host.id),
            )
            .setRank(rank)
            .build()
}
