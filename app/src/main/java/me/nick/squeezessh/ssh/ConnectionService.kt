package me.nick.squeezessh.ssh

import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.Vault
import org.apache.sshd.client.session.ClientSession

/** Thrown when a host's settings can't produce a connection (missing username, jump-host loop, ...). */
class HostConfigException(message: String) : Exception(message)

/**
 * Turns a saved [HostEntity] into an authenticated SSH shell backend, resolving its identity, key and any
 * chain of jump hosts.
 */
class ConnectionService(private val vault: Vault, private val connector: SshConnector) {

    suspend fun openShell(host: HostEntity, prompts: SshPrompts, usernameOverride: String? = null): SshShellBackend {
        val chain = jumpChain(host)
        val opened = mutableListOf<ClientSession>()
        try {
            var previous: ClientSession? = null
            for ((index, hop) in chain.withIndex()) {
                val isTarget = index == chain.lastIndex
                val target = targetFor(hop, if (isTarget) usernameOverride else null)
                val session = connector.connect(target, prompts, jump = previous)
                opened += session
                previous = session
            }
            vault.markConnected(host.id)
            val session = opened.last()
            return SshShellBackend(
                session = session,
                upstream = opened.dropLast(1).reversed(),
                env = mapOf("COLORTERM" to "truecolor"),
                agentForwarding = host.agentForwarding,
            )
        } catch (e: Exception) {
            opened.reversed().forEach { it.close(true) }
            throw e
        }
    }

    /** The hosts to connect through, outermost jump first and [host] last. */
    private suspend fun jumpChain(host: HostEntity): List<HostEntity> {
        val chain = ArrayDeque<HostEntity>()
        var current: HostEntity? = host
        val seen = mutableSetOf<String>()
        while (current != null) {
            if (!seen.add(current.id)) throw HostConfigException("Jump hosts form a loop at \"${current.nickname}\"")
            if (chain.size > MAX_JUMPS) throw HostConfigException("Too many jump hosts (max $MAX_JUMPS)")
            chain.addFirst(current)
            current = current.jumpHostId?.let { vault.host(it) }
        }
        return chain
    }

    private suspend fun targetFor(host: HostEntity, usernameOverride: String?): SshTarget {
        val identity = host.identityId?.let { vault.identity(it) }
        val username = usernameOverride ?: identity?.username ?: host.username
        if (username.isNullOrBlank()) throw HostConfigException("\"${host.nickname}\" has no username or identity")
        val key = identity?.keyId?.let { vault.key(it) }
        return SshTarget(
            host = host.address,
            port = host.port,
            username = username,
            keyPair = key?.let { vault.keyPair(it) },
            password = identity?.let { vault.password(it) },
            keepaliveSeconds = host.keepaliveSeconds,
            compression = host.compression,
        )
    }

    private companion object {
        const val MAX_JUMPS = 5
    }
}
