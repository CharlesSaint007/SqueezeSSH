package me.nick.squeezessh.bitwarden

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nick.squeezessh.crypto.SecretStore
import me.nick.squeezessh.data.KeyEntity
import me.nick.squeezessh.data.Vault
import java.util.Base64
import java.util.UUID

/**
 * The saved Bitwarden login. Kept whole in the [SecretStore], since it holds the API key secret and the
 * decrypted user key; the master password itself is only used once, at login, and never stored.
 */
@Serializable
data class BitwardenAccount(
    val serverUrl: String,
    val email: String,
    val userId: String,
    val clientId: String,
    val clientSecret: String,
    /** Base64 of the 64-byte user key that unlocks the vault. */
    val userKey: String,
    val deviceId: String,
    /** Only import vault SSH keys from the SqueezeSSH folder, rather than every SSH key in the vault. */
    val folderOnly: Boolean = true,
    val lastSyncAt: Long? = null,
)

sealed class SyncStatus {
    data object SignedOut : SyncStatus()

    data class SignedIn(
        val email: String,
        val serverUrl: String,
        val folderOnly: Boolean,
        val lastSyncAt: Long?,
        val syncing: Boolean = false,
        /** What the last sync did, e.g. "2 from vault, 1 to vault". */
        val summary: String? = null,
        val error: String? = null,
    ) : SyncStatus()
}

/**
 * Two-way sync between SqueezeSSH's keys and the SSH Key items in a Bitwarden or Vaultwarden vault.
 * See [SyncPlanner] for the rules; this class does the I/O and applies the plan.
 */
