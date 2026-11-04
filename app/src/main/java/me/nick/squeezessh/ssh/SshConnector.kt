package me.nick.squeezessh.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.util.Log
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.auth.keyboard.UserInteraction
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.AttributeRepository
import org.apache.sshd.common.SshException
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import java.io.IOException
import java.security.KeyPair
import java.security.PublicKey
import java.time.Duration

/** Everything needed to open one SSH hop. */
data class SshTarget(
    val host: String,
    val port: Int,
    val username: String,
    val keyPair: KeyPair?,
    /** A stored password, if any. When null and the server wants a password, [SshPrompts.password] is asked. */
    val password: String?,
    val keepaliveSeconds: Int = 30,
    val compression: Boolean = false,
)

/** The questions a connection can ask the user while it is being set up. */
interface SshPrompts : HostKeyPrompt {
    /** Ask for a password for [target]; return null to cancel. */
    suspend fun password(target: SshTarget, attempt: Int): String?

    /** Keyboard-interactive auth (e.g. 2FA codes). Return null to cancel. */
    suspend fun keyboardInteractive(name: String, instruction: String, prompts: List<Pair<String, Boolean>>): List<String>?
}

class SshConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Opens SSH sessions with Apache MINA SSHD. One [SshClient] is shared by all connections; per-connection state
 * (host-key decision, credentials, prompts) is attached to each [ClientSession].
 */
class SshConnector(private val hostKeys: HostKeyVerifier) {

    private val client: SshClient = SshClient.setUpDefaultClient().apply {
        // Host keys are verified per connection in [verifyHostKey]; this fallback rejects anything else.
        serverKeyVerifier = ServerKeyVerifier { _, _, _ -> false }
        // MINA's client refuses the server's "forwarded-tcpip" channels by default, which silently breaks -R
        // forwards. The server only opens them for remote forwards this app asked for, and each one goes to the
        // destination saved with that forward, so accept them.
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        // Log why sessions end; a dropped connection is otherwise silent.
        addSessionListener(object : SessionListener {
            override fun sessionException(session: Session, t: Throwable) {
                Log.w(TAG, "Session ${session.ioSession.remoteAddress} error: ${t.javaClass.simpleName}: ${t.message}")
            }

            override fun sessionDisconnect(session: Session, reason: Int, msg: String?, language: String?, initiator: Boolean) {
                Log.i(TAG, "Session ${session.ioSession.remoteAddress} disconnect reason=$reason by=${if (initiator) "us" else "server"}: $msg")
            }
        })
        start()
    }

    /**
     * Connect and authenticate. With [jump], the session is tunnelled through that already-authenticated
     * session (ProxyJump) via a local port forward.
     */
    suspend fun connect(target: SshTarget, prompts: SshPrompts, jump: ClientSession? = null): ClientSession =
        withContext(Dispatchers.IO) {
            val (connectHost, connectPort) = if (jump != null) {
                val local = jump.startLocalPortForwarding(
                    SshdSocketAddress("127.0.0.1", 0),
                    SshdSocketAddress(target.host, target.port),
                )
                local.hostName to local.port
            } else {
                target.host to target.port
            }

            val session = try {
                client.connect(target.username, connectHost, connectPort)
                    .verify(CONNECT_TIMEOUT)
                    .session
            } catch (e: Exception) {
                throw SshConnectException("Could not reach ${target.host}:${target.port}: ${rootMessage(e)}", e)
            }

            try {
                configure(session, target, prompts)
                session.auth().verify(AUTH_TIMEOUT)
                session
            } catch (e: Exception) {
                session.close(true)
                throw when {
                    e is SshConnectException -> e
                    session.getAttribute(HOST_KEY_REJECTED) == true -> SshConnectException("Host key not accepted", e)
                    else -> SshConnectException("Authentication failed: ${rootMessage(e)}", e)
                }
            }
        }

    private fun configure(session: ClientSession, target: SshTarget, prompts: SshPrompts) {
        // MINA calls the verifier on its own I/O thread and needs a synchronous answer; the user prompt is a
        // suspend function, so bridge with runBlocking on that thread only.
        session.serverKeyVerifier = ServerKeyVerifier { _, _, key ->
            runBlocking { verifyHostKey(target, key, prompts) }.also { ok ->
                if (!ok) session.setAttribute(HOST_KEY_REJECTED, true)
            }
        }
        target.keyPair?.let { session.addPublicKeyIdentity(it) }
        target.password?.let { session.addPasswordIdentity(it) }

        var passwordAttempts = 0
        session.userInteraction = object : UserInteraction {
            override fun isInteractionAllowed(session: ClientSession) = true

            override fun resolveAuthPasswordAttempt(session: ClientSession): String? {
                // Only prompt if no stored password was offered, and give the user three tries.
                if (target.password != null || passwordAttempts >= 3) return null
                passwordAttempts++
                return runBlocking { prompts.password(target, passwordAttempts) }
            }

            override fun interactive(
                session: ClientSession,
                name: String?,
                instruction: String?,
                lang: String?,
                prompt: Array<out String>,
                echo: BooleanArray,
            ): Array<String>? {
                // A single echo-off prompt with a stored password is the usual "Password:" case.
                if (prompt.size == 1 && !echo[0] && target.password != null) return arrayOf(target.password)
                if (prompt.isEmpty()) return emptyArray()
                val pairs = prompt.indices.map { prompt[it] to echo[it] }
                return runBlocking { prompts.keyboardInteractive(name.orEmpty(), instruction.orEmpty(), pairs) }
                    ?.toTypedArray()
            }

            override fun getUpdatedPassword(session: ClientSession, prompt: String?, lang: String?): String? = null
        }

        // MINA closes a session after 10 minutes of no channel data by default, and its keepalives don't count
        // as data, so an idle shell would drop even with keepalives on. An interactive terminal should stay
        // open until the user or the server ends it; the server's own timeouts still apply.
        CoreModuleProperties.IDLE_TIMEOUT.set(session, Duration.ZERO)
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(
            session,
            Duration.ofSeconds(target.keepaliveSeconds.coerceAtLeast(0).toLong()),
        )
        // Keepalives use the standard OpenSSH request name so servers reply instead of logging an unknown
        // request, and three unanswered ones mark the connection dead instead of waiting forever.
        CoreModuleProperties.HEARTBEAT_REQUEST.set(session, "keepalive@openssh.com")
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(session, 3)
    }

    private suspend fun verifyHostKey(target: SshTarget, key: PublicKey, prompts: SshPrompts): Boolean {
        val check = hostKeys.check(target.host, target.port, key)
        if (check is HostKeyCheck.Trusted) return true
        val accepted = prompts.confirm(check)
        if (accepted) hostKeys.remember(check, key)
        return accepted
    }

    fun close() {
        client.stop()
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)
        val AUTH_TIMEOUT: Duration = Duration.ofSeconds(120)
        val HOST_KEY_REJECTED = AttributeRepository.AttributeKey<Boolean>()
        const val TAG = "SshConnector"

        fun rootMessage(e: Throwable): String {
            val root = generateSequence(e) { it.cause }.last()
            return when (root) {
                is java.net.UnknownHostException -> "unknown host ${root.message}"
                is java.net.ConnectException -> "connection refused"
                is java.net.SocketTimeoutException -> "timed out"
                is SshException -> root.message ?: "SSH error"
                else -> root.message ?: root.javaClass.simpleName
            }
        }
    }
}
