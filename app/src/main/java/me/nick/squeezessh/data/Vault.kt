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
    val snippets: Flow<List<SnippetEntity>> = db.snippets().observeAll()
    val knownHostCount: Flow<Int> = db.knownHosts().observeHostCount()

    suspend fun host(id: String) = db.hosts().get(id)
    suspend fun identity(id: String) = db.identities().get(id)
    suspend fun key(id: String) = db.keys().get(id)

    suspend fun saveHost(host: HostEntity) = db.hosts().upsert(host)

    /** Save a host together with its full list of port forwards, replacing any it had. */
    suspend fun saveHost(host: HostEntity, forwards: List<ForwardEntity>) = db.withTransaction {
        db.hosts().upsert(host)
        db.forwards().deleteForHost(host.id)
        db.forwards().upsertAll(forwards.map { it.copy(hostId = host.id) })
    }

    suspend fun forwards(hostId: String): List<ForwardEntity> = db.forwards().forHost(hostId)
    fun observeForwards(hostId: String): Flow<List<ForwardEntity>> = db.forwards().observeForHost(hostId)

    // ---- snippets ----

    suspend fun snippet(id: String) = db.snippets().get(id)
    suspend fun saveSnippet(snippet: SnippetEntity) = db.snippets().upsert(snippet)
    suspend fun deleteSnippet(snippet: SnippetEntity) = db.snippets().delete(snippet)
    suspend fun deleteHost(host: HostEntity) = db.hosts().delete(host)

    /** Forget every saved host key; each host asks to be trusted again on its next connection. */
    suspend fun clearKnownHosts() = db.knownHosts().deleteAll()
    suspend fun markConnected(hostId: String) = db.hosts().markConnected(hostId)
    suspend fun setKeyboardAutocomplete(hostId: String, on: Boolean) = db.hosts().setKeyboardAutocomplete(hostId, on)

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

    private suspend fun storeKey(
        name: String,
        parsed: ParsedKey,
        cipherId: String? = null,
        revisionDate: String? = null,
    ): KeyEntity {
        val pem = KeyCodec.toOpenSshPrivateKey(parsed.keyPair, name)
        val secretId = secrets.putString(pem)
        val entity = KeyEntity(
            name = name,
            type = parsed.type,
            publicKey = parsed.publicKey,
            fingerprint = parsed.fingerprint,
            privateKeySecretId = secretId,
            bitwardenCipherId = cipherId,
            bitwardenRevisionDate = revisionDate,
        )
        db.keys().upsert(entity)
        if (cipherId == null) onKeysChanged()
        return entity
    }

    suspend fun renameKey(key: KeyEntity, name: String) {
        db.keys().upsert(key.copy(name = name, bitwardenDirty = key.bitwardenCipherId != null, updatedAt = now()))
        onKeysChanged()
    }

    /** Turn Bitwarden sync on or off for one key. Turning it off unlinks the key but leaves the vault item. */
    suspend fun setKeySync(key: KeyEntity, enabled: Boolean) {
        val updated = if (enabled) {
            key.copy(bitwardenSync = true)
        } else {
            key.copy(bitwardenSync = false, bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenDirty = false)
        }
        db.keys().upsert(updated)
        if (enabled) onKeysChanged()
    }

    suspend fun deleteKey(key: KeyEntity) {
        db.withTransaction { db.keys().delete(key) }
        secrets.delete(key.privateKeySecretId)
    }

    // ---- Bitwarden sync support ----

    /** Called after a local key change that sync should send to the vault; set by the sync manager. */
    var onKeysChanged: () -> Unit = {}

    suspend fun allKeys(): List<KeyEntity> = db.keys().getAll()

    /** Store a key that came from the vault, already linked to its item. */
    suspend fun addSyncedKey(name: String, privateKey: String, cipherId: String, revisionDate: String): KeyEntity {
        val parsed = withContext(Dispatchers.Default) { KeyCodec.parse(privateKey, null, name) }
        return storeKey(name, parsed, cipherId, revisionDate)
    }

    /** Replace a key's name and key material with the vault's copy. */
    suspend fun applySyncedKey(key: KeyEntity, name: String, privateKey: String, cipherId: String, revisionDate: String) {
        val parsed = withContext(Dispatchers.Default) { KeyCodec.parse(privateKey, null, name) }
        secrets.putString(KeyCodec.toOpenSshPrivateKey(parsed.keyPair, name)).let { newSecret ->
            db.keys().upsert(
                key.copy(
                    name = name,
                    type = parsed.type,
                    publicKey = parsed.publicKey,
                    fingerprint = parsed.fingerprint,
                    privateKeySecretId = newSecret,
                    bitwardenCipherId = cipherId,
                    bitwardenRevisionDate = revisionDate,
                    bitwardenDirty = false,
                ),
            )
            if (newSecret != key.privateKeySecretId) secrets.delete(key.privateKeySecretId)
        }
    }

    /** Record that [key] now matches vault item [cipherId] at [revisionDate]. */
    suspend fun markSynced(key: KeyEntity, cipherId: String, revisionDate: String) {
        // Re-read so an edit made while the request was in flight is not lost; it stays dirty for the next sync.
        val current = db.keys().get(key.id) ?: return
        val editedMeanwhile = current.name != key.name
        db.keys().upsert(current.copy(bitwardenCipherId = cipherId, bitwardenRevisionDate = revisionDate, bitwardenDirty = editedMeanwhile))
    }

    /** The vault item is gone: keep the key, but stop syncing it so it isn't uploaded again. */
    suspend fun unlinkKey(key: KeyEntity) {
        db.keys().upsert(key.copy(bitwardenCipherId = null, bitwardenRevisionDate = null, bitwardenDirty = false, bitwardenSync = false))
    }

    private fun now() = System.currentTimeMillis()

    /** The decrypted key pair, for handing to the SSH client. */
    suspend fun keyPair(key: KeyEntity): KeyPair? {
        val pem = secrets.getString(key.privateKeySecretId) ?: return null
        return withContext(Dispatchers.Default) { KeyCodec.parse(pem).keyPair }
    }

    /** The unencrypted OpenSSH private key text (for export / Bitwarden sync). */
    suspend fun privateKeyText(key: KeyEntity): String? = secrets.getString(key.privateKeySecretId)
}
