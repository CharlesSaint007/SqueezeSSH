package me.nick.squeezessh.crypto

import android.content.Context
import org.apache.sshd.common.util.OsUtils
import org.apache.sshd.common.util.io.PathUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * One-time setup MINA SSHD needs on Android (see the project's docs/android.md): tell it the OS, give it a
 * home/working directory, and register the full BouncyCastle provider. Android ships a cut-down "BC" provider
 * that lacks Ed25519 and several ciphers, so it is replaced with the bundled one.
 */
object SshCrypto {
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            OsUtils.setAndroid(true)
            OsUtils.setCurrentUser("squeezessh")
            val home = context.filesDir.toPath()
            PathUtils.setUserHomeFolderResolver { home }
            OsUtils.setCurrentWorkingDirectoryResolver { home }

            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
            check(SecurityUtils.isBouncyCastleRegistered()) { "BouncyCastle provider is not registered" }
            initialized = true
        }
    }
}
