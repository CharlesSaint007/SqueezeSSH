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
import androidx.compose.material.icons.filled.AddToHomeScreen
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nick.squeezessh.data.ForwardEntity
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.IdentityEntity
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.ui.common.BackTopBar
import me.nick.squeezessh.ui.common.PickerField
import me.nick.squeezessh.ui.theme.TerminalTheme
import java.util.UUID

@Composable
fun HostEditScreen(
    themes: List<TerminalTheme>,
    existing: HostEntity?,
    /** Start a new host from this one's settings ("copy as new config"); ignored when editing [existing]. */
    template: HostEntity? = null,
    allHosts: List<HostEntity>,
    identities: List<IdentityEntity>,
    snippets: List<SnippetEntity>,
    /** The host's saved forwards; null while they are still loading. */
    initialForwards: List<ForwardEntity>?,
    onSave: (HostEntity, List<ForwardEntity>) -> Unit,
    onDelete: (HostEntity) -> Unit,
    onManageIdentities: () -> Unit,
    /** Pin the saved host to the home screen; null when the launcher can't pin shortcuts. */
    onPinShortcut: ((HostEntity) -> Unit)?,
    /** Whether the phone has a fingerprint, face or screen lock to ask for. */
    biometricAvailable: Boolean,
    /**
     * Called before switching "Require fingerprint to connect" off for a host where it is already saved as on;
     * returns true once the owner has authenticated.
     */
    onRequestDisableBiometric: suspend (HostEntity) -> Boolean,
    onBack: () -> Unit,
) {
    // Editing starts from the saved host; a copy starts from the template, as an unsaved new host.
    val source = existing ?: template
    var nickname by remember {
        mutableStateOf(if (existing == null && template != null) "${template.nickname} (copy)" else existing?.nickname.orEmpty())
    }
    var address by remember { mutableStateOf(source?.address.orEmpty()) }
    var port by remember { mutableStateOf((source?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(source?.username.orEmpty()) }
    var identityId by remember { mutableStateOf(source?.identityId) }
    var jumpHostId by remember { mutableStateOf(source?.jumpHostId) }
    var group by remember { mutableStateOf(source?.group.orEmpty()) }
    var themeId by remember { mutableStateOf(source?.themeId) }
    var keepalive by remember { mutableStateOf((source?.keepaliveSeconds ?: 30).toString()) }
    var agentForwarding by remember { mutableStateOf(source?.agentForwarding ?: false) }
    var startupSnippetId by remember { mutableStateOf(source?.startupSnippetId) }
    val scope = rememberCoroutineScope()
    var requireBiometric by remember { mutableStateOf(source?.requireBiometric ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // A new host gets its ID up front so forwards added before the first save can point at it.
    val hostId = remember { existing?.id ?: UUID.randomUUID().toString() }
    var forwards by remember(initialForwards) { mutableStateOf(initialForwards.orEmpty()) }

    val portNumber = port.toIntOrNull()
    val valid = address.isNotBlank() && portNumber != null && portNumber in 1..65535
    // A host can't jump through itself.
    val jumpCandidates = allHosts.filter { it.id != existing?.id }

    Scaffold(
        topBar = {
            BackTopBar(if (existing == null) "New host" else "Edit host", onBack) {
                if (existing != null && onPinShortcut != null) {
                    IconButton(onClick = { onPinShortcut(existing) }) {
                        Icon(Icons.Filled.AddToHomeScreen, contentDescription = "Add to home screen")
                    }
                }
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
                selected = themes.firstOrNull { it.id == themeId },
                options = themes,
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
            PickerField(
                label = "Startup snippet",
                selected = snippets.firstOrNull { it.id == startupSnippetId },
                options = snippets,
                optionLabel = { it.name },
                noneLabel = "None",
                onSelect = { startupSnippetId = it?.id },
                modifier = Modifier.fillMaxWidth(),
            )
            ForwardsEditor(forwards = forwards, hostId = hostId, onChange = { forwards = it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Require fingerprint to connect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (biometricAvailable) {
                            "Asks for your fingerprint, face or screen lock each time before connecting to this host."
                        } else {
                            "Set up a fingerprint or screen lock in your phone's settings to use this."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Can always be switched off, even when the phone has lost its lock; only turning it on needs one.
                Switch(
                    checked = requireBiometric,
                    onCheckedChange = { on ->
                        if (on || existing?.requireBiometric != true) {
                            // Turning on, or switching off something that was never saved as on: free.
                            requireBiometric = on
                        } else {
                            // Saved as on: turning it off needs the owner's fingerprint first.
                            scope.launch { if (onRequestDisableBiometric(existing)) requireBiometric = false }
                        }
                    },
                    enabled = biometricAvailable || requireBiometric,
                )
            }
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
                enabled = valid && initialForwards != null,
                onClick = {
                    val base = existing ?: HostEntity(id = hostId, nickname = "", address = "")
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
                            startupSnippetId = startupSnippetId,
                            requireBiometric = requireBiometric,
                        ),
                        forwards,
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
