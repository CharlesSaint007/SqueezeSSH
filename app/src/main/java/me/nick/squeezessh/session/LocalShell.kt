package me.nick.squeezessh.session

import com.termux.terminal.PtyBackend
import com.termux.terminal.TerminalBackend

/** The device's own shell (`/system/bin/sh`), run on a local pseudo-terminal. */
object LocalShell {
    private const val SHELL = "/system/bin/sh"

    fun backend(): TerminalBackend {
        val home = System.getenv("HOME")?.takeIf { it.isNotEmpty() } ?: "/"
        val env = buildList {
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            add("HOME=$home")
            add("LANG=en_US.UTF-8")
            // Keep what Android gives the app process so system tools (toybox, etc.) are found.
            for (name in listOf("PATH", "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_STORAGE", "EXTERNAL_STORAGE", "TMPDIR")) {
                System.getenv(name)?.let { add("$name=$it") }
            }
        }.toTypedArray()
        // argv[0] of "-sh" makes it a login shell, like Termux does.
        return PtyBackend(SHELL, home, arrayOf("-sh"), env)
    }
}
