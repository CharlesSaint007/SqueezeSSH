package me.nick.squeezessh.session

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import me.nick.squeezessh.ui.theme.TerminalTheme
import me.nick.squeezessh.ui.theme.toColorScheme

/** A live terminal session and the info the UI shows about it. */
class SessionEntry(
    val id: String,
    val label: String,
    val session: TerminalSession,
    /** The theme this session was opened with; a per-host override later, the global default for now. */
    var theme: TerminalTheme,
)

/**
 * Owns every live terminal session so they outlive any one screen. Milestone 4 moves this behind a foreground
 * service so sessions also survive the app being backgrounded.
 */
class SessionManager {
    private val _sessions = MutableStateFlow<List<SessionEntry>>(emptyList())
    val sessions: StateFlow<List<SessionEntry>> = _sessions

    /**
     * Sessions report to one client that fans events out to whichever screen is attached. Kept separate from the
     * screen so that a rotated or recreated activity just re-attaches.
     */
    val client = DelegatingSessionClient()

    fun openLocalShell(theme: TerminalTheme): SessionEntry {
        val session = TerminalSession(
            LocalShell.backend(),
            TRANSCRIPT_ROWS,
            theme.toColorScheme(),
            client,
        )
        val entry = SessionEntry(id = session.mHandle, label = "Local shell", session = session, theme = theme)
        _sessions.update { it + entry }
        return entry
    }

    fun get(id: String): SessionEntry? = _sessions.value.firstOrNull { it.id == id }

    /** Apply a new theme to every open session (the global theme changed). */
    fun applyTheme(theme: TerminalTheme) {
        val scheme = theme.toColorScheme()
        for (entry in _sessions.value) {
            entry.theme = theme
            entry.session.setColorScheme(scheme)
        }
    }

    fun close(id: String) {
        get(id)?.session?.finishIfRunning()
        remove(id)
    }

    internal fun remove(id: String) = _sessions.update { list -> list.filterNot { it.id == id } }

    inner class DelegatingSessionClient : TerminalSessionClient by NoOpSessionClient {
        @Volatile
        var delegate: TerminalSessionClient? = null

        override fun onTextChanged(changedSession: TerminalSession) {
            delegate?.onTextChanged(changedSession)
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            delegate?.onTitleChanged(changedSession)
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
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
