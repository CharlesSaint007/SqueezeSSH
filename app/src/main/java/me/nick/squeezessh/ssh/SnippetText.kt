package me.nick.squeezessh.ssh

import me.nick.squeezessh.data.SnippetEntity

/** What typing a snippet sends to a terminal. */
object SnippetText {
    /**
     * The snippet's text with line endings as a terminal's Enter key sends them (CR), plus a final Enter when
     * the snippet asks for one and doesn't already end with a line break.
     */
    fun of(snippet: SnippetEntity): String {
        val text = snippet.text.replace("\r\n", "\n").replace('\n', '\r')
        return if (snippet.sendEnter && !text.endsWith('\r')) text + "\r" else text
    }
}
