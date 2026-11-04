package me.nick.squeezessh.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.squeezessh.json` backup file. Its own types rather than the Room entities, so the file format stays
 * put when the database schema changes. Everything except [secrets] is plain JSON; [secrets] is present only
 * when the user chose to include passwords and private keys, and is encrypted with their passphrase.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val hosts: List<HostBackup> = emptyList(),
    val identities: List<IdentityBackup> = emptyList(),
    val keys: List<KeyBackup> = emptyList(),
    val forwards: List<ForwardBackup> = emptyList(),
    val snippets: List<SnippetBackup> = emptyList(),
    val knownHosts: List<KnownHostBackup> = emptyList(),
    val settings: SettingsBackup? = null,
    val secrets: EncryptedSecrets? = null,
) {
    companion object {
        const val FORMAT = "squeezessh-backup"
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class HostBackup(
    val id: String,
    val nickname: String,
    val address: String,
    val port: Int = 22,
    val username: String? = null,
    val identityId: String? = null,
    val jumpHostId: String? = null,
    val group: String? = null,
    val themeId: String? = null,
    val keepaliveSeconds: Int = 30,
    val agentForwarding: Boolean = false,
    val compression: Boolean = false,
    val startupSnippetId: String? = null,
    val lastConnectedAt: Long? = null,
    val connectCount: Int = 0,
    val createdAt: Long = 0,
)

@Serializable
data class IdentityBackup(val id: String, val nickname: String, val username: String, val keyId: String? = null)

/** A key's public half and Bitwarden link; the private half is only ever in [Secrets.privateKeys]. */
@Serializable
data class KeyBackup(
    val id: String,
    val name: String,
    val type: String,
    val publicKey: String,
    val fingerprint: String,
    val bitwardenCipherId: String? = null,
    val bitwardenSync: Boolean = true,
    val createdAt: Long = 0,
)

@Serializable
data class ForwardBackup(
    val id: String,
    val hostId: String,
    val type: String,
    val bindAddress: String,
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
    val autoStart: Boolean = true,
)

@Serializable
data class SnippetBackup(val id: String, val name: String, val text: String, val sendEnter: Boolean = true)

@Serializable
data class KnownHostBackup(val hostPort: String, val keyType: String, val fingerprint: String, val publicKey: String, val addedAt: Long = 0)

@Serializable
data class SettingsBackup(
    val terminalThemeId: String? = null,
    val uiThemeMode: String? = null,
    val uiThemeId: String? = null,
    val fontSizeSp: Int? = null,
    val exitBehavior: String? = null,
    val keepAwake: Boolean? = null,
    val volumeKeysFontSize: Boolean? = null,
)

/** The decrypted contents of [BackupFile.secrets]. */
@Serializable
data class Secrets(
    /** Identity ID → password. */
    val passwords: Map<String, String> = emptyMap(),
    /** Key ID → unencrypted OpenSSH private key. */
    val privateKeys: Map<String, String> = emptyMap(),
)

/** Argon2id-derived AES-256-GCM encryption of a [Secrets] JSON document. */
@Serializable
data class EncryptedSecrets(
    val kdf: String = "argon2id",
    @SerialName("iterations") val iterations: Int,
    @SerialName("memoryKiB") val memoryKiB: Int,
    val parallelism: Int,
    val salt: String,
    val cipher: String = "aes-256-gcm",
    val nonce: String,
    val ciphertext: String,
)

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
class WrongPassphraseException : Exception("Wrong passphrase")

/** Reads and writes [BackupFile]s, and seals and opens their secrets. */
object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** Bound into the ciphertext so the secrets blob can't be moved into a different kind of file. */
    private val AAD = "squeezessh-backup-v1".toByteArray()

    /** OWASP's first recommended Argon2id setting: 3 passes over 64 MiB, 4 lanes. */
    data class KdfParams(val iterations: Int = 3, val memoryKiB: Int = 64 * 1024, val parallelism: Int = 4)

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: Exception) {
            throw BackupFormatException("Not a SqueezeSSH backup file", e)
        }
        if (file.format != BackupFile.FORMAT) throw BackupFormatException("Not a SqueezeSSH backup file")
        if (file.schemaVersion > BackupFile.SCHEMA_VERSION) {
            throw BackupFormatException("This backup is from a newer version of SqueezeSSH; update the app to import it")
        }
        return file
    }

    fun seal(secrets: Secrets, passphrase: String, params: KdfParams = KdfParams()): EncryptedSecrets {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, params.iterations, params.memoryKiB, params.parallelism)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        val ct = cipher.doFinal(json.encodeToString(Secrets.serializer(), secrets).toByteArray(Charsets.UTF_8))
        key.fill(0)
        return EncryptedSecrets(
            iterations = params.iterations,
            memoryKiB = params.memoryKiB,
            parallelism = params.parallelism,
            salt = b64.encodeToString(salt),
            nonce = b64.encodeToString(nonce),
            ciphertext = b64.encodeToString(ct),
        )
    }

    fun open(sealed: EncryptedSecrets, passphrase: String): Secrets {
        if (sealed.kdf != "argon2id" || sealed.cipher != "aes-256-gcm") {
            throw BackupFormatException("Unsupported encryption in this backup (${sealed.kdf}/${sealed.cipher})")
        }
        // Refuse settings that would take the phone minutes or gigabytes, from a corrupt or hostile file.
        if (sealed.memoryKiB !in 8..(1024 * 1024) || sealed.iterations !in 1..64 || sealed.parallelism !in 1..16) {
            throw BackupFormatException("The backup's key settings are out of range")
        }
        val key = deriveKey(passphrase, unb64.decode(sealed.salt), sealed.iterations, sealed.memoryKiB, sealed.parallelism)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64.decode(sealed.nonce)))
            cipher.updateAAD(AAD)
            cipher.doFinal(unb64.decode(sealed.ciphertext))
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } finally {
            key.fill(0)
        }
        return json.decodeFromString(Secrets.serializer(), plain.toString(Charsets.UTF_8))
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int, memoryKiB: Int, parallelism: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryKiB)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        return ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(passphrase.toCharArray(), it) }
    }
}
