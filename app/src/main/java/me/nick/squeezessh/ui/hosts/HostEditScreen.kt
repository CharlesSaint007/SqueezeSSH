package me.nick.squeezessh.ui.hosts

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.IdentityEntity
import me.nick.squeezessh.ui.common.BackTopBar
import me.nick.squeezessh.ui.common.PickerField
import me.nick.squeezessh.ui.theme.BuiltInThemes

@Composable
fun HostEditScreen(
    existing: HostEntity?,
    allHosts: List<HostEntity>,
    identities: List<IdentityEntity>,
    onSave: (HostEntity) -> Unit,
    onDelete: (HostEntity) -> Unit,
    onManageIdentities: () -> Unit,
    onBack: () -> Unit,
) {
    var nickname by remember { mutableStateOf(existing?.nickname.orEmpty()) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var identityId by remember { mutableStateOf(existing?.identityId) }
    var jumpHostId by remember { mutableStateOf(existing?.jumpHostId) }
    var group by remember { mutableStateOf(existing?.group.orEmpty()) }
    var themeId by remember { mutableStateOf(existing?.themeId) }
    var keepalive by remember { mutableStateOf((existing?.keepaliveSeconds ?: 30).toString()) }
    var agentForwarding by remember { mutableStateOf(existing?.agentForwarding ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val portNumber = port.toIntOrNull()
    val valid = address.isNotBlank() && portNumber != null && portNumber in 1..65535
    // A host can't jump through itself.
    val jumpCandidates = allHosts.filter { it.id != existing?.id }

    Scaffold(
        topBar = {
            BackTopBar(if (existing == null) "New host" else "Edit host", onBack) {
                if (existing != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete host")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("Address") },
                placeholder = { Text("example.com or 192.168.1.10") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname") },
                    placeholder = { Text(address.ifBlank { "My server" }) },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = portNumber == null || portNumber !in 1..65535,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }

            PickerField(
                label = "Identity",
                selected = identities.firstOrNull { it.id == identityId },
                options = identities,
                optionLabel = { "${it.nickname} (${it.username})" },
                noneLabel = "None — use the username below",
                onSelect = { identityId = it?.id },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onManageIdentities) { Text("Manage identities and keys") }
            if (identityId == null) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    supportingText = { Text("You'll be asked for a password when connecting.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            PickerField(
                label = "Connect via (jump host)",
                selected = jumpCandidates.firstOrNull { it.id == jumpHostId },
                options = jumpCandidates,
                optionLabel = { it.nickname },
                noneLabel = "Direct connection",
                onSelect = { jumpHostId = it?.id },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = group,
                onValueChange = { group = it },
                label = { Text("Group") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PickerField(
                label = "Terminal theme",
                selected = BuiltInThemes.all.firstOrNull { it.id == themeId },
                options = BuiltInThemes.all,
                optionLabel = { it.name },
                noneLabel = "Use global theme",
                onSelect = { themeId = it?.id },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keepalive,
                onValueChange = { keepalive = it.filter(Char::isDigit).take(4) },
                label = { Text("Keepalive interval (seconds, 0 = off)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent forwarding", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Only enable for servers you trust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = agentForwarding, onCheckedChange = { agentForwarding = it })
            }

            Button(
                enabled = valid,
                onClick = {
                    val base = existing ?: HostEntity(nickname = "", address = "")
                    onSave(
                        base.copy(
                            nickname = nickname.ifBlank { address },
                            address = address,
                            port = portNumber ?: 22,
                            username = username.ifBlank { null },
                            identityId = identityId,
                            jumpHostId = jumpHostId,
                            group = group.ifBlank { null },
                            themeId = themeId,
                            keepaliveSeconds = keepalive.toIntOrNull() ?: 30,
                            agentForwarding = agentForwarding,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.nickname}?") },
            text = { Text("The saved host is removed. Identities and keys are kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(existing) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
