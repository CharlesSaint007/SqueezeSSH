package me.nick.squeezessh.bitwarden

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BitwardenCryptoException(message: String) : Exception(message)

/** How the account turns the master password into the master key; reported by the server at login. */
data class KdfParams(val type: Int, val iterations: Int, val memoryMb: Int? = null, val parallelism: Int? = null) {
    companion object {
        const val PBKDF2 = 0
        const val ARGON2ID = 1
    }
}

/** A 64-byte Bitwarden symmetric key: AES-256 encryption key followed by an HMAC-SHA256 key. */
class SymmetricKey(val encKey: ByteArray, val macKey: ByteArray?) {
    val bytes: ByteArray get() = encKey + (macKey ?: ByteArray(0))

    companion object {
        fun of(bytes: ByteArray): SymmetricKey = when (bytes.size) {
            64 -> SymmetricKey(bytes.copyOfRange(0, 32), bytes.copyOfRange(32, 64))
            32 -> SymmetricKey(bytes, null)
            else -> throw BitwardenCryptoException("Unexpected key length ${bytes.size}")
        }
    }
}

/**
 * The parts of the Bitwarden vault crypto that SqueezeSSH needs, after the official clients'
 * `key-generation.service.ts` and `enc-string.ts`:
 *
 * - master key = PBKDF2-SHA256(password, email) or Argon2id(password, SHA-256(email))
 * - stretched master key = HKDF-Expand(master key, "enc") || HKDF-Expand(master key, "mac")
 * - "EncStrings" of type 2: `2.<iv>|<ciphertext>|<mac>`, AES-256-CBC with an HMAC-SHA256 over iv||ciphertext
 *
 * Only personal vault items are handled, so the RSA (organization key) types are not implemented.
 */
object BwCrypto {
    private val random = SecureRandom()
    private val b64 = Base64.getDecoder()
    private val b64e = Base64.getEncoder()

    fun masterKey(password: String, salt: String, kdf: KdfParams): ByteArray {
        val pw = password.toByteArray(Charsets.UTF_8)
        val saltBytes = salt.trim().lowercase().toByteArray(Charsets.UTF_8)
        return when (kdf.type) {
            KdfParams.PBKDF2 -> PKCS5S2ParametersGenerator(SHA256Digest()).run {
                init(pw, saltBytes, kdf.iterations)
                (generateDerivedParameters(256) as KeyParameter).key
            }
            KdfParams.ARGON2ID -> {
                val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(kdf.iterations)
                    .withMemoryAsKB((kdf.memoryMb ?: 64) * 1024)
                    .withParallelism(kdf.parallelism ?: 4)
                    .withSalt(MessageDigest.getInstance("SHA-256").digest(saltBytes))
                    .build()
                ByteArray(32).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, it) }
            }
            else -> throw BitwardenCryptoException("Unsupported key derivation type ${kdf.type}")
        }
    }

    fun stretch(masterKey: ByteArray): SymmetricKey =
        SymmetricKey(hkdfExpand(masterKey, "enc"), hkdfExpand(masterKey, "mac"))

    private fun hkdfExpand(prk: ByteArray, info: String): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters.skipExtractParameters(prk, info.toByteArray()))
        return ByteArray(32).also { gen.generateBytes(it, 0, it.size) }
    }

    /**
     * Decrypt the account's user key, which the server stores encrypted with the stretched master key (or, for
     * very old accounts, type 0 with the raw master key). A MAC failure here means a wrong master password.
     */
    fun decryptUserKey(encryptedUserKey: String, masterKey: ByteArray): SymmetricKey {
        val key = if (encryptedUserKey.startsWith("0.")) SymmetricKey(masterKey, null) else stretch(masterKey)
        val raw = try {
            decrypt(encryptedUserKey, key)
        } catch (e: BitwardenCryptoException) {
            throw BitwardenCryptoException("Wrong master password")
        }
        return SymmetricKey.of(raw)
    }

    fun encrypt(plaintext: ByteArray, key: SymmetricKey): String {
        val mac = key.macKey ?: throw BitwardenCryptoException("Refusing to encrypt without a MAC key")
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext)
        return "2.${b64e.encodeToString(iv)}|${b64e.encodeToString(ct)}|${b64e.encodeToString(hmac(mac, iv, ct))}"
    }

    fun encryptString(plaintext: String, key: SymmetricKey) = encrypt(plaintext.toByteArray(Charsets.UTF_8), key)

    fun decrypt(encString: String, key: SymmetricKey): ByteArray {
        val dot = encString.indexOf('.')
        // Strings without a type prefix are legacy type 0.
        val type = if (dot > 0) encString.substring(0, dot).toIntOrNull() else 0
        val parts = (if (dot > 0) encString.substring(dot + 1) else encString).split('|')
        val (iv, ct) = when (type) {
            0 -> {
                if (parts.size != 2) throw BitwardenCryptoException("Malformed encrypted value")
                b64.decode(parts[0]) to b64.decode(parts[1])
            }
            2 -> {
                if (parts.size != 3) throw BitwardenCryptoException("Malformed encrypted value")
                val mac = key.macKey ?: throw BitwardenCryptoException("Key has no MAC key")
                val iv = b64.decode(parts[0])
                val ct = b64.decode(parts[1])
                if (!MessageDigest.isEqual(hmac(mac, iv, ct), b64.decode(parts[2]))) {
                    throw BitwardenCryptoException("Encrypted value failed its integrity check")
                }
                iv to ct
            }
            else -> throw BitwardenCryptoException("Unsupported encryption type $type")
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        return try {
            cipher.doFinal(ct)
        } catch (e: Exception) {
            throw BitwardenCryptoException("Could not decrypt value")
        }
    }

    fun decryptString(encString: String, key: SymmetricKey) = decrypt(encString, key).toString(Charsets.UTF_8)

    private fun hmac(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(iv)
            doFinal(ct)
        }
}
