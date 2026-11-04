package me.nick.squeezessh.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.nick.squeezessh.data.ForwardType
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.ssh.ForwardController
import me.nick.squeezessh.ssh.ForwardState
import me.nick.squeezessh.ui.snippets.SnippetListItem

/** Pick a snippet to type into the session. */
@Composable
fun SnippetPicker(
    snippets: List<SnippetEntity>,
    onPick: (SnippetEntity) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snippets") },
        text = {
            if (snippets.isEmpty()) {
                Text("No snippets yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(snippets, key = { it.id }) { snippet ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(snippet) }) { SnippetListItem(snippet) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManage) { Text(if (snippets.isEmpty()) "Add snippets" else "Edit snippets") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The session's port forwards, with their live state and a switch to start or stop each one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardsSheet(controller: ForwardController?, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Port forwards", style = MaterialTheme.typography.titleMedium)
            if (controller == null) {
                Text(
                    "This session has no port forwards. Add them in the host's settings; they start with the next connection.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val states by controller.states.collectAsState()
                val scope = rememberCoroutineScope()
                for (state in states) {
                    ForwardRow(state) { on ->
                        scope.launch { if (on) controller.start(state.forward.id) else controller.stop(state.forward.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForwardRow(state: ForwardState, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(state.forward.summary, fontFamily = FontFamily.Monospace)
            val detail = when {
                state.error != null -> state.error
                state.bound != null -> when (state.forward.type) {
                    ForwardType.Local -> "Listening on ${state.bound.hostName}:${state.bound.port}"
                    ForwardType.Dynamic -> "SOCKS proxy on ${state.bound.hostName}:${state.bound.port}"
                    ForwardType.Remote -> "Server listening on port ${state.bound.port}"
                }
                else -> "Stopped"
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = state.active, onCheckedChange = onToggle)
    }
}
