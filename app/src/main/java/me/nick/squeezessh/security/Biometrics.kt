package me.nick.squeezessh.security

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface AuthResult {
    data object Success : AuthResult

    /** The user dismissed the prompt. */
    data object Cancelled : AuthResult

    /** No fingerprint, face or screen lock is set up, so there is nothing to authenticate with. */
    data object Unavailable : AuthResult

    data class Failed(val message: String) : AuthResult
}

/**
 * Fingerprint / face authentication, with the screen lock (PIN, pattern, password) as the fallback so a
 * wet finger or a failed scan never locks the owner out of their own hosts.
 */
object Biometrics {
    /** Android 9 and 10 can't combine a strong biometric with the screen lock; before 11, accept weak ones too. */
    private fun authenticators() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    /** Show the system prompt and wait for the result. Must be called on the main thread. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): AuthResult {
        if (!isAvailable(activity)) return AuthResult.Unavailable
        return suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(AuthResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        val dismissed = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        if (cont.isActive) cont.resume(if (dismissed) AuthResult.Cancelled else AuthResult.Failed(errString.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { subtitle?.let(::setSubtitle) }
                .setAllowedAuthenticators(authenticators())
                .build()
            prompt.authenticate(info)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}

/** When the app lock comes back after the app has been out of sight. */
object AppLock {
    /** Long enough that the system's own credential screen (a separate activity on older Android) can't re-lock it. */
    const val GRACE_MS = 60_000L

    fun shouldRelock(backgroundedAt: Long?, now: Long, graceMs: Long = GRACE_MS): Boolean =
        backgroundedAt != null && now - backgroundedAt > graceMs
}
