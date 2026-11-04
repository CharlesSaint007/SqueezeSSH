package me.nickreed.squeezessh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.nickreed.squeezessh.ui.theme.BuiltInThemes
import me.nickreed.squeezessh.ui.theme.SqueezeTheme
import me.nickreed.squeezessh.ui.theme.TerminalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeId by rememberSaveable { mutableStateOf(BuiltInThemes.default.id) }
            val theme = BuiltInThemes.byId(themeId)
            SqueezeTheme(theme) {
                ThemePreviewScreen(selected = theme, onSelect = { themeId = it.id })
            }
        }
    }
}

/**
 * Milestone-1 placeholder: proves the build, signing, and theme derivation end to end.
 * Replaced by the hosts list in milestone 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePreviewScreen(selected: TerminalTheme, onSelect: (TerminalTheme) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Pick a theme. The app UI below follows the terminal palette.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(BuiltInThemes.all, key = { it.id }) { theme ->
                ThemeRow(theme, isSelected = theme.id == selected.id, onClick = { onSelect(theme) })
            }
        }
    }
}

@Composable
private fun ThemeRow(theme: TerminalTheme, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.titleMedium)
                TerminalSample(theme)
            }
        }
    }
}

@Composable
private fun TerminalSample(theme: TerminalTheme) {
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
