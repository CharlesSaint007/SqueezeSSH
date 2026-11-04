package me.nick.squeezessh.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.nick.squeezessh.backup.BackupFile
import me.nick.squeezessh.backup.BackupService
import me.nick.squeezessh.backup.EncryptedBackup
import me.nick.squeezessh.backup.ReadBackup
import me.nick.squeezessh.backup.ImportMode
import me.nick.squeezessh.backup.ImportResult
import me.nick.squeezessh.backup.WrongPassphraseException
import me.nick.squeezessh.ui.common.BackTopBar
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

@Composable
fun BackupScreen(backup: BackupService, onImported: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var includeSecrets by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<BackupFile?>(null) }
    var locked by remember { mutableStateOf<EncryptedBackup?>(null) }
    // An encrypted file's passphrase, kept after unlocking so it isn't asked for twice.
    var unlockedWith by remember { mutableStateOf<String?>(null) }

    val exportReady = passphrase.length >= MIN_PASSPHRASE && passphrase == confirm

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Exporting…"
        scope.launch {
            message = runCatching {
                val text = backup.export(passphrase, includeSecrets)
                write(context, uri, text)
                if (includeSecrets) {
                    "Exported and encrypted, with passwords and private keys."
                } else {
                    "Exported and encrypted. Passwords and private keys were not included."
                }
            }.getOrElse { "Export failed: ${it.message}" }
            busy = null
            passphrase = ""
            confirm = ""
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { backup.read(read(context, uri)) }
                .onSuccess { r ->
                    message = null
                    unlockedWith = null
                    when (r) {
                        is ReadBackup.Locked -> locked = r.file
                        is ReadBackup.Plain -> pending = r.file
                    }
                }
                .onFailure { message = it.message ?: "Could not read the file" }
        }
    }

    Scaffold(topBar = { BackTopBar("Backup & restore", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleMedium)
            Text(
                "Saves hosts, identities, keys (public halves), port forwards, snippets, known hosts and settings to " +
                    "a file encrypted with a passphrase you choose. On a new phone, import it, then sign in to " +
                    "Bitwarden to bring back synced private keys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().clickable { includeSecrets = !includeSecrets },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Include passwords and private keys", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Needed for keys that aren't in Bitwarden, and for saved passwords.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
            }
            PassphraseField(passphrase, { passphrase = it }, "Passphrase", supporting = "At least $MIN_PASSPHRASE characters. It can't be recovered.")
            PassphraseField(
                confirm,
                { confirm = it },
                "Repeat passphrase",
                error = confirm.isNotEmpty() && confirm != passphrase,
                supporting = if (confirm.isNotEmpty() && confirm != passphrase) "Doesn't match" else null,
            )
            Button(
                enabled = exportReady && busy == null,
                onClick = { createDocument.launch("squeezessh-${LocalDate.now()}.squeezessh.json") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export to file…") }

            Text("Import", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            OutlinedButton(
                enabled = busy == null,
                onClick = { openDocument.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import from file…") }

            busy?.let { Text(it) }
            message?.let { Card(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(16.dp)) } }
        }
    }

    locked?.let { file ->
        UnlockDialog(
            busy = busy != null,
            wrongPassphrase = message == "Wrong passphrase",
            onUnlock = { pass ->
                busy = "Unlocking…"
                scope.launch {
                    try {
                        pending = backup.unlock(file, pass)
                        unlockedWith = pass
                        locked = null
                        message = null
                    } catch (e: WrongPassphraseException) {
                        message = "Wrong passphrase"
                    } catch (e: Exception) {
                        locked = null
                        message = "Import failed: ${e.message}"
                    } finally {
                        busy = null
                    }
                }
            },
            onDismiss = { locked = null; if (message == "Wrong passphrase") message = null },
        )
    }

    pending?.let { file ->
        ImportDialog(
            file = file,
            alreadyUnlocked = unlockedWith != null,
            onImport = { mode, pass ->
                busy = "Importing…"
                scope.launch {
                    try {
                        val result = backup.import(file, mode, pass ?: unlockedWith)
                        pending = null
                        message = summary(result)
                        onImported()
                    } catch (e: WrongPassphraseException) {
                        // Leave the dialog up so the passphrase can be retyped.
                        message = "Wrong passphrase"
                    } catch (e: Exception) {
                        pending = null
                        message = "Import failed: ${e.message}"
                    } finally {
                        busy = null
                    }
                }
            },
            wrongPassphrase = message == "Wrong passphrase",
            busy = busy != null,
            onDismiss = { pending = null; unlockedWith = null; if (message == "Wrong passphrase") message = null },
        )
    }
}

@Composable
private fun UnlockDialog(busy: Boolean, wrongPassphrase: Boolean, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Encrypted backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the passphrase this backup was exported with.")
                PassphraseField(
                    passphrase,
                    { passphrase = it },
                    "Backup passphrase",
                    error = wrongPassphrase,
                    supporting = if (wrongPassphrase) "Wrong passphrase" else null,
                )
                if (busy) Text("Unlocking…")
            }
        },
        confirmButton = { TextButton(enabled = !busy && passphrase.isNotEmpty(), onClick = { onUnlock(passphrase) }) { Text("Unlock") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(
    file: BackupFile,
    /** Unlocked already (encrypted file), so there's nothing more to ask for. */
    alreadyUnlocked: Boolean,
    onImport: (ImportMode, String?) -> Unit,
    wrongPassphrase: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ImportMode.Merge) }
    var passphrase by remember { mutableStateOf("") }
    var confirmReplace by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "From ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.exportedAt))}: " +
                        "${file.hosts.size} hosts, ${file.identities.size} identities, ${file.keys.size} keys, " +
                        "${file.snippets.size} snippets, ${file.forwards.size} forwards.",
                )
                for ((option, label, detail) in listOf(
                    Triple(ImportMode.Merge, "Merge", "Add to what's here; matching items are updated"),
                    Triple(ImportMode.Replace, "Replace", "Delete everything here first"),
                )) {
                    Row(
                        Modifier.fillMaxWidth().clickable { mode = option; confirmReplace = false },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == option, onClick = { mode = option; confirmReplace = false })
                        Column {
                            Text(label)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (alreadyUnlocked) {
                    Text(
                        if (file.includedSecrets != null) {
                            "Includes passwords and private keys."
                        } else {
                            "No passwords or private keys in this backup. Keys come back once Bitwarden sync has them."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (file.secrets != null) {
                    PassphraseField(
                        passphrase,
                        { passphrase = it },
                        "Backup passphrase",
                        error = wrongPassphrase,
                        supporting = if (wrongPassphrase) "Wrong passphrase" else "Leave empty to skip passwords and private keys",
                    )
                } else {
                    Text(
                        "This backup has no passwords or private keys. Keys come back once Bitwarden sync has them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (confirmReplace) {
                    Text("Replace deletes all hosts, identities and keys on this phone. Tap Replace again to continue.", color = MaterialTheme.colorScheme.error)
                }
                if (busy) Text("Importing…")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (mode == ImportMode.Replace && !confirmReplace) {
                        confirmReplace = true
                    } else {
                        onImport(mode, passphrase.ifEmpty { null })
                    }
                },
            ) { Text(if (mode == ImportMode.Replace) "Replace" else "Import") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String, error: Boolean = false, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun summary(r: ImportResult) = buildString {
    append("Imported ${r.hosts} hosts, ${r.identities} identities, ${r.keys} keys, ${r.snippets} snippets and ${r.forwards} forwards.")
    if (r.keysMissingPrivate > 0) {
        append(" ${r.keysMissingPrivate} keys came back without their private half; sign in to Bitwarden sync to restore them.")
    }
}

private suspend fun write(context: android.content.Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: throw IllegalStateException("Could not open the file for writing")
}

private suspend fun read(context: android.content.Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.use { input ->
        // Backups are small; cap the read so picking the wrong file can't load something huge.
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_FILE_BYTES) throw IllegalStateException("That file is too large to be a SqueezeSSH backup")
        }
        out.toString(Charsets.UTF_8.name())
    } ?: throw IllegalStateException("Could not open the file")
}

private const val MIN_PASSPHRASE = 8
private const val MAX_FILE_BYTES = 10 * 1024 * 1024
