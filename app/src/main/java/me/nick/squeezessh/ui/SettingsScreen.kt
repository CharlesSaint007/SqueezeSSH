package me.nick.squeezessh.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.settings.UiThemeMode
import me.nick.squeezessh.ui.theme.TerminalTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onTerminalTheme: (String) -> Unit,
    onUiThemeMode: (UiThemeMode) -> Unit,
    onUiTheme: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onExitBehavior: (ExitBehavior) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onVolumeKeysFontSize: (Boolean) -> Unit,
    onAutoReconnect: (Boolean) -> Unit,
    onKeyRepeat: (Boolean) -> Unit,
    onKeyRepeatDelay: (Int) -> Unit,
    onKeyRepeatRate: (Int) -> Unit,
    biometricAvailable: Boolean,
    onRequireBiometricToOpen: (Boolean) -> Unit,
    onBitwarden: () -> Unit,
    onBackup: () -> Unit,
    /** How many hosts have a saved host key. */
    knownHostCount: Int,
    onClearKnownHosts: () -> Unit,
    /** Open the theme editor on [id]: a custom theme to edit, or any theme to copy. */
    onEditTheme: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle("Sync")
                Card(Modifier.fillMaxWidth().clickable(onClick = onBitwarden)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Bitwarden / Vaultwarden", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Sync SSH keys with your vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onBackup)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Backup & restore", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Export hosts and settings to a file, or import them on a new phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionTitle("Security")
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = biometricAvailable || settings.requireBiometricToOpen) {
                            onRequireBiometricToOpen(!settings.requireBiometricToOpen)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Require fingerprint to open the app", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (biometricAvailable) {
                                "Asks for your fingerprint, face or screen lock when the app opens, and again after it's been " +
                                    "in the background for a minute. Running sessions are hidden until you unlock."
                            } else {
                                "Set up a fingerprint or screen lock in your phone's settings to use this."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.requireBiometricToOpen,
                        onCheckedChange = onRequireBiometricToOpen,
                        enabled = biometricAvailable || settings.requireBiometricToOpen,
                    )
                }
            }
            item {
                var confirmClear by remember { mutableStateOf(false) }
                Card(Modifier.fillMaxWidth().clickable(enabled = knownHostCount > 0) { confirmClear = true }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Clear known hosts", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (knownHostCount) {
                                0 -> "No saved host keys"
                                1 -> "1 host has a saved key"
                                else -> "$knownHostCount hosts have a saved key"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (confirmClear) {
                    AlertDialog(
                        onDismissRequest = { confirmClear = false },
                        title = { Text("Clear known hosts?") },
                        text = {
                            Text(
                                "This forgets the saved host key of ${if (knownHostCount == 1) "1 host" else "all $knownHostCount hosts"}. " +
                                    "The next time you connect to each one, you'll be shown its key fingerprint and asked to accept " +
                                    "or reject it again. Check it against the server's real fingerprint, because a changed key can " +
                                    "mean someone is intercepting the connection.\n\nYour hosts, identities and keys are not affected.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmClear = false; onClearKnownHosts() }) {
                                Text("Clear", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
                    )
                }
            }
            item {
                SectionTitle("Terminal theme")
                Text(
                    "Long-press a theme to edit it (built-in themes are copied first).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(settings.themes.all, key = { "term-" + it.id }) { theme ->
                ThemeRow(
                    theme,
                    selected = theme.id == settings.terminalThemeId,
                    onClick = { onTerminalTheme(theme.id) },
                    onLongClick = { onEditTheme(theme.id) },
                )
            }
            item {
                TextButton(onClick = { onEditTheme(settings.terminalThemeId) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("New theme from the current one")
                }
            }

            item {
                SectionTitle("App theme")
                Text(
                    "Follow the terminal theme, or pick a separate theme for menus and screens.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                val modes = listOf(UiThemeMode.FollowTerminal to "Follow terminal", UiThemeMode.Separate to "Separate")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.uiThemeMode == mode,
                            onClick = { onUiThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
            }
            if (settings.uiThemeMode == UiThemeMode.Separate) {
                items(settings.themes.all, key = { "ui-" + it.id }) { theme ->
                    ThemeRow(theme, selected = theme.id == settings.uiThemeId, onClick = { onUiTheme(theme.id) })
                }
            }

            item {
                SectionTitle("Font size: ${settings.fontSizeSp} sp")
                Slider(
                    value = settings.fontSizeSp.toFloat(),
                    onValueChange = { onFontSize(it.toInt()) },
                    valueRange = Settings.MIN_FONT_SIZE_SP.toFloat()..Settings.MAX_FONT_SIZE_SP.toFloat(),
                    steps = Settings.MAX_FONT_SIZE_SP - Settings.MIN_FONT_SIZE_SP - 1,
                )
                Text(
                    "You can also pinch to zoom in a terminal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onVolumeKeysFontSize(!settings.volumeKeysFontSize) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Volume keys change font size", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "In a terminal, volume up and down make the text bigger or smaller.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.volumeKeysFontSize, onCheckedChange = onVolumeKeysFontSize)
                }
            }

            item {
                SectionTitle("Key bar")
                Row(
                    Modifier.fillMaxWidth().clickable { onKeyRepeat(!settings.keyRepeat) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Key repeat", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Holding a key above the keyboard types it again and again. CTRL, ALT and FN never repeat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.keyRepeat, onCheckedChange = onKeyRepeat)
                }
                if (settings.keyRepeat) {
                    Text("Repeat delay: ${settings.keyRepeatDelayMs} ms", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = settings.keyRepeatDelayMs.toFloat(),
                        onValueChange = { onKeyRepeatDelay((it / 50).roundToInt() * 50) },
                        valueRange = Settings.MIN_REPEAT_DELAY_MS.toFloat()..Settings.MAX_REPEAT_DELAY_MS.toFloat(),
                    )
                    Text("Repeat speed: ${settings.keyRepeatPerSecond} per second", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = settings.keyRepeatPerSecond.toFloat(),
                        onValueChange = { onKeyRepeatRate(it.roundToInt()) },
                        valueRange = Settings.MIN_REPEAT_PER_SECOND.toFloat()..Settings.MAX_REPEAT_PER_SECOND.toFloat(),
                    )
                }
            }

            item {
                SectionTitle("Sessions")
                Text("When leaving a running session", style = MaterialTheme.typography.bodyMedium)
            }
            items(ExitBehavior.entries, key = { "exit-" + it.name }) { behavior ->
                Row(
                    Modifier.fillMaxWidth().clickable { onExitBehavior(behavior) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.exitBehavior == behavior, onClick = { onExitBehavior(behavior) })
                    Text(
                        when (behavior) {
                            ExitBehavior.Ask -> "Ask each time"
                            ExitBehavior.KeepRunning -> "Keep running in the background"
                            ExitBehavior.Disconnect -> "Disconnect"
                        },
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onAutoReconnect(!settings.autoReconnect) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reconnect automatically", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "When a connection drops while you're looking at it, connect again. Off: ask first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.autoReconnect, onCheckedChange = onAutoReconnect)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { onKeepAwake(!settings.keepAwake) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep device awake", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Holds the CPU and Wi-Fi awake while sessions run, so connections survive the screen " +
                                "being off. Uses more battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.keepAwake, onCheckedChange = onKeepAwake)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeRow(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleMedium)
                ThemeSample(theme)
            }
        }
    }
}

@Composable
private fun ThemeSample(theme: TerminalTheme) {
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(theme.background))
            .padding(8.dp),
    ) {
        Text("user@host:~$ ls", color = Color(theme.foreground), fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            theme.ansi.take(8).forEach { c ->
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Color(c)))
            }
        }
    }
}
