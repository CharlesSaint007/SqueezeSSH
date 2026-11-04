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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
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
import me.nick.squeezessh.session.SessionInfo

/** A parsed `user@host:port` quick-connect string. */
data class QuickConnect(val username: String?, val host: String, val port: Int) {
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
    onConnect: (HostEntity) -> Unit,
    onQuickConnect: (QuickConnect) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (HostEntity) -> Unit,
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
    val quick = QuickConnect.parse(query)?.takeIf { query.contains('@') || query.contains(':') }
    val identityById = identities.associateBy { it.id }

    val visible = hosts
        .filter { h ->
            query.isBlank() || listOf(h.nickname, h.address, h.group.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }
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
                        onLongClick = { onEditHost(host) },
                    ),
                ) {
                    ListItem(
                        headlineContent = { Text(host.nickname) },
                        supportingContent = {
                            Column {
                                Text(buildString {
                                    if (user != null) append("$user@")
                                    append(host.address)
                                    if (host.port != 22) append(":${host.port}")
                                })
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
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}
