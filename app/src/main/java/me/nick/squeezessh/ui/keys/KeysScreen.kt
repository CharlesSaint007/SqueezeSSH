package me.nick.squeezessh.ui.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nick.squeezessh.bitwarden.BitwardenSync
import me.nick.squeezessh.bitwarden.SyncStatus
import me.nick.squeezessh.crypto.KeyAlgorithm
import me.nick.squeezessh.crypto.PassphraseRequiredException
import me.nick.squeezessh.data.KeyEntity
import me.nick.squeezessh.data.Vault
import me.nick.squeezessh.ui.common.BackTopBar

@Composable
fun KeysScreen(
    keys: List<KeyEntity>,
    vault: Vault,
    bitwarden: BitwardenSync,
    syncStatus: SyncStatus,
    onOpenBitwarden: () -> Unit,
    onBack: () -> Unit,
) {
    var showGenerate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<KeyEntity?>(null) }
    val context = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Key files are tiny; cap the read so a wrong pick can't load something huge.
            val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray() }
            showImport = text?.toString(Charsets.UTF_8).orEmpty()
        }
    }

    Scaffold(
        topBar = {
            BackTopBar("Keys", onBack) {
                TextButton(onClick = { showImport = "" }) { Text("Paste") }
                TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Import file") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showGenerate = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Generate") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SyncBanner(syncStatus, onOpenBitwarden) }
            if (keys.isEmpty()) {
                item {
                    Text(
                        "No keys yet. Generate one, paste a private key, or import a key file (OpenSSH, PEM or PuTTY .ppk).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(keys, key = { it.id }) { key ->
                Card(Modifier.fillMaxWidth().clickable { selected = key }) {
                    ListItem(
                        headlineContent = { Text(key.name) },
                        supportingContent = {
                            Column {
                                Text(key.type)
                                Text(key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingContent = { Icon(Icons.Filled.Key, contentDescription = null) },
                        trailingContent = {
                            if (key.bitwardenCipherId != null) Icon(Icons.Filled.CloudDone, contentDescription = "Synced to vault")
                        },
                    )
                }
            }
        }
    }

    if (showGenerate) GenerateKeyDialog(vault, onDone = { showGenerate = false })
    showImport?.let { initial -> ImportKeyDialog(vault, initial, onDone = { showImport = null }) }
    selected?.let { key ->
        KeyDetailsDialog(
            key = key,
            onCopy = {
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Public key", key.publicKey))
            },
            onShare = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, key.publicKey),
                        "Share public key",
                    ),
                )
            },
            vault = vault,
            bitwarden = bitwarden,
            signedIn = syncStatus is SyncStatus.SignedIn,
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun GenerateKeyDialog(vault: Vault, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var algorithm by remember { mutableStateOf(KeyAlgorithm.Ed25519) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!working) onDone() },
        title = { Text("Generate key") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                for (option in KeyAlgorithm.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { algorithm = option }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = algorithm == option, onClick = { algorithm = option })
                        Text(option.label)
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (working) Text("Generating…")
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        runCatching { vault.generateKey(name.trim(), algorithm) }
                            .onSuccess { onDone() }
                            .onFailure { error = it.message; working = false }
                    }
                },
            ) { Text("Generate") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun ImportKeyDialog(vault: Vault, initialText: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(initialText) }
    var passphrase by remember { mutableStateOf("") }
    var needsPassphrase by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(min = 120.dp, max = 240.dp),
                )
                if (needsPassphrase) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotBlank(),
                onClick = {
                    scope.launch {
                        try {
                            vault.importKey(name.trim(), text, passphrase.ifEmpty { null })
                            onDone()
                        } catch (e: PassphraseRequiredException) {
                            needsPassphrase = true
                            error = if (e.wrongPassphrase) "Wrong passphrase" else "This key needs its passphrase"
                        } catch (e: Exception) {
                            error = e.message ?: "Could not import the key"
                        }
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
private fun SyncBanner(status: SyncStatus, onOpen: () -> Unit) {
    val text = when (status) {
        SyncStatus.SignedOut -> "Bitwarden sync is off. Tap to set it up."
        is SyncStatus.SignedIn -> when {
            status.syncing -> "Syncing with ${status.email}…"
            status.error != null -> "Sync failed: ${status.error}"
            else -> "Syncing with ${status.email}" + (status.summary?.let { " · $it" } ?: "")
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if ((status as? SyncStatus.SignedIn)?.error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp),
    )
}

@Composable
private fun KeyDetailsDialog(
    key: KeyEntity,
    vault: Vault,
    bitwarden: BitwardenSync,
    signedIn: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(key.name) }
    var syncEnabled by remember { mutableStateOf(key.bitwardenSync) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val linked = key.bitwardenCipherId != null

    fun delete(fromVault: Boolean) = scope.launch {
        if (fromVault) {
            // Trash first: if it fails the key is still here and the user can retry.
            runCatching { bitwarden.trash(key) }.onFailure {
                error = "Could not remove it from the vault: ${it.message}"
                return@launch
            }
        }
        vault.deleteKey(key)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text(key.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text("Public key:", style = MaterialTheme.typography.labelMedium)
                Text(key.publicKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = onCopy) { Text("Copy") }
                    TextButton(onClick = onShare) { Text("Share") }
                }
                if (signedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sync with Bitwarden")
                            Text(
                                if (linked) "Saved in your vault" else "Not in your vault yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = syncEnabled, onCheckedChange = { syncEnabled = it })
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (confirmDelete) {
                    Text(
                        "Delete this key? Identities using it will no longer log in with it.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (linked && signedIn) {
                        TextButton(onClick = { delete(fromVault = true) }) {
                            Text("Delete here and move to vault trash", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { delete(fromVault = false) }) {
                            Text("Delete here only (keep in vault)", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { delete(fromVault = false) }) {
                            Text("Yes, delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete key", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        if (syncEnabled != key.bitwardenSync) vault.setKeySync(key, syncEnabled)
                        val current = vault.key(key.id)
                        if (current != null && name.trim() != current.name) vault.renameKey(current, name.trim())
                        onDismiss()
                    }
                },
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
