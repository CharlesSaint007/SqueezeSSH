package me.nick.squeezessh

import android.app.Application
import me.nick.squeezessh.backup.BackupService
import me.nick.squeezessh.bitwarden.BitwardenSync
import me.nick.squeezessh.crypto.SecretStore
import me.nick.squeezessh.crypto.SshCrypto
import me.nick.squeezessh.data.AppDatabase
import me.nick.squeezessh.data.Vault
import me.nick.squeezessh.session.SessionManager
import me.nick.squeezessh.settings.SettingsRepository
import me.nick.squeezessh.ssh.ConnectionService
import me.nick.squeezessh.ssh.HostKeyVerifier
import me.nick.squeezessh.ssh.SshConnector

/** Holds the app-wide singletons. Replaced by Hilt once there are enough of them to warrant it. */
class SqueezeApp : Application() {
    val sessionManager = SessionManager()
    val settingsRepository by lazy { SettingsRepository(this) }
    val database by lazy { AppDatabase.create(this) }
    private val secretStore by lazy { SecretStore(this) }
    val vault by lazy { Vault(database, secretStore) }
    val bitwarden by lazy { BitwardenSync(vault, secretStore) }
    val backup by lazy { BackupService(database, secretStore, settingsRepository, BuildConfig.VERSION_NAME) }
    val connections by lazy { ConnectionService(vault, SshConnector(HostKeyVerifier(database.knownHosts()))) }

    override fun onCreate() {
        super.onCreate()
        SshCrypto.init(this)
    }
}
