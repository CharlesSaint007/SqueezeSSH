package me.nick.squeezessh.ssh

import com.termux.terminal.StreamBackend
import org.apache.sshd.client.channel.ChannelShell
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.PtyChannelConfiguration
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.EnumSet

/**
 * A terminal backend running an interactive shell on an authenticated SSH session. Owns the session (and any
 * jump-host sessions it was tunnelled through) and closes them when the shell ends.
 */
class SshShellBackend(
    private val session: ClientSession,
    /** Sessions this one depends on, e.g. the jump host; closed after [session]. */
    private val upstream: List<ClientSession> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
    private val agentForwarding: Boolean = false,
) : StreamBackend() {

    @Volatile
    private var channel: ChannelShell? = null

    override fun openInput(columns: Int, rows: Int): InputStream {
        val pty = PtyChannelConfiguration().apply {
            ptyType = "xterm-256color"
            ptyColumns = columns
            ptyLines = rows
        }
        val shell = session.createShellChannel(pty, env)
        shell.isAgentForwarding = agentForwarding
        shell.setRedirectErrorStream(true)
        shell.open().verify(Duration.ofSeconds(30))
        channel = shell
        return shell.invertedOut
    }

    override fun openOutput(): OutputStream = channel?.invertedIn ?: throw IOException("Shell channel not open")

    override fun describeEndOfStream(): String {
        val status = channel?.let {
            it.waitFor(EnumSet.of(ClientChannelEvent.EXIT_STATUS), Duration.ofSeconds(1))
            it.exitStatus
        }
        return if (status != null && status != 0) "Connection closed (exit status $status)" else "Connection closed"
    }

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        val shell = channel ?: return
        try {
            shell.sendWindowChange(columns, rows, columns * cellWidthPixels, rows * cellHeightPixels)
        } catch (_: IOException) {
            // The channel is going away; the reader thread will report it.
        }
    }

    override fun terminate() {
        close()
        finish(0, "Disconnected")
    }

    override fun close() {
        channel?.close(true)
        session.close(true)
        upstream.forEach { it.close(true) }
    }

    val isConnected: Boolean get() = session.isOpen
}
