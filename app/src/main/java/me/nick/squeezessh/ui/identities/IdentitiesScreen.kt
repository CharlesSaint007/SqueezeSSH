package me.nick.squeezessh.ui.identities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.data.IdentityEntity
import me.nick.squeezessh.data.KeyEntity
import me.nick.squeezessh.ui.common.BackTopBar
import me.nick.squeezessh.ui.common.PickerField

@Composable
fun IdentitiesScreen(
    identities: List<IdentityEntity>,
    keys: List<KeyEntity>,
    onSave: (IdentityEntity, password: String?) -> Unit,
    onDelete: (IdentityEntity) -> Unit,
    onKeys: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<IdentityEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    val keyById = keys.associateBy { it.id }

    Scaffold(
        topBar = { BackTopBar("Identities", onBack) { TextButton(onClick = onKeys) { Text("Keys") } } },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = "Add identity") }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "An identity is a username plus a password and/or key, reusable across hosts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(identities, key = { it.id }) { identity ->
                Card(Modifier.fillMaxWidth().clickable { editing = identity }) {
                    ListItem(
                        headlineContent = { Text(identity.nickname) },
                        supportingContent = {
                            Text(buildList {
                                add(identity.username)
                                identity.keyId?.let { keyById[it] }?.let { add("key: ${it.name}") }
                                if (identity.passwordSecretId != null) add("password saved")
                            }.joinToString(" · "))
                        },
                        leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
                    )
                }
            }
        }
    }

    if (creating || editing != null) {
        IdentityDialog(
            existing = editing,
            keys = keys,
            onDismiss = { creating = false; editing = null },
            onSave = { identity, password -> onSave(identity, password); creating = false; editing = null },
            onDelete = { onDelete(it); editing = null },
        )
    }
}

@Composable
private fun IdentityDialog(
    existing: IdentityEntity?,
    keys: List<KeyEntity>,
    onDismiss: () -> Unit,
    onSave: (IdentityEntity, String?) -> Unit,
    onDelete: (IdentityEntity) -> Unit,
) {
    var nickname by remember { mutableStateOf(existing?.nickname.orEmpty()) }
    var username by remember { mutableStateOf(existing?.username.orEmpty()) }
    var keyId by remember { mutableStateOf(existing?.keyId) }
    var password by remember { mutableStateOf("") }
    var clearPassword by remember { mutableStateOf(false) }
    val hasSavedPassword = existing?.passwordSecretId != null && !clearPassword

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New identity" else "Edit identity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.trim() },
                    label = { Text("Username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname") },
                    placeholder = { Text(username.ifBlank { "e.g. Work" }) },
                    singleLine = true,
                )
                PickerField(
                    label = "Key",
                    selected = keys.firstOrNull { it.id == keyId },
                    options = keys,
                    optionLabel = { "${it.name} (${it.type})" },
                    noneLabel = "No key",
                    onSelect = { keyId = it?.id },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (hasSavedPassword) "Password (saved — type to replace)" else "Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (hasSavedPassword) {
                    TextButton(onClick = { clearPassword = true }) { Text("Remove saved password") }
                }
                if (existing != null) {
                    TextButton(onClick = { onDelete(existing) }) {
                        Text("Delete identity", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = username.isNotBlank(),
                onClick = {
                    val base = existing ?: IdentityEntity(nickname = "", username = "")
                    val passwordChange = when {
                        password.isNotEmpty() -> password
                        clearPassword -> ""
                        else -> null
                    }
                    onSave(base.copy(nickname = nickname.ifBlank { username }, username = username, keyId = keyId), passwordChange)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
