package me.nick.squeezessh.backup

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.nick.squeezessh.crypto.SecretStore
import me.nick.squeezessh.data.AppDatabase
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.ForwardType
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.IdentityEntity
import me.nick.squeezessh.data.KeyEntity
import me.nick.squeezessh.data.KnownHostEntity
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.settings.SettingsRepository
import me.nick.squeezessh.settings.UiThemeMode
import me.nick.squeezessh.ui.theme.CustomThemeJson

enum class ImportMode {
    /** Add what's in the file; items already here (same ID) are updated from the file, everything else is kept. */
    Merge,

    /** Delete everything here first, then restore the file. */
    Replace,
}

/** What an import did, for the summary shown afterwards. */
data class ImportResult(
    val hosts: Int,
    val identities: Int,
    val keys: Int,
    val snippets: Int,
    val forwards: Int,
    /** Keys restored without their private half; Bitwarden sync can fill these in. */
    val keysMissingPrivate: Int,
    val secretsRestored: Boolean,
)

/** Builds backups from the app's data and restores them. */
class BackupService(
    private val db: AppDatabase,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val appVersion: String,
) {

    /**
     * The whole backup, encrypted with [passphrase]. [includeSecrets] adds identity passwords and private keys
     * inside the same encryption.
     */
    suspend fun export(passphrase: String, includeSecrets: Boolean): String = withContext(Dispatchers.IO) {
        val keys = db.keys().getAll()
        val identities = db.identities().getAll()
        val included = if (includeSecrets) {
            val privateKeys = keys.filter { it.hasPrivateKey }.mapNotNull { k ->
                secrets.getString(k.privateKeySecretId)?.let { k.id to it }
            }.toMap()
            val passwords = identities.mapNotNull { i -> i.passwordSecretId?.let { id -> secrets.getString(id)?.let { i.id to it } } }.toMap()
            Secrets(passwords = passwords, privateKeys = privateKeys)
        } else {
            null
        }
        val s = settings.settings.first()
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            hosts = db.hosts().getAll().map { it.toBackup() },
            identities = identities.map { IdentityBackup(it.id, it.nickname, it.username, it.keyId) },
            keys = keys.map {
                KeyBackup(it.id, it.name, it.type, it.publicKey, it.fingerprint, it.bitwardenCipherId, it.bitwardenSync, it.createdAt)
            },
            forwards = db.forwards().getAll().map {
                ForwardBackup(it.id, it.hostId, it.type.name, it.bindAddress, it.bindPort, it.destHost, it.destPort, it.autoStart)
            },
            snippets = db.snippets().getAll().map { SnippetBackup(it.id, it.name, it.text, it.sendEnter) },
            knownHosts = db.knownHosts().getAll().map { KnownHostBackup(it.hostPort, it.keyType, it.fingerprint, it.publicKey, it.addedAt) },
            settings = SettingsBackup(
                s.terminalThemeId, s.uiThemeMode.name, s.uiThemeId, s.fontSizeSp, s.exitBehavior.name, s.keepAwake, s.volumeKeysFontSize,
                s.autoReconnect, s.customThemes.map(CustomThemeJson::of),
            ),
            includedSecrets = included,
        )
        BackupCodec.encodeEncrypted(file, passphrase)
    }

    /** Recognise a file without unlocking it. */
    fun read(text: String): ReadBackup = BackupCodec.read(text)

    /** Unlock an encrypted backup to see what it holds; throws [WrongPassphraseException]. */
    suspend fun unlock(file: EncryptedBackup, passphrase: String): BackupFile =
        withContext(Dispatchers.Default) { BackupCodec.decrypt(file, passphrase) }

    /**
     * Restore [file]. Its secrets come from [BackupFile.includedSecrets] (encrypted files, already unlocked), or
     * for an older unencrypted file from [BackupFile.secrets] opened with [passphrase]; null skips those (keys
     * then come back without their private half). Throws [WrongPassphraseException] before changing anything.
     */
    suspend fun import(file: BackupFile, mode: ImportMode, passphrase: String?): ImportResult = withContext(Dispatchers.IO) {
        val opened = file.includedSecrets
            ?: file.secrets?.takeIf { passphrase != null }?.let { BackupCodec.open(it, passphrase!!) }

        // Secrets are written outside the database, so collect what to write and what becomes unused, and
        // touch the secret files only once the database transaction has succeeded.
        val newSecrets = mutableListOf<Pair<String, ByteArray>>()
        val unusedSecrets = mutableListOf<String>()

        val existingKeys = db.keys().getAll()
        val existingIdentities = db.identities().getAll()
        if (mode == ImportMode.Replace) {
            existingKeys.filter { it.hasPrivateKey }.forEach { unusedSecrets += it.privateKeySecretId }
            existingIdentities.mapNotNullTo(unusedSecrets) { it.passwordSecretId }
        }
        val keysById = if (mode == ImportMode.Merge) existingKeys.associateBy { it.id } else emptyMap()
        val keysByFingerprint = if (mode == ImportMode.Merge) existingKeys.associateBy { it.fingerprint } else emptyMap()
        val identitiesById = if (mode == ImportMode.Merge) existingIdentities.associateBy { it.id } else emptyMap()

        // Keys: a key this phone already has (same ID, or the same key under another ID) keeps its private half
        // unless the file brings one; otherwise the file's private key, or a placeholder for sync to fill.
        val keyIdMap = mutableMapOf<String, String>()
        var missingPrivate = 0
        val keys = file.keys.map { k ->
            val local = keysById[k.id] ?: keysByFingerprint[k.fingerprint]
            keyIdMap[k.id] = local?.id ?: k.id
            val imported = opened?.privateKeys?.get(k.id)
            val secretId = when {
                imported != null -> newSecret(imported, newSecrets).also { if (local?.hasPrivateKey == true) unusedSecrets += local.privateKeySecretId }
                local != null -> local.privateKeySecretId
                else -> KeyEntity.MISSING_SECRET_PREFIX + k.id
            }
            if (secretId.startsWith(KeyEntity.MISSING_SECRET_PREFIX)) missingPrivate++
            KeyEntity(
                id = local?.id ?: k.id,
                name = k.name,
                type = k.type,
                publicKey = k.publicKey,
                fingerprint = k.fingerprint,
                privateKeySecretId = secretId,
                bitwardenCipherId = local?.bitwardenCipherId ?: k.bitwardenCipherId,
                // Let the next sync compare against the vault afresh rather than trust a stale date.
                bitwardenRevisionDate = local?.bitwardenRevisionDate,
                bitwardenSync = k.bitwardenSync,
                createdAt = k.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        }

        val identities = file.identities.map { i ->
            val local = identitiesById[i.id]
            val password = opened?.passwords?.get(i.id)
            val secretId = when {
                password != null -> newSecret(password, newSecrets).also { local?.passwordSecretId?.let(unusedSecrets::add) }
                else -> local?.passwordSecretId
            }
            IdentityEntity(i.id, i.nickname, i.username, i.keyId?.let { keyIdMap[it] ?: it }, secretId)
        }

        val validKeyIds = (keys.map { it.id } + if (mode == ImportMode.Merge) existingKeys.map { it.id } else emptyList()).toSet()
        val validIdentityIds = (identities.map { it.id } + if (mode == ImportMode.Merge) existingIdentities.map { it.id } else emptyList()).toSet()
        val snippets = file.snippets.map { SnippetEntity(it.id, it.name, it.text, it.sendEnter) }
        val validSnippetIds = snippets.map { it.id }.toSet() + if (mode == ImportMode.Merge) db.snippets().getAll().map { it.id } else emptyList()
        val hostIds = file.hosts.map { it.id }.toSet() + if (mode == ImportMode.Merge) db.hosts().getAll().map { it.id } else emptyList()

        val hosts = file.hosts.map { h ->
            HostEntity(
                id = h.id,
                nickname = h.nickname,
                address = h.address,
                port = h.port,
                username = h.username,
                // References that point outside the file (and outside this phone) are dropped, not left dangling.
                identityId = h.identityId?.takeIf { it in validIdentityIds },
                jumpHostId = h.jumpHostId?.takeIf { it in hostIds && it != h.id },
                group = h.group,
                themeId = h.themeId,
                keepaliveSeconds = h.keepaliveSeconds,
                agentForwarding = h.agentForwarding,
                compression = h.compression,
                startupSnippetId = h.startupSnippetId?.takeIf { it in validSnippetIds },
                lastConnectedAt = h.lastConnectedAt,
                connectCount = h.connectCount,
                createdAt = h.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        }
        val forwards = file.forwards.mapNotNull { f ->
            val type = runCatching { ForwardType.valueOf(f.type) }.getOrNull() ?: return@mapNotNull null
            if (f.hostId !in hostIds) return@mapNotNull null
            ForwardEntity(f.id, f.hostId, type, f.bindAddress, f.bindPort, f.destHost, f.destPort, f.autoStart)
        }
        val knownHosts = file.knownHosts.map { KnownHostEntity(it.hostPort, it.keyType, it.fingerprint, it.publicKey, it.addedAt) }

        db.withTransaction {
            if (mode == ImportMode.Replace) {
                // Children first, so foreign keys never point at a row that is already gone.
                db.forwards().deleteAll()
                db.hosts().deleteAll()
                db.identities().deleteAll()
                db.keys().deleteAll()
                db.snippets().deleteAll()
                db.knownHosts().deleteAll()
            }
            keys.forEach { db.keys().upsert(it) }
            identities.map { it.copy(keyId = it.keyId?.takeIf { id -> id in validKeyIds }) }.forEach { db.identities().upsert(it) }
            db.snippets().upsertAll(snippets)
            // Jump hosts reference other hosts: insert without them, then set them once every host exists.
            db.hosts().upsertAll(hosts.map { it.copy(jumpHostId = null) })
            db.hosts().upsertAll(hosts)
            forwards.groupBy { it.hostId }.forEach { (hostId, list) ->
                db.forwards().deleteForHost(hostId)
                db.forwards().upsertAll(list)
            }
            db.knownHosts().upsertAll(knownHosts)
        }
        newSecrets.forEach { (id, bytes) -> secrets.put(id, bytes) }
        unusedSecrets.forEach { secrets.delete(it) }

        file.settings?.let { settings.replace(it.toSettings(settings.settings.first())) }

        ImportResult(
            hosts = hosts.size,
            identities = identities.size,
            keys = keys.size,
            snippets = snippets.size,
            forwards = forwards.size,
            keysMissingPrivate = missingPrivate,
            secretsRestored = opened != null,
        )
    }

    private fun newSecret(value: String, out: MutableList<Pair<String, ByteArray>>): String {
        val id = java.util.UUID.randomUUID().toString()
        out += id to value.toByteArray(Charsets.UTF_8)
        return id
    }
}

private fun HostEntity.toBackup() = HostBackup(
    id, nickname, address, port, username, identityId, jumpHostId, group, themeId, keepaliveSeconds,
    agentForwarding, compression, startupSnippetId, lastConnectedAt, connectCount, createdAt,
)

/** Settings from a backup, falling back to [current] for anything the file leaves out or doesn't recognise. */
internal fun SettingsBackup.toSettings(current: Settings) = Settings(
    terminalThemeId = terminalThemeId ?: current.terminalThemeId,
    uiThemeMode = uiThemeMode?.let { runCatching { UiThemeMode.valueOf(it) }.getOrNull() } ?: current.uiThemeMode,
    uiThemeId = uiThemeId ?: current.uiThemeId,
    fontSizeSp = fontSizeSp ?: current.fontSizeSp,
    exitBehavior = exitBehavior?.let { runCatching { ExitBehavior.valueOf(it) }.getOrNull() } ?: current.exitBehavior,
    keepAwake = keepAwake ?: current.keepAwake,
    volumeKeysFontSize = volumeKeysFontSize ?: current.volumeKeysFontSize,
    autoReconnect = autoReconnect ?: current.autoReconnect,
    recentQuickConnects = current.recentQuickConnects,
    // A merge keeps this phone's own themes alongside the file's; same ID means the file's copy wins.
    customThemes = customThemes?.mapNotNull { it.toTheme() }
        ?.let { incoming -> incoming + current.customThemes.filter { c -> incoming.none { it.id == c.id } } }
        ?: current.customThemes,
)
