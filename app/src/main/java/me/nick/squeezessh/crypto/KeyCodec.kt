package me.nick.squeezessh.crypto

import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.putty.PuttyKeyUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.KeyPair

enum class KeyAlgorithm(val label: String, val sshType: String, val bits: Int) {
    Ed25519("Ed25519 (recommended)", "ssh-ed25519", 256),
    Ecdsa256("ECDSA P-256", "ecdsa-sha2-nistp256", 256),
    Ecdsa384("ECDSA P-384", "ecdsa-sha2-nistp384", 384),
    Rsa4096("RSA 4096", "ssh-rsa", 4096),
}

/** The result of reading key material: the pair plus its OpenSSH public-key line and fingerprint. */
class ParsedKey(val keyPair: KeyPair, val type: String, val publicKey: String, val fingerprint: String)

class KeyImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the key is encrypted and no passphrase (or the wrong one) was supplied. */
class PassphraseRequiredException(val wrongPassphrase: Boolean) :
    Exception(if (wrongPassphrase) "Wrong passphrase" else "This key is protected by a passphrase")

/**
 * Generates, parses and serializes SSH keys. Keys are always stored as unencrypted OpenSSH private keys inside
 * the [SecretStore]; a passphrase is only needed when importing a key that has one.
 */
object KeyCodec {

    fun generate(algorithm: KeyAlgorithm, comment: String): ParsedKey =
        describe(KeyUtils.generateKeyPair(algorithm.sshType, algorithm.bits), comment)

    /**
     * Read a private key in OpenSSH (`-----BEGIN OPENSSH PRIVATE KEY-----`), PEM (PKCS#1/PKCS#8) or PuTTY
     * `.ppk` format.
     */
    fun parse(text: String, passphrase: String? = null, comment: String = ""): ParsedKey {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw KeyImportException("No key data")
        var askedForPassphrase = false
        val passwordProvider = FilePasswordProvider { _, _, _ ->
            askedForPassphrase = true
            passphrase ?: throw PassphraseRequiredException(wrongPassphrase = false)
        }
        val pairs = try {
            if (trimmed.startsWith("PuTTY-User-Key-File-")) {
                PuttyKeyUtils.DEFAULT_INSTANCE.loadKeyPairs(null, NamedResource.ofName("import"), passwordProvider, trimmed)
            } else {
                SecurityUtils.loadKeyPairIdentities(
                    null,
                    NamedResource.ofName("import"),
                    ByteArrayInputStream(trimmed.toByteArray()),
                    passwordProvider,
                )?.toList()
            }
        } catch (e: PassphraseRequiredException) {
            throw e
        } catch (e: GeneralSecurityException) {
            if (askedForPassphrase && passphrase != null) throw PassphraseRequiredException(wrongPassphrase = true)
            throw KeyImportException("Could not read key: ${e.message}", e)
        } catch (e: Exception) {
            val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is PassphraseRequiredException }
            if (cause != null) throw cause
            if (askedForPassphrase && passphrase != null) throw PassphraseRequiredException(wrongPassphrase = true)
            throw KeyImportException("Not a supported private key (OpenSSH, PEM or PuTTY .ppk)", e)
        }
        val pair = pairs?.firstOrNull() ?: throw KeyImportException("No private key found in the data")
        return describe(pair, comment)
    }

    /** Unencrypted OpenSSH private key text, as `ssh-keygen` writes it. */
    fun toOpenSshPrivateKey(pair: KeyPair, comment: String): String {
        val out = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, comment, null, out)
        return out.toString(Charsets.UTF_8.name())
    }

    fun describe(pair: KeyPair, comment: String): ParsedKey {
        val type = KeyUtils.getKeyType(pair)
        val base = PublicKeyEntry.toString(pair.public)
        val publicLine = if (comment.isBlank()) base else "$base ${comment.trim()}"
        return ParsedKey(pair, type, publicLine, KeyUtils.getFingerPrint(pair.public))
    }
}
