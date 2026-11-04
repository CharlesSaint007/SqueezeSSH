package me.nick.squeezessh.crypto

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Encrypted storage for secrets: private keys, passwords, and (later) Bitwarden tokens.
 *
 * Envelope encryption: each secret is sealed with an AES-256-GCM Tink keyset, and that keyset is itself
 * encrypted by a non-exportable master key in the Android Keystore. The master key never leaves the Keystore,
 * so copying the app's files off the device does not reveal any secret. Private keys still have to be readable
 * by the app (they are synced to Bitwarden and handed to the SSH client), which is why they are encrypted
 * rather than generated inside the Keystore.
 *
 * Each secret is one file named by its ID; the ID is bound in as associated data so ciphertexts cannot be
 * swapped between entries.
 */
class SecretStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFS_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Store [plaintext] under a new ID and return the ID. */
    suspend fun put(plaintext: ByteArray): String {
        val id = UUID.randomUUID().toString()
        put(id, plaintext)
        return id
    }

    /** Store or replace the secret with [id]. */
    suspend fun put(id: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        val ciphertext = aead.encrypt(plaintext, id.toByteArray())
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(ciphertext)
        check(tmp.renameTo(File(dir, id))) { "Could not write secret $id" }
    }

    suspend fun get(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(dir, id)
        if (!file.exists()) return@withContext null
        aead.decrypt(file.readBytes(), id.toByteArray())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, id).delete()
        Unit
    }

    suspend fun putString(value: String): String = put(value.toByteArray(Charsets.UTF_8))
    suspend fun getString(id: String): String? = get(id)?.toString(Charsets.UTF_8)

    private companion object {
        const val KEYSET_NAME = "secret_store_keyset"
        const val PREFS_FILE = "secret_store_prefs"
        const val MASTER_KEY_URI = "android-keystore://squeezessh_master_key"
    }
}
