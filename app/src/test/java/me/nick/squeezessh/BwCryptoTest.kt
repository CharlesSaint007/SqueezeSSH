package me.nick.squeezessh

import me.nick.squeezessh.bitwarden.BitwardenCryptoException
import me.nick.squeezessh.bitwarden.BwCrypto
import me.nick.squeezessh.bitwarden.KdfParams
import me.nick.squeezessh.bitwarden.SymmetricKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Vectors computed independently with Node's built-in crypto (pbkdf2Sync, argon2Sync, HMAC-based HKDF). */
class BwCryptoTest {
    private val password = "correct horse battery staple"
    private val email = "  Test@Example.com "

    @Test
    fun pbkdf2MasterKeyUsesTrimmedLowercaseEmailAsSalt() {
        val key = BwCrypto.masterKey(password, email, KdfParams(KdfParams.PBKDF2, 5000))
        assertEquals("2a586eb50539298e261d82ab1d30a06ef59146c32f6fd35a2a01eafdbfe3a42b", key.hex())
    }

    @Test
    fun argon2MasterKeyHashesTheSalt() {
        val key = BwCrypto.masterKey(password, email, KdfParams(KdfParams.ARGON2ID, 3, memoryMb = 16, parallelism = 2))
        assertEquals("67632a7baf82b127cd757a45a518b0745996020314fcb65d555cc97081be5e40", key.hex())
    }

    @Test
    fun stretchExpandsIntoEncAndMacKeys() {
        val master = BwCrypto.masterKey(password, email, KdfParams(KdfParams.PBKDF2, 5000))
        assertEquals(
            "51b580d993b9a66c85507da24f5f859b454a4b041d5f09026bd1505924f80623" +
                "6003abcbec7d9f1a5d22d286be567594936eee96ee66afea96f74a8040ec4b05",
            BwCrypto.stretch(master).bytes.hex(),
        )
    }

    @Test
    fun decryptsUserKeyAndFieldsMadeByAnotherImplementation() {
        val master = BwCrypto.masterKey(password, email, KdfParams(KdfParams.PBKDF2, 5000))
        val userKey = BwCrypto.decryptUserKey(ENC_USER_KEY, master)
        assertArrayEquals(ByteArray(64) { 7 }, userKey.bytes)
        assertEquals("ssh-ed25519 AAAA test", BwCrypto.decryptString(ENC_TEXT, userKey))
    }

    @Test
    fun wrongMasterPasswordIsReported() {
        val master = BwCrypto.masterKey("wrong", email, KdfParams(KdfParams.PBKDF2, 5000))
        val e = assertThrows(BitwardenCryptoException::class.java) { BwCrypto.decryptUserKey(ENC_USER_KEY, master) }
        assertEquals("Wrong master password", e.message)
    }

    @Test
    fun encryptRoundTripsAndDetectsTampering() {
        val key = SymmetricKey.of(ByteArray(64) { it.toByte() })
        val enc = BwCrypto.encryptString("-----BEGIN OPENSSH PRIVATE KEY-----\n…", key)
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\n…", BwCrypto.decryptString(enc, key))

        val parts = enc.removePrefix("2.").split('|')
        val flipped = parts[1].let { (if (it[0] == 'A') 'B' else 'A') + it.substring(1) }
        assertThrows(BitwardenCryptoException::class.java) {
            BwCrypto.decrypt("2.${parts[0]}|$flipped|${parts[2]}", key)
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ENC_USER_KEY = "2.or8T21Lx/ydEIaYoxkDeYQ==|5e/U6fGle9sZhw9mB3zn2gNpGPx9cupj+1vuT6Ufz5M436jC02eGPgTZXVsQlZLisoysR7NrHoZ/GQouUqw2+8mHQfZfcDCWOzNKXykhAUk=|lBqtevcO1qYy3onvVZmaI3XrSzxireuauEeI/TFDqR4="
        const val ENC_TEXT = "2.ow/X0vD+DknBTzzQfkH8Yg==|2/u9abw20galEmqyYdIAgQ/Vf1Et9iBZKizupYH7VPU=|D2Ko4BVPb6Yd4iOYb+ixuqWy44jwpwMrRXi961SgJDA="
    }
}
