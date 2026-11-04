package me.nick.squeezessh.ui.theme

import com.termux.terminal.TerminalColorScheme

/** The emulator's color scheme for a [TerminalTheme]. */
fun TerminalTheme.toColorScheme(): TerminalColorScheme =
    TerminalColorScheme.of(foreground, background, cursor, ansi.toIntArray())