class BitwardenSync(private val vault: Vault, private val secrets: SecretStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var pending: Job? = null

    /** Reused until shortly before it expires; servers rate-limit API-key logins. Guarded by [mutex]. */
    private var cachedToken: Pair<String, Long>? = null

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)
    val status: StateFlow<SyncStatus> = _status

    init {
        vault.onKeysChanged = { requestSync() }
        scope.launch {
            val account = account()
            if (account != null) {
                _status.value = idle(account, null)
                sync()
            }
        }
    }

    private suspend fun account(): BitwardenAccount? =
        secrets.getString(ACCOUNT_SECRET)?.let { runCatching { json.decodeFromString<BitwardenAccount>(it) }.getOrNull() }

    private suspend fun save(account: BitwardenAccount) =
        secrets.put(ACCOUNT_SECRET, json.encodeToString(BitwardenAccount.serializer(), account).toByteArray())

    /** Log in, check the master password by unlocking the vault, then run a first sync. */
    suspend fun signIn(serverUrl: String, clientId: String, clientSecret: String, masterPassword: String) = mutex.withLock {
        val deviceId = account()?.deviceId ?: UUID.randomUUID().toString()
        val api = BitwardenApi(BitwardenServer.of(serverUrl), deviceId)
        val token = api.loginWithApiKey(clientId, clientSecret)
        val profile = api.profile(token.accessToken)
        val email = profile.str("email") ?: throw BitwardenApiException("The server returned no account email")
        val userKey = BwCrypto.decryptUserKey(token.encryptedUserKey, BwCrypto.masterKey(masterPassword, token.salt ?: email, token.kdf))
        val account = BitwardenAccount(
            serverUrl = serverUrl.trim().trimEnd('/'),
            email = email,
            userId = profile.str("id") ?: throw BitwardenApiException("The server returned no account id"),
            clientId = clientId.trim(),
            clientSecret = clientSecret.trim(),
            userKey = Base64.getEncoder().encodeToString(userKey.bytes),
            deviceId = deviceId,
        )
        save(account)
        cachedToken = token.accessToken to token.expiresAt
        _status.value = idle(account, null)
        // Runs on our own scope: the screen that called signIn() may be gone before the first sync finishes.
        requestSync(0)
    }

    suspend fun signOut() = mutex.withLock {
        secrets.delete(ACCOUNT_SECRET)
        cachedToken = null
        _status.value = SyncStatus.SignedOut
    }

    suspend fun setFolderOnly(folderOnly: Boolean) {
        mutex.withLock {
            val account = account() ?: return
            save(account.copy(folderOnly = folderOnly))
            _status.value = idle(account.copy(folderOnly = folderOnly), null)
        }
        requestSync(0)
    }

    /** Sync soon; repeated calls within [delayMs] collapse into one. */
    fun requestSync(delayMs: Long = 2_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            sync()
        }
    }

    private suspend fun sync() = mutex.withLock {
        val account = account() ?: return@withLock
        _status.value = idle(account, null).copy(syncing = true)
        _status.value = try {
            val summary = runSync(account)
            val updated = account.copy(lastSyncAt = System.currentTimeMillis())
            save(updated)
            idle(updated, summary)
        } catch (e: CancellationException) {
            _status.value = idle(account, null)
            throw e
        } catch (e: Exception) {
            idle(account, null).copy(error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Move a key's vault item to the vault trash (when the user deletes the key and asks for that). */
    suspend fun trash(key: KeyEntity) = mutex.withLock {
        val account = account() ?: return@withLock
        val cipherId = key.bitwardenCipherId ?: return@withLock
        val api = BitwardenApi(BitwardenServer.of(account.serverUrl), account.deviceId)
        withToken(api, account) { api.trashCipher(it, cipherId) }
    }

    /** Run [block] with a valid access token, logging in again once if the cached one was rejected. */
    private suspend fun <T> withToken(api: BitwardenApi, account: BitwardenAccount, block: suspend (String) -> T): T {
        val cached = cachedToken?.takeIf { it.second > System.currentTimeMillis() + 60_000 }?.first
        if (cached != null) {
            try {
                return block(cached)
            } catch (e: BitwardenApiException) {
                if (e.status != 401) throw e
            }
        }
        val token = api.loginWithApiKey(account.clientId, account.clientSecret)
        cachedToken = token.accessToken to token.expiresAt
        return block(token.accessToken)
    }

    private suspend fun runSync(account: BitwardenAccount): String {
        val api = BitwardenApi(BitwardenServer.of(account.serverUrl), account.deviceId)
        val userKey = SymmetricKey.of(Base64.getDecoder().decode(account.userKey))
        // The sync call proves the token is good, so the rest of this run can use it directly.
        var token = ""
        val data = withToken(api, account) { t -> api.sync(t).also { token = t } }

        var folderId = data.arr("folders").firstOrNull { f ->
            runCatching { BwCrypto.decryptString(f.str("name")!!, userKey) == FOLDER_NAME }.getOrDefault(false)
        }?.str("id")
        val remotes = data.arr("ciphers").mapNotNull { decryptSshKey(it, userKey) }
        val actions = SyncPlanner.plan(vault.allKeys(), remotes, folderId, account.folderOnly)

        var pulled = 0
        var pushed = 0
        var failed = 0
        for (action in actions) {
            try {
                when (action) {
                    is SyncAction.Import -> {
                        val r = action.remote
                        vault.addSyncedKey(r.name, r.privateKey, r.id, r.revisionDate)
                        pulled++
                    }
                    is SyncAction.Link -> vault.markSynced(action.local, action.remote.id, action.remote.revisionDate)
                    is SyncAction.Pull -> {
                        val r = action.remote
                        vault.applySyncedKey(action.local, r.name, r.privateKey, r.id, r.revisionDate)
                        pulled++
                    }
                    is SyncAction.Create -> {
                        if (folderId == null) {
                            folderId = api.createFolder(token, BwCrypto.encryptString(FOLDER_NAME, userKey)).str("id")
                        }
                        val body = cipherBody(action.local, account.userId, userKey, base = null, folderId = folderId) ?: continue
                        val created = api.createCipher(token, body)
                        vault.markSynced(action.local, created.str("id")!!, created.str("revisionDate")!!)
                        pushed++
                    }
                    is SyncAction.Push -> {
                        val r = action.remote
                        val body = cipherBody(action.local, account.userId, r.itemKey ?: userKey, base = r, folderId = r.folderId) ?: continue
                        val updated = api.updateCipher(token, r.id, body)
                        vault.markSynced(action.local, r.id, updated.str("revisionDate")!!)
                        pushed++
                    }
                    is SyncAction.RemoteDeleted -> vault.unlinkKey(action.local)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BitwardenApiException) {
                // Network and auth failures affect everything; stop and report.
                if (e.status == null || e.status == 401) throw e
                failed++
            } catch (e: Exception) {
                failed++
            }
        }
        return buildList {
            add(if (pulled + pushed == 0) "Up to date" else "$pulled from vault, $pushed to vault")
            if (failed > 0) add("$failed failed")
        }.joinToString(", ")
    }

    /** Decrypt one vault item if it is a personal SSH Key that is not in the trash; null for anything else. */
    private fun decryptSshKey(cipher: JsonObject, userKey: SymmetricKey): RemoteSshKey? {
        if (cipher.int("type") != SSH_KEY_TYPE) return null
        if (cipher.str("deletedDate") != null || cipher.str("organizationId") != null) return null
        return runCatching {
            val itemKey = cipher.str("key")?.let { SymmetricKey.of(BwCrypto.decrypt(it, userKey)) }
            val key = itemKey ?: userKey
            val ssh = cipher.obj("sshKey")!!
            RemoteSshKey(
                id = cipher.str("id")!!,
                name = BwCrypto.decryptString(cipher.str("name")!!, key),
                privateKey = BwCrypto.decryptString(ssh.str("privateKey")!!, key),
                publicKey = BwCrypto.decryptString(ssh.str("publicKey")!!, key),
                fingerprint = BwCrypto.decryptString(ssh.str("keyFingerprint")!!, key),
                revisionDate = cipher.str("revisionDate")!!,
                folderId = cipher.str("folderId"),
                raw = cipher,
                itemKey = itemKey,
            )
        }.getOrNull()
    }

    /**
     * The create/update request for [local]. For an update, the vault item's other fields (notes, custom
     * fields, favorite, its per-item key) are passed through unchanged.
     */
    private suspend fun cipherBody(local: KeyEntity, userId: String, key: SymmetricKey, base: RemoteSshKey?, folderId: String?): JsonObject? {
        val privateKey = vault.privateKeyText(local) ?: return null
        val enc = { s: String -> JsonPrimitive(BwCrypto.encryptString(s, key)) }
        val raw = base?.raw
        return buildJsonObject {
            put("type", SSH_KEY_TYPE)
            put("encryptedFor", userId)
            put("folderId", folderId?.let(::JsonPrimitive) ?: JsonNull)
            put("name", enc(local.name))
            put("notes", raw?.field("notes") ?: JsonNull)
            raw?.field("fields")?.let { put("fields", it) }
            raw?.field("key")?.let { put("key", it) }
            put("favorite", (raw?.field("favorite") as? JsonPrimitive)?.content?.toBoolean() ?: false)
            put("reprompt", raw?.int("reprompt") ?: 0)
            put(
                "sshKey",
                buildJsonObject {
                    put("privateKey", enc(privateKey))
                    put("publicKey", enc(local.publicKey.split(' ').take(2).joinToString(" ")))
                    put("keyFingerprint", enc(local.fingerprint))
                },
            )
            base?.let { put("lastKnownRevisionDate", it.revisionDate) }
        }
    }

    private fun idle(a: BitwardenAccount, summary: String?) =
        SyncStatus.SignedIn(a.email, a.serverUrl, a.folderOnly, a.lastSyncAt, summary = summary)

    companion object {
        const val FOLDER_NAME = "SqueezeSSH"
        private const val SSH_KEY_TYPE = 5
        private const val ACCOUNT_SECRET = "bitwarden-account"
    }
}
