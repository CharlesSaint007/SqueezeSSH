package me.nick.squeezessh.ui.bitwarden

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nick.squeezessh.bitwarden.BitwardenServer
import me.nick.squeezessh.bitwarden.BitwardenSync
import me.nick.squeezessh.bitwarden.SyncStatus
import me.nick.squeezessh.ui.common.BackTopBar
import java.text.DateFormat
import java.util.Date

@Composable
fun BitwardenScreen(sync: BitwardenSync, status: SyncStatus, onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("Bitwarden sync", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "SSH keys sync both ways with the SSH Key items in your Bitwarden or Vaultwarden vault. Keys made " +
                    "here are saved to a \"${BitwardenSync.FOLDER_NAME}\" folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (status) {
                SyncStatus.SignedOut -> SignInForm(sync)
                is SyncStatus.SignedIn -> SignedIn(sync, status)
            }
        }
    }
}

private enum class ServerChoice(val label: String) { Us("bitwarden.com"), Eu("bitwarden.eu"), SelfHosted("Self-hosted") }

@Composable
private fun SignInForm(sync: BitwardenSync) {
    var server by remember { mutableStateOf(ServerChoice.Us) }
    var url by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    for (choice in ServerChoice.entries) {
        Row(
            Modifier.fillMaxWidth().clickable { server = choice },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = server == choice, onClick = { server = choice })
            Text(choice.label)
        }
    }
    if (server == ServerChoice.SelfHosted) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://vault.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        "Use your personal API key: in the web vault, Settings → Security → Keys → View API key.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it },
        label = { Text("client_id") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = clientSecret,
        onValueChange = { clientSecret = it },
        label = { Text("client_secret") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Master password") },
        supportingText = { Text("Used once to unlock the vault; it is not stored.") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val serverUrl = when (server) {
        ServerChoice.Us -> BitwardenServer.US
        ServerChoice.Eu -> BitwardenServer.EU
        ServerChoice.SelfHosted -> url.trim()
    }
    Button(
        enabled = !working && clientId.isNotBlank() && clientSecret.isNotBlank() && password.isNotEmpty() &&
            (server != ServerChoice.SelfHosted || serverUrl.startsWith("http")),
        onClick = {
            working = true
            error = null
            scope.launch {
                runCatching { sync.signIn(serverUrl, clientId, clientSecret, password) }
                    .onSuccess { password = "" }
                    .onFailure { error = it.message ?: "Sign-in failed" }
                working = false
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (working) "Signing in…" else "Sign in") }
}

@Composable
private fun SignedIn(sync: BitwardenSync, status: SyncStatus.SignedIn) {
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status.email, style = MaterialTheme.typography.titleMedium)
            Text(status.serverUrl, style = MaterialTheme.typography.bodySmall)
            when {
                status.syncing -> Text("Syncing…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                status.error != null -> Text("Sync failed: ${status.error}", color = MaterialTheme.colorScheme.error)
                else -> Text(
                    listOfNotNull(status.lastSyncAt?.let { "Last sync ${format(it)}" }, status.summary).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { scope.launch { sync.setFolderOnly(!status.folderOnly) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Only the ${BitwardenSync.FOLDER_NAME} folder", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Off: every SSH key in the vault is brought into the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = status.folderOnly, onCheckedChange = { scope.launch { sync.setFolderOnly(it) } })
    }
    Button(
        enabled = !status.syncing,
        onClick = { sync.requestSync(0) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sync now") }
    OutlinedButton(onClick = { scope.launch { sync.signOut() } }, modifier = Modifier.fillMaxWidth()) {
        Text("Sign out")
    }
    Text(
        "Signing out keeps your keys on this device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun format(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
