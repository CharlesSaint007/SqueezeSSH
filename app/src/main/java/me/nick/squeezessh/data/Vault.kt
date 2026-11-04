package me.nick.squeezessh.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import me.nick.squeezessh.crypto.KeyAlgorithm
import me.nick.squeezessh.crypto.KeyCodec
import me.nick.squeezessh.crypto.ParsedKey
import me.nick.squeezessh.crypto.SecretStore
import java.security.KeyPair

/**
 * The single entry point for hosts, identities and keys. Keeps the Room rows and the encrypted secrets they
 * reference in step, so a deleted key or identity never leaves its secret behind.
 */
class Vault(private val db: AppDatabase, private val secrets: SecretStore) {

    val hosts: Flow<List<HostEntity>> = db.hosts().observeAll()
    val identities: Flow<List<IdentityEntity>> = db.identities().observeAll()
    val keys: Flow<List<KeyEntity>> = db.keys().observeAll()

    suspend fun host(id: String) = db.hosts().get(id)
    suspend fun identity(id: String) = db.identities().get(id)
    suspend fun key(id: String) = db.keys().get(id)

    suspend fun saveHost(host: HostEntity) = db.hosts().upsert(host)
    suspend fun deleteHost(host: HostEntity) = db.hosts().delete(host)
    suspend fun markConnected(hostId: String) = db.hosts().markConnected(hostId)

    // ---- identities ----

    /**
     * Save an identity. [password] semantics: null leaves any stored password as it is, an empty string removes
     * it, anything else replaces it.
     */
    suspend fun saveIdentity(identity: IdentityEntity, password: String?): IdentityEntity {
        var updated = identity
        when {
            password == null -> Unit
            password.isEmpty() -> {
                identity.passwordSecretId?.let { secrets.delete(it) }
                updated = identity.copy(passwordSecretId = null)
            }
            else -> {
                val secretId = identity.passwordSecretId ?: secrets.putString(password).also {
                    updated = identity.copy(passwordSecretId = it)
                }
                if (identity.passwordSecretId != null) secrets.put(secretId, password.toByteArray())
            }
        }
        db.identities().upsert(updated)
        return updated
    }

    suspend fun deleteIdentity(identity: IdentityEntity) {
        db.identities().delete(identity)
        identity.passwordSecretId?.let { secrets.delete(it) }
    }

    suspend fun password(identity: IdentityEntity): String? = identity.passwordSecretId?.let { secrets.getString(it) }

    // ---- keys ----

    suspend fun generateKey(name: String, algorithm: KeyAlgorithm): KeyEntity {
        val parsed = withContext(Dispatchers.Default) { KeyCodec.generate(algorithm, name) }
        return storeKey(name, parsed)
    }

    /** Import a private key; throws [me.nick.squeezessh.crypto.PassphraseRequiredException] when one is needed. */
    suspend fun importKey(name: String, text: String, passphrase: String?): KeyEntity {
        val parsed = withContext(Dispatchers.Default) { KeyCodec.parse(text, passphrase, name) }
        db.keys().findByFingerprint(parsed.fingerprint)?.let { existing ->
            throw IllegalStateException("This key is already saved as \"${existing.name}\"")
        }
        return storeKey(name, parsed)
    }

    private suspend fun storeKey(name: String, parsed: ParsedKey): KeyEntity {
        val pem = KeyCodec.toOpenSshPrivateKey(parsed.keyPair, name)
        val secretId = secrets.putString(pem)
        val entity = KeyEntity(
            name = name,
            type = parsed.type,
            publicKey = parsed.publicKey,
            fingerprint = parsed.fingerprint,
            privateKeySecretId = secretId,
        )
        db.keys().upsert(entity)
        return entity
    }

    suspend fun renameKey(key: KeyEntity, name: String) = db.keys().upsert(key.copy(name = name))

    suspend fun deleteKey(key: KeyEntity) {
        db.withTransaction { db.keys().delete(key) }
        secrets.delete(key.privateKeySecretId)
    }

    /** The decrypted key pair, for handing to the SSH client. */
    suspend fun keyPair(key: KeyEntity): KeyPair? {
        val pem = secrets.getString(key.privateKeySecretId) ?: return null
        return withContext(Dispatchers.Default) { KeyCodec.parse(pem).keyPair }
    }

    /** The unencrypted OpenSSH private key text (for export / Bitwarden sync). */
    suspend fun privateKeyText(key: KeyEntity): String? = secrets.getString(key.privateKeySecretId)
}
