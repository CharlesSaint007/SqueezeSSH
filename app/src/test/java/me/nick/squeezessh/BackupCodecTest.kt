package me.nick.squeezessh

import me.nick.squeezessh.backup.BackupCodec
import me.nick.squeezessh.backup.BackupFile
import me.nick.squeezessh.backup.BackupFormatException
import me.nick.squeezessh.backup.HostBackup
import me.nick.squeezessh.backup.Secrets
import me.nick.squeezessh.backup.SettingsBackup
import me.nick.squeezessh.backup.WrongPassphraseException
import me.nick.squeezessh.backup.toSettings
import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    // Small Argon2 settings keep the tests fast; the format records whatever was used.
    private val fast = BackupCodec.KdfParams(iterations = 1, memoryKiB = 1024, parallelism = 1)
    private val secrets = Secrets(
        passwords = mapOf("id-1" to "hunter2"),
        privateKeys = mapOf("key-1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"),
    )

    @Test
    fun secretsRoundTripWithThePassphrase() {
        val sealed = BackupCodec.seal(secrets, "correct horse", fast)
        assertEquals(secrets, BackupCodec.open(sealed, "correct horse"))
    }

    @Test
    fun wrongPassphraseIsReported() {
        val sealed = BackupCodec.seal(secrets, "correct horse", fast)
        assertThrows(WrongPassphraseException::class.java) { BackupCodec.open(sealed, "wrong") }
    }

    @Test
    fun sealedSecretsDontContainThePlaintext() {
        val text = BackupCodec.encode(file(secrets = BackupCodec.seal(secrets, "pw", fast)))
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("OPENSSH PRIVATE KEY"))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sealed = BackupCodec.seal(secrets, "pw", fast)
        val bytes = java.util.Base64.getDecoder().decode(sealed.ciphertext).also { it[0] = (it[0].toInt() xor 1).toByte() }
        val tampered = sealed.copy(ciphertext = java.util.Base64.getEncoder().encodeToString(bytes))
        assertThrows(WrongPassphraseException::class.java) { BackupCodec.open(tampered, "pw") }
    }

    @Test
    fun hostileKdfSettingsAreRefused() {
        val sealed = BackupCodec.seal(secrets, "pw", fast).copy(memoryKiB = 64 * 1024 * 1024)
        assertThrows(BackupFormatException::class.java) { BackupCodec.open(sealed, "pw") }
    }

    @Test
    fun fileRoundTripsAndToleratesUnknownFields() {
        val original = file()
        val text = BackupCodec.encode(original)
        assertEquals(original, BackupCodec.decode(text))
        // A later minor version may add fields; older apps should still read the file.
        val extended = text.replaceFirst("{", "{\n  \"addedLater\": 1,")
        assertEquals(original, BackupCodec.decode(extended))
        assertNull(BackupCodec.decode(text).secrets)
    }

    @Test
    fun otherFilesAndNewerSchemasAreRejected() {
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("""{"hello":"world"}""") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.decode("not json") }
        val newer = BackupCodec.encode(file()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val e = assertThrows(BackupFormatException::class.java) { BackupCodec.decode(newer) }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun settingsFallBackForMissingOrUnknownValues() {
        val current = Settings(fontSizeSp = 20, exitBehavior = ExitBehavior.Disconnect)
        val restored = SettingsBackup(fontSizeSp = 12, exitBehavior = "SomethingNew").toSettings(current)
        assertEquals(12, restored.fontSizeSp)
        assertEquals(ExitBehavior.Disconnect, restored.exitBehavior)
        assertEquals(current.terminalThemeId, restored.terminalThemeId)
    }

    private fun file(secrets: me.nick.squeezessh.backup.EncryptedSecrets? = null) = BackupFile(
        exportedAt = 1_700_000_000_000,
        appVersion = "test",
        hosts = listOf(HostBackup(id = "h1", nickname = "pi5", address = "pi5", username = "pi")),
        settings = SettingsBackup(fontSizeSp = 16),
        secrets = secrets,
    )
}
