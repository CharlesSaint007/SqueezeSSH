package me.nick.squeezessh.bitwarden

import kotlinx.serialization.json.JsonObject
import me.nick.squeezessh.data.KeyEntity
import java.time.Instant

/** A decrypted SSH Key item from the vault. [raw] is the item as the server sent it, for fields we pass through. */
class RemoteSshKey(
    val id: String,
    val name: String,
    val privateKey: String,
    val publicKey: String,
    val fingerprint: String,
    val revisionDate: String,
    val folderId: String?,
    val raw: JsonObject,
    /** The item's own key when it uses per-item encryption; otherwise fields are under the user key. */
    val itemKey: SymmetricKey?,
)

sealed class SyncAction {
    /** A vault item this device doesn't have yet. */
    data class Import(val remote: RemoteSshKey) : SyncAction()

    /** The same key exists on both sides but was never linked (same fingerprint). */
    data class Link(val local: KeyEntity, val remote: RemoteSshKey) : SyncAction()

    /** The vault copy changed; take it. */
    data class Pull(val local: KeyEntity, val remote: RemoteSshKey, val conflict: Boolean = false) : SyncAction()

    /** A local key the vault doesn't have yet. */
    data class Create(val local: KeyEntity) : SyncAction()

    /** The local copy changed; send it. */
    data class Push(val local: KeyEntity, val remote: RemoteSshKey, val conflict: Boolean = false) : SyncAction()

    /** The vault item was deleted (or trashed); keep the key here but stop syncing it so it isn't re-uploaded. */
    data class RemoteDeleted(val local: KeyEntity) : SyncAction()
}

/**
 * Decides what a sync does, without doing it. Rules:
 *
 * - Keys are matched by vault item ID once linked, otherwise by fingerprint.
 * - A side "changed" if the vault revision date differs from the one recorded at the last sync (vault side),
 *   or the key was edited here since (local side). When both changed, the newer edit wins.
 * - New vault items are imported only from [folderId] when [folderOnly] is set; linked items are followed
 *   wherever they are moved in the vault.
 * - Keys with sync turned off are left alone, and a vault copy of one is not imported either.
 * - A key restored from a backup without its private half is pulled from the vault, never pushed.
 */
object SyncPlanner {
    fun plan(locals: List<KeyEntity>, remotes: List<RemoteSshKey>, folderId: String?, folderOnly: Boolean): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        val byId = remotes.associateBy { it.id }
        val claimed = mutableSetOf<String>()

        for (local in locals) {
            val cipherId = local.bitwardenCipherId ?: continue
            if (!local.bitwardenSync) continue
            val remote = byId[cipherId]
            if (remote == null) {
                actions += SyncAction.RemoteDeleted(local)
                continue
            }
            claimed += cipherId
            val remoteChanged = !sameInstant(remote.revisionDate, local.bitwardenRevisionDate)
            actions += when {
                // Restored from a backup without its private key: only the vault can supply it.
                !local.hasPrivateKey -> SyncAction.Pull(local, remote)
                remoteChanged && local.bitwardenDirty -> {
                    if (parse(remote.revisionDate) >= Instant.ofEpochMilli(local.updatedAt)) {
                        SyncAction.Pull(local, remote, conflict = true)
                    } else {
                        SyncAction.Push(local, remote, conflict = true)
                    }
                }
                remoteChanged -> SyncAction.Pull(local, remote)
                local.bitwardenDirty -> SyncAction.Push(local, remote)
                else -> continue
            }
        }

        for (local in locals) {
            if (local.bitwardenCipherId != null || !local.bitwardenSync) continue
            val match = remotes.firstOrNull { it.id !in claimed && it.fingerprint == local.fingerprint }
            if (match != null) {
                claimed += match.id
                actions += SyncAction.Link(local, match)
            } else if (local.hasPrivateKey) {
                actions += SyncAction.Create(local)
            }
        }

        val knownFingerprints = locals.map { it.fingerprint }.toMutableSet()
        for (remote in remotes) {
            if (remote.id in claimed) continue
            // No folder yet means nothing is in it; null must not match the vault's unfoldered items.
            if (folderOnly && (folderId == null || remote.folderId != folderId)) continue
            // Also guards against two vault items holding the same key.
            if (!knownFingerprints.add(remote.fingerprint)) continue
            actions += SyncAction.Import(remote)
        }
        return actions
    }

    private fun parse(date: String): Instant = Instant.parse(date)

    private fun sameInstant(a: String, b: String?): Boolean =
        b != null && (a == b || runCatching { parse(a) == parse(b) }.getOrDefault(false))
}
