package me.nick.squeezessh.ui.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Covers the whole app while it is locked. Shows nothing of what is underneath. */
@Composable
fun LockScreen(
    /** Why the last attempt didn't unlock, if it didn't. */
    message: String?,
    /** The phone has no fingerprint, face or screen lock to ask for, so the lock can't be satisfied. */
    unavailable: Boolean,
    onUnlock: () -> Unit,
    onTurnOffLock: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text("SqueezeSSH is locked", style = MaterialTheme.typography.titleLarge)
            if (unavailable) {
                Text(
                    "This phone has no fingerprint, face or screen lock set up, so there is nothing to unlock with. " +
                        "Set one up in the phone's settings, or turn the app lock off.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onUnlock) { Text("Try again") }
                OutlinedButton(onClick = onTurnOffLock) { Text("Turn off app lock") }
            } else {
                message?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Button(onClick = onUnlock) { Text("Unlock") }
            }
        }
    }
}
