package me.nick.squeezessh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.settings.UiThemeMode
import me.nick.squeezessh.ui.theme.BuiltInThemes
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
    onBitwarden: () -> Unit,
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
            item { SectionTitle("Terminal theme") }
            items(BuiltInThemes.all, key = { "term-" + it.id }) { theme ->
                ThemeRow(theme, selected = theme.id == settings.terminalThemeId, onClick = { onTerminalTheme(theme.id) })
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
                items(BuiltInThemes.all, key = { "ui-" + it.id }) { theme ->
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

@Composable
private fun ThemeRow(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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
