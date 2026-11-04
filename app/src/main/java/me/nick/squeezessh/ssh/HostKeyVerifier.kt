package me.nick.squeezessh.ssh

import me.nick.squeezessh.data.KnownHostDao
import me.nick.squeezessh.data.KnownHostEntity
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.security.PublicKey

/** What we know about a server's host key compared with what it just presented. */
sealed interface HostKeyCheck {
    val hostPort: String
    val keyType: String
    val fingerprint: String

    /** Seen and accepted before; connect silently. */
    data class Trusted(override val hostPort: String, override val keyType: String, override val fingerprint: String) :
        HostKeyCheck

    /** First connection to this host (for this key type). The user must confirm the fingerprint. */
    data class Unknown(override val hostPort: String, override val keyType: String, override val fingerprint: String) :
        HostKeyCheck

    /**
     * The host presented a different key than the one we stored. This can be a reinstalled server, or someone
     * in the middle; the user must explicitly choose to replace the stored key.
     */
    data class Changed(
        override val hostPort: String,
        override val keyType: String,
        override val fingerprint: String,
        val previousFingerprint: String,
    ) : HostKeyCheck
}

/** Asks the user about a host key; returns true to accept (and remember) it. Suspends until they answer. */
fun interface HostKeyPrompt {
    suspend fun confirm(check: HostKeyCheck): Boolean
}

class HostKeyVerifier(private val dao: KnownHostDao) {

    suspend fun check(host: String, port: Int, key: PublicKey): HostKeyCheck {
        val hostPort = hostPort(host, port)
        val keyType = KeyUtils.getKeyType(key)
        val fingerprint = KeyUtils.getFingerPrint(key)
        val known = dao.forHost(hostPort)
        val sameType = known.firstOrNull { it.keyType == keyType }
        return when {
            sameType?.fingerprint == fingerprint -> HostKeyCheck.Trusted(hostPort, keyType, fingerprint)
            sameType != null -> HostKeyCheck.Changed(hostPort, keyType, fingerprint, sameType.fingerprint)
            // A host we know under another key type now offering a new one is treated as a change, so a
            // downgrade from ed25519 to an attacker-supplied RSA key is not silently accepted.
            known.isNotEmpty() -> HostKeyCheck.Changed(hostPort, keyType, fingerprint, known.first().fingerprint)
            else -> HostKeyCheck.Unknown(hostPort, keyType, fingerprint)
        }
    }

    suspend fun remember(check: HostKeyCheck, key: PublicKey) {
        if (check is HostKeyCheck.Changed) dao.forget(check.hostPort)
        val encoded = PublicKeyEntry.toString(key).substringAfter(' ').substringBefore(' ')
        dao.upsert(KnownHostEntity(check.hostPort, check.keyType, check.fingerprint, encoded))
    }

    companion object {
        /** OpenSSH known_hosts naming: bare host for port 22, `[host]:port` otherwise. */
        fun hostPort(host: String, port: Int): String = if (port == 22) host.lowercase() else "[${host.lowercase()}]:$port"
    }
}
