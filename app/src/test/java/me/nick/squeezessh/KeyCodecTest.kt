package me.nick.squeezessh

import me.nick.squeezessh.crypto.KeyAlgorithm
import me.nick.squeezessh.crypto.KeyCodec
import me.nick.squeezessh.crypto.KeyImportException
import me.nick.squeezessh.crypto.PassphraseRequiredException
import me.nick.squeezessh.ssh.HostKeyVerifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.security.Security

class KeyCodecTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun registerBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        /** Generated with `ssh-keygen -t ed25519 -N '' -C fixture`. */
        val ED25519_FIXTURE = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
            QyNTUxOQAAACCnAjPSa4l11BreLI/qFe7Uu+wBXFAylfA+eju2RIN6egAAAJAe1CpIHtQq
            SAAAAAtzc2gtZWQyNTUxOQAAACCnAjPSa4l11BreLI/qFe7Uu+wBXFAylfA+eju2RIN6eg
            AAAEDsR4WVctkRoqf6J5NgS5a+jWtr2i+n6HVK31m6P/BIh6cCM9JriXXUGt4sj+oV7tS7
            7AFcUDKV8D56O7ZEg3p6AAAAB2ZpeHR1cmUBAgMEBQY=
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
        const val ED25519_FINGERPRINT = "SHA256:6EhSt6IjacYdRLN8GYRCaUw2/TTKhTzqVpUNeEdPZZM"

        /** Generated with `ssh-keygen -t ed25519 -N squeeze -C enc`. */
        val PASSPHRASE_FIXTURE = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABBCriJcyP
            eMNILxkhYPREi8AAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIOzVjkJ77ycjCkiK
            vcXOxiQllvRgu0WJI3KPpsOCOALbAAAAkAT1DFW/cTpyjfl5QBuoa/ONeAaNf430gc8gtf
            il6rnH2BMzMqk8p+N0ghIFIeYH2ZGv0vSGAGLxr1GMOa6mrSp+vFq0GhGsd+8kEqWwmbwk
            riQChjQbb8+fEYPjYFiqt4raazJ3Bsg8VIY9e+DnEkyV6y/T+ccrpV/lwuYA6HtGLwRPSL
            2XTCK7dgQxvMU9hw==
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()
    }

    @Test
    fun generatedKeysRoundTripThroughOpenSshFormat() {
        for (algorithm in KeyAlgorithm.entries) {
            val generated = KeyCodec.generate(algorithm, "test@squeeze")
            assertEquals(algorithm.sshType, generated.type)
            assertTrue(generated.publicKey.startsWith(algorithm.sshType + " "))
            assertTrue(generated.publicKey.endsWith(" test@squeeze"))
            assertTrue(generated.fingerprint.startsWith("SHA256:"))

            val pem = KeyCodec.toOpenSshPrivateKey(generated.keyPair, "test@squeeze")
            assertTrue(pem.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            val reparsed = KeyCodec.parse(pem)
            assertEquals("$algorithm fingerprint", generated.fingerprint, reparsed.fingerprint)
        }
    }

    @Test
    fun parsesKeyWrittenBySshKeygen() {
        val parsed = KeyCodec.parse(ED25519_FIXTURE, comment = "fixture")
        assertEquals("ssh-ed25519", parsed.type)
        assertEquals(ED25519_FINGERPRINT, parsed.fingerprint)
        assertTrue(parsed.publicKey.endsWith(" fixture"))
    }

    @Test
    fun rejectsGarbage() {
        assertThrows(KeyImportException::class.java) { KeyCodec.parse("not a key") }
        assertThrows(KeyImportException::class.java) { KeyCodec.parse("   ") }
    }

    @Test
    fun hostPortFollowsKnownHostsConvention() {
        assertEquals("example.com", HostKeyVerifier.hostPort("Example.COM", 22))
        assertEquals("[example.com]:2222", HostKeyVerifier.hostPort("example.com", 2222))
    }

    @Test
    fun encryptedKeyNeedsPassphrase() {
        val encrypted = PASSPHRASE_FIXTURE
        val missing = assertThrows(PassphraseRequiredException::class.java) { KeyCodec.parse(encrypted) }
        assertEquals(false, missing.wrongPassphrase)
        val wrong = assertThrows(PassphraseRequiredException::class.java) { KeyCodec.parse(encrypted, "nope") }
        assertEquals(true, wrong.wrongPassphrase)
        assertEquals("ssh-ed25519", KeyCodec.parse(encrypted, "squeeze").type)
    }
}
