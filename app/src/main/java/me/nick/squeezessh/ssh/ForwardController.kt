package me.nick.squeezessh.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.ForwardType
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.util.net.SshdSocketAddress
import java.net.BindException

/** One forward's live state within a connection. */
data class ForwardState(
    val forward: ForwardEntity,
    /** The address actually listening, once started; its port differs from the saved one when that was 0. */
    val bound: SshdSocketAddress? = null,
    val error: String? = null,
) {
    val active: Boolean get() = bound != null
}

/**
 * Runs a host's port forwards over one SSH connection. Forwards end with the connection; the controller then
 * reports them all as stopped.
 */
class ForwardController(private val session: ClientSession, forwards: List<ForwardEntity>) {
    private val mutex = Mutex()
    private val _states = MutableStateFlow(forwards.map { ForwardState(it) })
    val states: StateFlow<List<ForwardState>> = _states

    init {
        session.addCloseFutureListener {
            _states.update { list -> list.map { it.copy(bound = null) } }
        }
    }

    /** Start every forward marked to start with the connection; returns the ones that failed. */
    suspend fun startAutomatic(): List<ForwardState> {
        for (state in _states.value) if (state.forward.autoStart) start(state.forward.id)
        return _states.value.filter { it.forward.autoStart && it.error != null }
    }

    suspend fun start(id: String) = mutex.withLock {
        val state = _states.value.firstOrNull { it.forward.id == id } ?: return@withLock
        if (state.active) return@withLock
        val result = withContext(Dispatchers.IO) { runCatching { open(state.forward) } }
        set(id) {
            it.copy(bound = result.getOrNull(), error = result.exceptionOrNull()?.let(::describe))
        }
    }

    suspend fun stop(id: String) = mutex.withLock {
        val state = _states.value.firstOrNull { it.forward.id == id } ?: return@withLock
        val bound = state.bound ?: return@withLock
        withContext(Dispatchers.IO) {
            runCatching {
                when (state.forward.type) {
                    ForwardType.Local -> session.stopLocalPortForwarding(bound)
                    ForwardType.Remote -> session.stopRemotePortForwarding(bound)
                    ForwardType.Dynamic -> session.stopDynamicPortForwarding(bound)
                }
            }
        }
        set(id) { it.copy(bound = null, error = null) }
    }

    private fun open(f: ForwardEntity): SshdSocketAddress {
        val bind = SshdSocketAddress(f.bindAddress.ifBlank { ForwardEntity.LOOPBACK }, f.bindPort)
        return when (f.type) {
            ForwardType.Local -> session.startLocalPortForwarding(bind, destination(f))
            // For -R the server listens; it replies with the port it chose when 0 was asked for.
            ForwardType.Remote -> session.startRemotePortForwarding(bind, destination(f))
            ForwardType.Dynamic -> session.startDynamicPortForwarding(bind)
        }
    }

    private fun destination(f: ForwardEntity): SshdSocketAddress {
        val host = f.destHost?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("No destination host")
        val port = f.destPort ?: throw IllegalArgumentException("No destination port")
        return SshdSocketAddress(host, port)
    }

    private fun set(id: String, change: (ForwardState) -> ForwardState) {
        _states.update { list -> list.map { if (it.forward.id == id) change(it) else it } }
    }

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return when (root) {
            is BindException -> "Port in use or not allowed"
            else -> root.message ?: root.javaClass.simpleName
        }
    }
}
