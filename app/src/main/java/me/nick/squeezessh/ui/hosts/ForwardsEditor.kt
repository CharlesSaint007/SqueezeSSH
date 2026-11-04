package me.nick.squeezessh.ui.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.ForwardType

/** The host editor's list of port forwards; edits stay local until the host is saved. */
@Composable
fun ForwardsEditor(forwards: List<ForwardEntity>, hostId: String, onChange: (List<ForwardEntity>) -> Unit) {
    var editing by remember { mutableStateOf<ForwardEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Port forwarding", style = MaterialTheme.typography.titleMedium)
        if (forwards.isEmpty()) {
            Text(
                "None. Add a local (-L), remote (-R) or SOCKS (-D) forward.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (f in forwards) {
            Column(Modifier.fillMaxWidth().clickable { editing = f }) {
                Text(f.summary, fontFamily = FontFamily.Monospace)
                Text(
                    if (f.autoStart) "Starts on connect" else "Start from the session (FN → PORTS)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Add forward")
        }
    }

    if (adding || editing != null) {
        ForwardDialog(
            existing = editing,
            hostId = hostId,
            onSave = { saved ->
                onChange(if (editing != null) forwards.map { if (it.id == saved.id) saved else it } else forwards + saved)
                adding = false
                editing = null
            },
            onDelete = editing?.let { e -> { onChange(forwards - e); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
private fun ForwardDialog(
    existing: ForwardEntity?,
    hostId: String,
    onSave: (ForwardEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(existing?.type ?: ForwardType.Local) }
    var bindPort by remember { mutableStateOf(existing?.bindPort?.toString().orEmpty()) }
    var destHost by remember { mutableStateOf(existing?.destHost ?: "localhost") }
    var destPort by remember { mutableStateOf(existing?.destPort?.toString().orEmpty()) }
    var allowOthers by remember { mutableStateOf(existing != null && existing.bindAddress != ForwardEntity.LOOPBACK) }
    var autoStart by remember { mutableStateOf(existing?.autoStart ?: true) }

    val bind = bindPort.toIntOrNull()
    val dest = destPort.toIntOrNull()
    // Port 0 asks the system (or for -R, the server) to pick a free port.
    val valid = bind != null && bind in 0..65535 &&
        (type == ForwardType.Dynamic || (destHost.isNotBlank() && dest != null && dest in 1..65535))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add forward" else "Edit forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val types = listOf(ForwardType.Local to "Local", ForwardType.Remote to "Remote", ForwardType.Dynamic to "SOCKS")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    types.forEachIndexed { i, (t, label) ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, types.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    when (type) {
                        ForwardType.Local -> "A port on this phone that reaches a destination through the server."
                        ForwardType.Remote -> "A port on the server that reaches a destination through this phone."
                        ForwardType.Dynamic -> "A SOCKS5 proxy on this phone; apps using it browse from the server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = bindPort,
                    onValueChange = { bindPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(if (type == ForwardType.Remote) "Server port" else "Phone port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (type != ForwardType.Dynamic) {
                    OutlinedTextField(
                        value = destHost,
                        onValueChange = { destHost = it.trim() },
                        label = { Text("Destination host") },
                        supportingText = {
                            Text(if (type == ForwardType.Local) "As seen from the server" else "As seen from this phone")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = destPort,
                        onValueChange = { destPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Destination port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                CheckRow(
                    checked = allowOthers,
                    onChange = { allowOthers = it },
                    label = if (type == ForwardType.Remote) "Listen on all server addresses" else "Allow other devices on the network",
                    detail = if (type == ForwardType.Remote) "Needs GatewayPorts on the server" else "Off: only apps on this phone can connect",
                )
                CheckRow(checked = autoStart, onChange = { autoStart = it }, label = "Start when connecting", detail = null)
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete forward", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = existing ?: ForwardEntity(hostId = hostId, type = type, bindPort = 0)
                    onSave(
                        base.copy(
                            type = type,
                            bindAddress = if (allowOthers) ALL_ADDRESSES else ForwardEntity.LOOPBACK,
                            bindPort = bind ?: 0,
                            destHost = if (type == ForwardType.Dynamic) null else destHost,
                            destPort = if (type == ForwardType.Dynamic) null else dest,
                            autoStart = autoStart,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String, detail: String?) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column {
            Text(label)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val ALL_ADDRESSES = "0.0.0.0"
