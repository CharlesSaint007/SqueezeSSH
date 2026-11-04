package me.nick.squeezessh.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.ui.common.BackTopBar
import java.util.UUID

private val ANSI_NAMES = listOf("Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White")

/**
 * Edit a custom theme, or make one from [base]. Built-in themes can't be changed, so editing one saves a copy.
 * Colors are typed as hex; the preview updates as they change and the theme applies only when saved.
 */
@Composable
fun ThemeEditorScreen(
    base: TerminalTheme,
    onSave: (TerminalTheme) -> Unit,
    onDelete: ((TerminalTheme) -> Unit)?,
    onBack: () -> Unit,
) {
    val editingCustom = base.id.startsWith(CustomThemeJson.ID_PREFIX)
    var name by remember { mutableStateOf(if (editingCustom) base.name else "${base.name} (copy)") }
    var foreground by remember { mutableStateOf(CustomThemeJson.toHex(base.foreground)) }
    var background by remember { mutableStateOf(CustomThemeJson.toHex(base.background)) }
    var cursor by remember { mutableStateOf(CustomThemeJson.toHex(base.cursor)) }
    var selection by remember { mutableStateOf(CustomThemeJson.toHex(base.selection)) }
    val ansi = remember { mutableStateListOf(*base.ansi.map(CustomThemeJson::toHex).toTypedArray()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The theme as currently typed, or null while any field isn't a valid color.
    val draft = runCatching {
        TerminalTheme(
            id = if (editingCustom) base.id else "",
            name = name.trim(),
            foreground = CustomThemeJson.parseHex(foreground),
            background = CustomThemeJson.parseHex(background),
            cursor = CustomThemeJson.parseHex(cursor),
            selection = CustomThemeJson.parseHex(selection),
            ansi = ansi.map(CustomThemeJson::parseHex),
        )
    }.getOrNull()

    Scaffold(
        topBar = {
            BackTopBar(if (editingCustom) "Edit theme" else "New theme", onBack) {
                if (editingCustom && onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete theme") }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Preview(draft ?: base)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ColorField("Background", value = background, modifier = Modifier.fillMaxWidth()) { background = it }
            ColorField("Text", value = foreground, modifier = Modifier.fillMaxWidth()) { foreground = it }
            ColorField("Cursor", value = cursor, modifier = Modifier.fillMaxWidth()) { cursor = it }
            ColorField("Selection", value = selection, modifier = Modifier.fillMaxWidth()) { selection = it }
            Text("ANSI colors", style = MaterialTheme.typography.titleMedium)
            for (i in 0 until 8) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorField(ANSI_NAMES[i], ansi[i], Modifier.weight(1f)) { ansi[i] = it }
                    ColorField("Bright ${ANSI_NAMES[i].lowercase()}", ansi[i + 8], Modifier.weight(1f)) { ansi[i + 8] = it }
                }
            }
            Button(
                enabled = draft != null && name.isNotBlank(),
                onClick = {
                    val theme = draft ?: return@Button
                    onSave(if (editingCustom) theme else theme.copy(id = CustomThemeJson.ID_PREFIX + UUID.randomUUID()))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save theme") }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${base.name}?") },
            text = { Text("Sessions and hosts using it switch to the default theme.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(base) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColorField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val parsed = runCatching { CustomThemeJson.parseHex(value) }.getOrNull()
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(7)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        isError = parsed == null,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        leadingIcon = {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
                    .background(parsed?.let { Color(it) } ?: Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
            )
        },
        modifier = modifier,
    )
}

/** A small fake terminal showing the theme's text, prompt, cursor, selection and all 16 colors. */
@Composable
private fun Preview(theme: TerminalTheme) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(theme.background)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("user@host", color = Color(theme.green), fontFamily = FontFamily.Monospace)
            Text(":", color = Color(theme.foreground), fontFamily = FontFamily.Monospace)
            Text("~", color = Color(theme.blue), fontFamily = FontFamily.Monospace)
            Text("$ ls ", color = Color(theme.foreground), fontFamily = FontFamily.Monospace)
            Box(Modifier.size(9.dp, 18.dp).background(Color(theme.cursor)))
        }
        Text(
            "selected text",
            color = Color(theme.foreground),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.background(Color(theme.selection)),
        )
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until 8) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)).background(Color(theme.ansi[row * 8 + i])))
                }
            }
        }
    }
}
