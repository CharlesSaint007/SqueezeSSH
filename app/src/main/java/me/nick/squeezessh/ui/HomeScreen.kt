package me.nick.squeezessh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.R
import me.nick.squeezessh.session.SessionEntry

/** Milestone-2 home: open local shells and switch between them. Becomes the hosts list in milestone 3. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sessions: List<SessionEntry>,
    onOpenLocalShell: () -> Unit,
    onOpenSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenLocalShell,
                icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                text = { Text("Local shell") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "No open sessions. SSH hosts arrive in a later build; for now you can open a local shell.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { Text("Open sessions", style = MaterialTheme.typography.titleMedium) }
            }
            items(sessions, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth().clickable { onOpenSession(entry.id) }) {
                    ListItem(
                        headlineContent = { Text(entry.session.title ?: entry.label) },
                        supportingContent = {
                            Text(if (entry.session.isRunning) entry.label else "${entry.label} (ended)")
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
    }
}
