package me.nick.squeezessh.ui.hosts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.R
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.data.IdentityEntity
import me.nick.squeezessh.security.Masking
import me.nick.squeezessh.session.SessionInfo

/** A parsed `user@host:port` quick-connect string. */
data class QuickConnect(val username: String?, val host: String, val port: Int) {
    /** Back to the `user@host:port` form, as kept in the quick-connect history. */
    fun format(): String = buildString {
        if (username != null) append("$username@")
        append(if (host.contains(':')) "[$host]" else host)
        if (port != 22) append(":$port")
    }

    companion object {
        private val PATTERN = Regex("""^(?:([^@\s]+)@)?(\[[^\]]+]|[^:\s]+)(?::(\d{1,5}))?$""")

        fun parse(text: String): QuickConnect? {
            val m = PATTERN.matchEntire(text.trim()) ?: return null
            val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 22
            if (port !in 1..65535) return null
            return QuickConnect(m.groupValues[1].ifEmpty { null }, m.groupValues[2].removeSurrounding("[", "]"), port)
        }
    }
}

enum class HostSort(val label: String) { Name("Name"), Recent("Recent"), Frequent("Most used") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostsScreen(
    hosts: List<HostEntity>,
    identities: List<IdentityEntity>,
    sessions: List<SessionInfo>,
    /** Recent quick-connect targets, newest first. */
    recentQuick: List<String>,
    onConnect: (HostEntity) -> Unit,
    onQuickConnect: (QuickConnect) -> Unit,
    onForgetQuick: (String) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (HostEntity) -> Unit,
    onCopyHost: (HostEntity) -> Unit,
    onDeleteHost: (HostEntity) -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onLocalShell: () -> Unit,
    onIdentities: () -> Unit,
    onKeys: () -> Unit,
    onSnippets: () -> Unit,
    onSettings: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(HostSort.Name) }
    var menuOpen by remember { mutableStateOf(false) }
    // The host whose long-press menu is open, and the one waiting for delete confirmation.
    var menuHost by remember { mutableStateOf<HostEntity?>(null) }
    var deleting by remember { mutableStateOf<HostEntity?>(null) }
    val quick = QuickConnect.parse(query)?.takeIf { query.contains('@') || query.contains(':') }
    val identityById = identities.associateBy { it.id }

    val visible = hosts
        .filter { h ->
            // A protected host's address is hidden, so it must not be searchable either (typing "192" would reveal it).
            query.isBlank() ||
                listOf(h.nickname, if (h.requireBiometric) "" else h.address, h.group.orEmpty())
                    .any { it.contains(query.trim(), ignoreCase = true) }
        }
        .let { list ->
            when (sort) {
                HostSort.Name -> list
                HostSort.Recent -> list.sortedByDescending { it.lastConnectedAt ?: 0 }
                HostSort.Frequent -> list.sortedByDescending { it.connectCount }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onLocalShell) { Icon(Icons.Filled.Terminal, contentDescription = "Local shell") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Identities") },
                            leadingIcon = { Icon(Icons.Filled.Person, null) },
                            onClick = { menuOpen = false; onIdentities() },
                        )
                        DropdownMenuItem(
                            text = { Text("Keys") },
                            leadingIcon = { Icon(Icons.Filled.Key, null) },
                            onClick = { menuOpen = false; onKeys() },
                        )
                        DropdownMenuItem(
                            text = { Text("Snippets") },
                            leadingIcon = { Icon(Icons.Filled.Code, null) },
                            onClick = { menuOpen = false; onSnippets() },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menuOpen = false; onSettings() },
                        )
                        for (option in HostSort.entries) {
                            DropdownMenuItem(
                                text = { Text("Sort: ${option.label}" + if (option == sort) "  ✓" else "") },
                                onClick = { sort = option; menuOpen = false },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddHost) { Icon(Icons.Filled.Add, contentDescription = "Add host") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search, or user@host:port to connect") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { quick?.let(onQuickConnect) }),
                )
            }
            if (quick != null) {
                item {
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onQuickConnect(quick) })) {
                        ListItem(
                            headlineContent = { Text("Connect to ${quick.host}") },
                            supportingContent = { Text("${quick.username ?: "(ask for user)"} · port ${quick.port}") },
                            leadingContent = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        )
                    }
                }
            }
            if (sessions.isNotEmpty()) {
                item { SectionLabel("Open sessions") }
                items(sessions, key = { "s-" + it.id }) { entry ->
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenSession(entry.id) })) {
                        ListItem(
                            headlineContent = { Text(entry.label) },
                            supportingContent = {
                                Text(if (entry.running) entry.title ?: "Running in background" else "Ended")
                            },
                            leadingContent = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { onCloseSession(entry.id) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close session")
                                }
                            },
                        )
                    }
                }
            }
            val recent = recentQuick.mapNotNull { r -> QuickConnect.parse(r)?.let { r to it } }
                .filter { (text, _) -> query.isBlank() || text.contains(query.trim(), ignoreCase = true) }
            if (recent.isNotEmpty()) {
                item { SectionLabel("Recent quick connects") }
                items(recent, key = { "q-" + it.first }) { (text, target) ->
                    Card(Modifier.fillMaxWidth().combinedClickable(onClick = { onQuickConnect(target) })) {
                        ListItem(
                            headlineContent = { Text(text) },
                            leadingContent = { Icon(Icons.Filled.History, contentDescription = null) },
                            trailingContent = {
                                IconButton(onClick = { onForgetQuick(text) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove from history")
                                }
                            },
                        )
                    }
                }
            }
            item { SectionLabel("Hosts") }
            if (hosts.isEmpty()) {
                item {
                    Text(
                        "No hosts yet. Tap + to add one, or type user@host above to connect once.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.id }) { host ->
                val user = host.identityId?.let { identityById[it]?.username } ?: host.username
                Card(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onConnect(host) },
                        onLongClick = { menuHost = host },
                    ),
                ) {
                    HostContextMenu(
                        expanded = menuHost?.id == host.id,
                        onDismiss = { menuHost = null },
                        onEdit = { menuHost = null; onEditHost(host) },
                        onCopy = { menuHost = null; onCopyHost(host) },
                        onDelete = { menuHost = null; deleting = host },
                    )
                    ListItem(
                        headlineContent = { Text(host.nickname) },
                        supportingContent = {
                            Column {
                                Text(
                                    if (host.requireBiometric) {
                                        Masking.maskedTarget(user, host.address)
                                    } else {
                                        buildString {
                                            if (user != null) append("$user@")
                                            append(host.address)
                                            if (host.port != 22) append(":${host.port}")
                                        }
                                    },
                                )
                                if (host.group != null) {
                                    Text(host.group, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                        leadingContent = { Icon(Icons.Filled.Computer, contentDescription = null) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onEditHost(host) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${host.nickname}")
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    deleting?.let { host ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${host.nickname}?") },
            text = { Text("The saved host and its port forwards are removed. Identities and keys are kept.") },
            confirmButton = { TextButton(onClick = { deleting = null; onDeleteHost(host) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

/** The menu shown by long-pressing a host. */
@Composable
private fun HostContextMenu(expanded: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = onEdit)
        DropdownMenuItem(text = { Text("Copy as new config") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = onCopy)
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
        )
    }
}
