package me.nick.squeezessh.ssh

import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.KeyEntity
import me.nick.squeezessh.security.AuthRules
import me.nick.squeezessh.data.Vault
import org.apache.sshd.client.session.ClientSession

/** Thrown when a host's settings can't produce a connection (missing username, jump-host loop, ...). */
class HostConfigException(message: String) : Exception(message)

/**
 * Turns a saved [HostEntity] into an authenticated SSH shell backend, resolving its identity, key and any
 * chain of jump hosts.
 */
class ConnectionService(private val vault: Vault, private val connector: SshConnector) {

    /**
     * @param authorize called with each host on the way before it is connected to (the jump hosts, then [host]);
     * returns null to go ahead or a message to stop with. This is how a protected jump host cannot be used as a
     * way around its fingerprint requirement.
     */
    suspend fun openShell(
        host: HostEntity,
        prompts: SshPrompts,
        usernameOverride: String? = null,
        authorize: (suspend (HostEntity) -> String?)? = null,
    ): SshShellBackend {
        val chain = jumpChain(host)
        authorize?.let { check ->
            AuthRules.firstRefusal(chain, isProtected = { it.requireBiometric }, ask = check)?.let { throw HostConfigException(it) }
        }
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
            val forwards = vault.forwards(host.id).takeIf { it.isNotEmpty() }?.let { ForwardController(session, it) }
            // A forward that can't start (port in use, refused by the server) doesn't stop the shell; the
            // session's forwards list shows the error.
            forwards?.startAutomatic()
            val snippet = host.startupSnippetId?.let { vault.snippet(it) }
            return SshShellBackend(
                session = session,
                upstream = opened.dropLast(1).reversed(),
                env = mapOf("COLORTERM" to "truecolor"),
                agentForwarding = host.agentForwarding,
                startupInput = snippet?.let(SnippetText::of),
                forwards = forwards,
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
        if (key != null && !key.hasPrivateKey) {
            throw HostConfigException(
                "The key \"${key.name}\" was restored without its private half. Sign in to Bitwarden sync, " +
                    "or import a backup that includes secrets.",
            )
        }
        return SshTarget(
            host = host.address,
            port = host.port,
            username = username,
            keyPair = key?.let { vault.keyPair(it) },
            agentKeys = if (host.agentForwarding) agentKeysFor(key) else emptyList(),
            password = identity?.let { vault.password(it) },
            keepaliveSeconds = host.keepaliveSeconds,
            compression = host.compression,
        )
    }

    /**
     * The keys to forward: the one this host logs in with plus every key linked to Bitwarden, i.e. the ones the user
     * keeps in their vault. Other keys stay on the phone, so a compromised server can't ask for them.
     */
    private suspend fun agentKeysFor(loginKey: KeyEntity?): List<AgentKey> =
        (listOfNotNull(loginKey) + vault.allKeys().filter { it.bitwardenCipherId != null })
            .distinctBy { it.id }
            .filter { it.hasPrivateKey }
            .mapNotNull { key -> vault.keyPair(key)?.let { AgentKey(it, key.name) } }

    private companion object {
        const val MAX_JUMPS = 5
    }
}
