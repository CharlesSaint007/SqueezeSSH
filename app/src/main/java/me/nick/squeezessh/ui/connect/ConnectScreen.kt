package me.nick.squeezessh.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.nick.squeezessh.data.HostEntity
import me.nick.squeezessh.ssh.ConnectionService
import me.nick.squeezessh.ssh.SshShellBackend
import me.nick.squeezessh.ui.common.BackTopBar

/**
 * Connects to [host] and hands the authenticated shell to [onConnected]. Host-key, password and 2FA questions
 * are shown as dialogs while the connection waits.
 */
@Composable
fun ConnectScreen(
    host: HostEntity,
    connections: ConnectionService,
    onConnected: (SshShellBackend) -> Unit,
    onBack: () -> Unit,
    usernameOverride: String? = null,
) {
    val prompter = remember { ConnectPrompter() }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            val backend = connections.openShell(host, prompter, usernameOverride)
            onConnected(backend)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    DisposableEffect(Unit) { onDispose { prompter.cancel() } }

    Scaffold(topBar = { BackTopBar(host.nickname, onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            val target = buildString {
                append(host.address)
                if (host.port != 22) append(":${host.port}")
            }
            if (error == null) {
                CircularProgressIndicator()
                Text("Connecting to $target…", style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Couldn't connect to $target", style = MaterialTheme.typography.titleMedium)
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = { attempt++ }) { Text("Retry") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
    PromptDialogs(prompter)
}
