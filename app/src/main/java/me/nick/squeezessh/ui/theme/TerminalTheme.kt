package me.nick.squeezessh.ui.theme

/**
 * A terminal color palette. Colors are ARGB ints so this class stays free of Compose and can be
 * shared with the terminal renderer and the JSON import/export code.
 *
 * [ansi] holds the 16 ANSI colors in standard order: black, red, green, yellow, blue, magenta,
 * cyan, white, then the bright variants of each.
 */
data class TerminalTheme(
    val id: String,
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val selection: Int,
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == 16) { "Theme '$id' must define 16 ANSI colors, got ${ansi.size}" }
    }

    val red get() = ansi[1]
    val green get() = ansi[2]
    val yellow get() = ansi[3]
    val blue get() = ansi[4]
    val magenta get() = ansi[5]
    val cyan get() = ansi[6]
}

private fun rgb(hex: Long): Int = (0xFF000000 or hex).toInt()

object BuiltInThemes {
    val GruvboxDark = TerminalTheme(
        id = "gruvbox-dark",
        name = "Gruvbox Dark",
        foreground = rgb(0xEBDBB2),
        background = rgb(0x282828),
        cursor = rgb(0xEBDBB2),
        selection = rgb(0x504945),
        ansi = listOf(
            0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
            0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
        ).map { rgb(it.toLong()) },
    )

    val SolarizedDark = TerminalTheme(
        id = "solarized-dark",
        name = "Solarized Dark",
        foreground = rgb(0x839496),
        background = rgb(0x002B36),
        cursor = rgb(0x93A1A1),
        selection = rgb(0x073642),
        ansi = listOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ).map { rgb(it.toLong()) },
    )

    val TokyoNight = TerminalTheme(
        id = "tokyo-night",
        name = "Tokyo Night",
        foreground = rgb(0xC0CAF5),
        background = rgb(0x1A1B26),
        cursor = rgb(0xC0CAF5),
        selection = rgb(0x33467C),
        ansi = listOf(
            0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
            0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5,
        ).map { rgb(it.toLong()) },
    )

    val DefaultDark = TerminalTheme(
        id = "default-dark",
        name = "Dark",
        foreground = rgb(0xE6E6E6),
        background = rgb(0x121212),
        cursor = rgb(0xE6E6E6),
        selection = rgb(0x3A3A3A),
        ansi = listOf(
            0x000000, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xD0D0D0,
            0x5C6370, 0xFF7B86, 0xB5E890, 0xFFD58F, 0x82C4FF, 0xDE9BF0, 0x7FD3DC, 0xFFFFFF,
        ).map { rgb(it.toLong()) },
    )

    val DefaultLight = TerminalTheme(
        id = "default-light",
        name = "Light",
        foreground = rgb(0x2E3440),
        background = rgb(0xFAFAFA),
        cursor = rgb(0x2E3440),
        selection = rgb(0xD8DEE9),
        ansi = listOf(
            0x2E3440, 0xC82829, 0x3E8E00, 0x9A6700, 0x2563C9, 0x8E44AD, 0x00838F, 0xD0D0D0,
            0x6B7280, 0xE0383E, 0x4CA30D, 0xB57A00, 0x3B7DE0, 0xA855C7, 0x0097A7, 0xFFFFFF,
        ).map { rgb(it.toLong()) },
    )

    val all = listOf(GruvboxDark, SolarizedDark, TokyoNight, DefaultDark, DefaultLight)

    val default = TokyoNight

    fun byId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: default
}
