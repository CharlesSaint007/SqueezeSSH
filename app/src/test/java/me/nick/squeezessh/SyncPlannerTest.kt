package me.nick.squeezessh

import kotlinx.serialization.json.JsonObject
import me.nick.squeezessh.bitwarden.RemoteSshKey
import me.nick.squeezessh.bitwarden.SyncAction
import me.nick.squeezessh.bitwarden.SyncPlanner
import me.nick.squeezessh.data.KeyEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SyncPlannerTest {
    private val folder = "folder-1"

    private fun local(
        fp: String,
        cipherId: String? = null,
        rev: String? = null,
        dirty: Boolean = false,
        sync: Boolean = true,
        updatedAt: Long = 0,
        secret: String = "s",
    ) =
        KeyEntity(
            id = "local-$fp", name = fp, type = "ssh-ed25519", publicKey = "ssh-ed25519 AAAA", fingerprint = fp,
            privateKeySecretId = secret, bitwardenCipherId = cipherId, bitwardenRevisionDate = rev,
            bitwardenSync = sync, bitwardenDirty = dirty, updatedAt = updatedAt,
        )

    private fun remote(id: String, fp: String, rev: String = "2026-01-01T00:00:00Z", folderId: String? = folder) =
        RemoteSshKey(id, "remote $fp", "PRIVATE", "ssh-ed25519 AAAA", fp, rev, folderId, JsonObject(emptyMap()), null)

    private fun plan(locals: List<KeyEntity>, remotes: List<RemoteSshKey>, folderOnly: Boolean = true) =
        SyncPlanner.plan(locals, remotes, folder, folderOnly)

    @Test
    fun newKeysGoBothWays() {
        val actions = plan(listOf(local("A")), listOf(remote("r1", "B")))
        assertEquals(setOf("Create:A", "Import:B"), actions.map(::describe).toSet())
    }

    @Test
    fun sameKeyOnBothSidesIsLinkedNotDuplicated() {
        assertEquals(listOf("Link:A"), plan(listOf(local("A")), listOf(remote("r1", "A"))).map(::describe))
    }

    @Test
    fun unchangedLinkedKeyDoesNothing() {
        val rev = "2026-01-01T00:00:00Z"
        // Same instant written differently still counts as unchanged.
        assertEquals(emptyList<String>(), plan(listOf(local("A", "r1", "2026-01-01T00:00:00.000Z")), listOf(remote("r1", "A", rev))).map(::describe))
    }

    @Test
    fun oneSidedChangesFlowToTheOtherSide() {
        assertEquals(listOf("Pull:A"), plan(listOf(local("A", "r1", "2026-01-01T00:00:00Z")), listOf(remote("r1", "A", "2026-02-01T00:00:00Z"))).map(::describe))
        assertEquals(listOf("Push:A"), plan(listOf(local("A", "r1", "2026-01-01T00:00:00Z", dirty = true)), listOf(remote("r1", "A"))).map(::describe))
    }

    @Test
    fun conflictGoesToTheNewerEdit() {
        val older = Instant.parse("2026-01-15T00:00:00Z").toEpochMilli()
        val newer = Instant.parse("2026-03-01T00:00:00Z").toEpochMilli()
        val r = remote("r1", "A", "2026-02-01T00:00:00Z")
        val old = "2026-01-01T00:00:00Z"
        assertEquals(SyncAction.Pull::class, plan(listOf(local("A", "r1", old, dirty = true, updatedAt = older)), listOf(r)).single()::class)
        assertEquals(SyncAction.Push::class, plan(listOf(local("A", "r1", old, dirty = true, updatedAt = newer)), listOf(r)).single()::class)
    }

    @Test
    fun deletedVaultItemUnlinksInsteadOfReuploading() {
        assertEquals(listOf("RemoteDeleted:A"), plan(listOf(local("A", "r1", "2026-01-01T00:00:00Z")), emptyList()).map(::describe))
    }

    @Test
    fun folderFilterAppliesOnlyToNewImports() {
        val outside = remote("r1", "B", folderId = null)
        assertEquals(emptyList<String>(), plan(emptyList(), listOf(outside)).map(::describe))
        assertEquals(listOf("Import:B"), plan(emptyList(), listOf(outside), folderOnly = false).map(::describe))
        // Already linked: followed even after being moved out of the folder.
        val linked = local("B", "r1", "2025-01-01T00:00:00Z")
        assertEquals(listOf("Pull:B"), plan(listOf(linked), listOf(outside)).map(::describe))
    }

    @Test
    fun missingFolderImportsNothingWhenFolderOnly() {
        val unfoldered = remote("r1", "B", folderId = null)
        assertEquals(emptyList<String>(), SyncPlanner.plan(emptyList(), listOf(unfoldered), folderId = null, folderOnly = true).map(::describe))
    }

    @Test
    fun keysWithSyncOffAreLeftAloneAndNotReimported() {
        assertEquals(emptyList<String>(), plan(listOf(local("A", sync = false)), listOf(remote("r1", "A"))).map(::describe))
    }

    @Test
    fun keyRestoredWithoutPrivateHalfIsPulledNeverPushed() {
        val missing = KeyEntity.MISSING_SECRET_PREFIX + "x"
        // Linked and unchanged in the vault: still pulled, to get the private key back.
        val linked = local("A", "r1", "2026-01-01T00:00:00Z", dirty = true, secret = missing)
        assertEquals(listOf("Pull:A"), plan(listOf(linked), listOf(remote("r1", "A", "2026-01-01T00:00:00Z"))).map(::describe))
        // Not linked and not in the vault: nothing to upload.
        assertEquals(emptyList<String>(), plan(listOf(local("B", secret = missing)), emptyList()).map(::describe))
        // Not linked but the vault has it: linked (and the sync fills in the private key).
        assertEquals(listOf("Link:C"), plan(listOf(local("C", secret = missing)), listOf(remote("r3", "C"))).map(::describe))
    }

    @Test
    fun duplicateVaultItemsImportOnce() {
        assertEquals(listOf("Import:B"), plan(emptyList(), listOf(remote("r1", "B"), remote("r2", "B"))).map(::describe))
    }

    private fun describe(a: SyncAction) = when (a) {
        is SyncAction.Import -> "Import:${a.remote.fingerprint}"
        is SyncAction.Link -> "Link:${a.local.fingerprint}"
        is SyncAction.Pull -> "Pull:${a.local.fingerprint}"
        is SyncAction.Create -> "Create:${a.local.fingerprint}"
        is SyncAction.Push -> "Push:${a.local.fingerprint}"
        is SyncAction.RemoteDeleted -> "RemoteDeleted:${a.local.fingerprint}"
    }
}
