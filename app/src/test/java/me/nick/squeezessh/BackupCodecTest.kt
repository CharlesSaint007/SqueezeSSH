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

/**
 * A host name long enough that it cannot appear in random ciphertext by chance. (The checks that a backup "hides
 * everything" used a 3-letter name, which showed up in about 1 run in 300 and failed CI.)
 */
private const val SECRET_HOST_NAME = "squeezessh-test-host.internal.example"

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
        assertThrows(BackupFormatException::class.java) { BackupCodec.read("""{"hello":"world"}""") }
        assertThrows(BackupFormatException::class.java) { BackupCodec.read("not json") }
        val newer = BackupCodec.encode(file()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val e = assertThrows(BackupFormatException::class.java) { BackupCodec.decode(newer) }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun wholeFileEncryptionRoundTripsAndHidesEverything() {
        val original = file().copy(includedSecrets = secrets)
        val text = BackupCodec.encodeEncrypted(original, "correct horse", fast)
        // Nothing readable on disk: not the host, not the secrets.
        for (plain in listOf(SECRET_HOST_NAME, "hunter2", "OPENSSH PRIVATE KEY", "\"hosts\"")) assertFalse(plain, text.contains(plain))
        val read = BackupCodec.read(text)
        assertTrue(read is me.nick.squeezessh.backup.ReadBackup.Locked)
        val locked = (read as me.nick.squeezessh.backup.ReadBackup.Locked).file
        assertEquals(original, BackupCodec.decrypt(locked, "correct horse"))
        assertThrows(WrongPassphraseException::class.java) { BackupCodec.decrypt(locked, "wrong") }
    }

    @Test
    fun encryptedWithoutSecretsStillNeedsThePassphrase() {
        val text = BackupCodec.encodeEncrypted(file(), "pw-12345678", fast)
        assertFalse(text.contains(SECRET_HOST_NAME))
        val locked = (BackupCodec.read(text) as me.nick.squeezessh.backup.ReadBackup.Locked).file
        assertNull(BackupCodec.decrypt(locked, "pw-12345678").includedSecrets)
    }

    @Test
    fun olderUnencryptedFilesStillRead() {
        val legacy = BackupCodec.encode(file(secrets = BackupCodec.seal(secrets, "pw", fast)))
        val read = BackupCodec.read(legacy)
        assertTrue(read is me.nick.squeezessh.backup.ReadBackup.Plain)
        val plain = (read as me.nick.squeezessh.backup.ReadBackup.Plain).file
        assertEquals(secrets, BackupCodec.open(plain.secrets!!, "pw"))
    }

    @Test
    fun wholeFileAndLegacySecretsCiphertextsAreNotInterchangeable() {
        // The two uses bind different associated data, so one can't be passed off as the other.
        val legacySealed = BackupCodec.seal(secrets, "pw", fast)
        val envelope = me.nick.squeezessh.backup.EncryptedBackup(exportedAt = 0, sealed = legacySealed)
        assertThrows(WrongPassphraseException::class.java) { BackupCodec.decrypt(envelope, "pw") }
    }

    @Test
    fun perHostBiometricRequirementSurvivesAnEncryptedBackup() {
        val withFlag = file().copy(hosts = listOf(HostBackup(id = "h1", nickname = SECRET_HOST_NAME, address = SECRET_HOST_NAME, requireBiometric = true)))
        val text = BackupCodec.encodeEncrypted(withFlag, "pw-12345678", fast)
        val locked = (BackupCodec.read(text) as me.nick.squeezessh.backup.ReadBackup.Locked).file
        assertTrue(BackupCodec.decrypt(locked, "pw-12345678").hosts.single().requireBiometric)
        // Backups made before the option existed read as "off".
        assertFalse(BackupCodec.decode(BackupCodec.encode(file()).replace("\"requireBiometric\": false,", "")).hosts.single().requireBiometric)
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
        hosts = listOf(HostBackup(id = "h1", nickname = SECRET_HOST_NAME, address = SECRET_HOST_NAME, username = "pi")),
        settings = SettingsBackup(fontSizeSp = 16),
        secrets = secrets,
    )
}
