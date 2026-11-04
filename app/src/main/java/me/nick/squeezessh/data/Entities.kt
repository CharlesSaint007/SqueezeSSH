package me.nick.squeezessh.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A saved SSH host. IDs are UUID strings rather than auto-increment ints so a later export/import can merge
 * configs from two devices without collisions.
 */
@Entity(
    tableName = "hosts",
    foreignKeys = [
        ForeignKey(
            entity = IdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identityId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpHostId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("identityId"), Index("jumpHostId")],
)
data class HostEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val nickname: String,
    val address: String,
    val port: Int = 22,
    /** Used when no identity is set; the connect flow then asks for a password. */
    val username: String? = null,
    val identityId: String? = null,
    /** Connect through this host first (ProxyJump). */
    val jumpHostId: String? = null,
    val group: String? = null,
    /** Overrides the global terminal theme for this host; null means use the global one. */
    val themeId: String? = null,
    val keepaliveSeconds: Int = 30,
    val agentForwarding: Boolean = false,
    val compression: Boolean = false,
    val lastConnectedAt: Long? = null,
    val connectCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A reusable login: a username plus a password and/or a key. The password is never stored here; it lives in
 * the encrypted [SecretStore] under [passwordSecretId].
 */
@Entity(
    tableName = "identities",
    foreignKeys = [
        ForeignKey(
            entity = KeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("keyId")],
)
data class IdentityEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val nickname: String,
    val username: String,
    val keyId: String? = null,
    val passwordSecretId: String? = null,
)

/**
 * An SSH key pair. The public half is stored in the clear so it can be shown and copied; the private half is
 * encrypted in the [SecretStore] under [privateKeySecretId].
 */
@Entity(tableName = "keys")
data class KeyEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa", ... */
    val type: String,
    /** The OpenSSH one-line public key (`<type> <base64> <comment>`). */
    val publicKey: String,
    /** SHA256 fingerprint, as `ssh-keygen -l` prints it. */
    val fingerprint: String,
    val privateKeySecretId: String,
    /** The Bitwarden/Vaultwarden SSH Key item this key is linked to, once synced. */
    val bitwardenCipherId: String? = null,
    /** The item's revision date as of the last sync; a different one on the server means it changed there. */
    val bitwardenRevisionDate: String? = null,
    /** Whether this key takes part in Bitwarden sync. */
    @ColumnInfo(defaultValue = "1") val bitwardenSync: Boolean = true,
    /** Changed here since the last sync, so the change still has to be sent to the vault. */
    @ColumnInfo(defaultValue = "0") val bitwardenDirty: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    /** When the key was last changed on this device; breaks ties when both sides changed. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

/** A host key we have seen and the user accepted, keyed by `host:port` like OpenSSH's known_hosts. */
@Entity(tableName = "known_hosts", primaryKeys = ["hostPort", "keyType"])
data class KnownHostEntity(
    /** `host` for port 22, `[host]:port` otherwise (OpenSSH convention). */
    val hostPort: String,
    val keyType: String,
    val fingerprint: String,
    /** The full public key, base64 of the SSH wire encoding. */
    val publicKey: String,
    val addedAt: Long = System.currentTimeMillis(),
)
