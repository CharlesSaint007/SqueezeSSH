package me.nick.squeezessh.ui.snippets

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.ui.common.BackTopBar

@Composable
fun SnippetsScreen(
    snippets: List<SnippetEntity>,
    onSave: (SnippetEntity) -> Unit,
    onDelete: (SnippetEntity) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<SnippetEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar("Snippets", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New snippet") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SnippetHelp() }
            items(snippets, key = { it.id }) { snippet ->
                Card(Modifier.fillMaxWidth().clickable { editing = snippet }) {
                    SnippetListItem(snippet)
                }
            }
        }
    }

    if (creating || editing != null) {
        SnippetDialog(
            existing = editing,
            onSave = { onSave(it); creating = false; editing = null },
            onDelete = editing?.let { e -> { onDelete(e); editing = null } },
            onDismiss = { creating = false; editing = null },
        )
    }
}

/** How snippets get used, shown above the list. */
@Composable
private fun SnippetHelp() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Using snippets", style = MaterialTheme.typography.titleSmall)
            for ((how, what) in listOf(
                "Long-press the terminal" to "opens the snippet list; tap one to type it in. The list also has Select text, for copying.",
                "FN → SNIP" to "on the key bar opens the same list.",
                "Startup snippet" to "in a host's settings types one in as soon as that host connects.",
                "Press Enter after it" to "runs the snippet as a command; turn it off to just type the text.",
            )) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(how) }
                        append(": $what")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SnippetListItem(snippet: SnippetEntity) {
    ListItem(
        headlineContent = { Text(snippet.name) },
        supportingContent = {
            Text(
                snippet.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun SnippetDialog(
    existing: SnippetEntity?,
    onSave: (SnippetEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var text by remember { mutableStateOf(existing?.text.orEmpty()) }
    var sendEnter by remember { mutableStateOf(existing?.sendEnter ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New snippet" else "Edit snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    placeholder = { Text("sudo apt update") },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.heightIn(min = 100.dp, max = 240.dp),
                )
                Row(Modifier.fillMaxWidth().clickable { sendEnter = !sendEnter }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sendEnter, onCheckedChange = { sendEnter = it })
                    Text("Press Enter after it")
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete snippet", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && text.isNotEmpty(),
                onClick = {
                    val base = existing ?: SnippetEntity(name = "", text = "")
                    onSave(base.copy(name = name.trim(), text = text, sendEnter = sendEnter))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
