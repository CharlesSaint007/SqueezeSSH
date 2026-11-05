package me.nick.squeezessh.session

import com.termux.terminal.TerminalBackend
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.nick.squeezessh.ssh.ForwardController
import me.nick.squeezessh.ssh.SshShellBackend
import me.nick.squeezessh.ui.theme.TerminalTheme
import me.nick.squeezessh.ui.theme.toColorScheme

/** A live terminal session and the info the UI shows about it. */
class SessionEntry(
    val id: String,
    val label: String,
    /** The saved host this session connected to; null for the local shell and one-off quick connects. */
    val hostId: String? = null,
    /** The host requires a fingerprint, so what the remote shell reports about it (its title) is not shown. */
    val isProtected: Boolean = false,
    val session: TerminalSession,
    /** The theme this session uses: the host's override, or the global theme. */
    var theme: TerminalTheme,
    /** True when the host chose its own theme, so global theme changes leave this session alone. */
    val themeOverridden: Boolean = false,
    /** Port forwards running over this session's connection, if it has any. */
    val forwards: ForwardController? = null,
) {
    /** The connection dropped (network gone, server went away) rather than the shell exiting. */
    val droppedUnexpectedly: Boolean
        get() = !session.isRunning && (session.backend as? SshShellBackend)?.droppedUnexpectedly == true

    val startedAt: Long = System.currentTimeMillis()

    /** Whether the key bar is shown; tapping the terminal toggles it, and each session remembers its own. */
    var keyBarVisible by mutableStateOf(true)

    fun info() = SessionInfo(id, label, session.title.takeUnless { isProtected }, session.isRunning, startedAt)
}

/** An immutable snapshot of a session, for lists and notifications. */
data class SessionInfo(
    val id: String,
    val label: String,
    /** The terminal title set by the remote shell, if any. */
    val title: String?,
    val running: Boolean,
    val startedAt: Long,
)

/**
 * Owns every live terminal session so they outlive any one screen, and the activity itself: while any session
 * is running, [SessionService] keeps the process in the foreground.
 *
 * All methods are called on the main thread, the same thread [TerminalSession] delivers its callbacks on.
 */
class SessionManager {
    private val entries = mutableListOf<SessionEntry>()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions

    /**
     * Sessions report to one client that fans events out to whichever screen is attached. Kept separate from the
     * screen so that a rotated or recreated activity just re-attaches.
     */
    val client = DelegatingSessionClient()

    fun openLocalShell(theme: TerminalTheme): SessionEntry = open("Local shell", LocalShell.backend(), theme)

    /** Open a terminal on an already-connected backend, e.g. an [me.nick.squeezessh.ssh.SshShellBackend]. */
    fun open(
        label: String,
        backend: TerminalBackend,
        theme: TerminalTheme,
        themeOverridden: Boolean = false,
        hostId: String? = null,
        isProtected: Boolean = false,
    ): SessionEntry {
        val session = TerminalSession(backend, TRANSCRIPT_ROWS, theme.toColorScheme(), client)
        val entry = SessionEntry(
            id = session.mHandle,
            label = label,
            hostId = hostId,
            isProtected = isProtected,
            session = session,
            theme = theme,
            themeOverridden = themeOverridden,
            forwards = (backend as? SshShellBackend)?.forwards,
        )
        entries += entry
        refresh()
        return entry
    }

    fun get(id: String): SessionEntry? = entries.firstOrNull { it.id == id }

    /** Apply a new theme to every open session (the global theme changed). */
    fun applyTheme(theme: TerminalTheme) {
        val scheme = theme.toColorScheme()
        for (entry in entries) {
            if (entry.themeOverridden) continue
            entry.theme = theme
            entry.session.setColorScheme(scheme)
        }
    }

    /** Disconnect (if still running) and forget the session. */
    fun close(id: String) {
        val entry = get(id) ?: return
        entries.remove(entry)
        entry.session.finishIfRunning()
        refresh()
    }

    private fun refresh() {
        _sessions.value = entries.map { it.info() }
    }

    inner class DelegatingSessionClient : TerminalSessionClient by NoOpSessionClient {
        @Volatile
        var delegate: TerminalSessionClient? = null

        override fun onTextChanged(changedSession: TerminalSession) {
            delegate?.onTextChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            refresh()
            delegate?.onTitleChanged(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            refresh()
            delegate?.onSessionFinished(finishedSession)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            delegate?.onCopyTextToClipboard(session, text)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            delegate?.onPasteTextFromClipboard(session)
        }

        override fun onBell(session: TerminalSession) {
            delegate?.onBell(session)
        }

        override fun onColorsChanged(session: TerminalSession) {
            delegate?.onColorsChanged(session)
        }
    }

    companion object {
        const val TRANSCRIPT_ROWS = 5000
    }
}
