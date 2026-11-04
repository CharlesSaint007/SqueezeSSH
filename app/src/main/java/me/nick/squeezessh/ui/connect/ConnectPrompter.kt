package me.nick.squeezessh.ui.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import me.nick.squeezessh.ssh.HostKeyCheck
import me.nick.squeezessh.ssh.SshPrompts
import me.nick.squeezessh.ssh.SshTarget

/** A question the connection is waiting on. Answered from the UI; [answer] resumes the connection. */
sealed class Prompt<T> {
    private val result = CompletableDeferred<T>()
    fun answer(value: T) {
        result.complete(value)
    }
    suspend fun await(): T = result.await()

    class HostKey(val check: HostKeyCheck) : Prompt<Boolean>()
    class Password(val target: SshTarget, val attempt: Int) : Prompt<String?>()
    class KeyboardInteractive(val name: String, val instruction: String, val prompts: List<Pair<String, Boolean>>) :
        Prompt<List<String>?>()
}

/**
 * Bridges [SshPrompts] (called from the SSH thread) to Compose dialogs. The current prompt is observable state;
 * [PromptDialogs] renders it.
 */
class ConnectPrompter : SshPrompts {
    var current by mutableStateOf<Prompt<*>?>(null)
        private set

    private suspend fun <T> ask(prompt: Prompt<T>): T {
        current = prompt
        return try {
            prompt.await()
        } finally {
            if (current === prompt) current = null
        }
    }

    override suspend fun confirm(check: HostKeyCheck): Boolean = ask(Prompt.HostKey(check))

    override suspend fun password(target: SshTarget, attempt: Int): String? = ask(Prompt.Password(target, attempt))

    override suspend fun keyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<Pair<String, Boolean>>,
    ): List<String>? = ask(Prompt.KeyboardInteractive(name, instruction, prompts))

    /** Cancel whatever is pending, e.g. when the user leaves the screen. */
    fun cancel() {
        when (val p = current) {
            is Prompt.HostKey -> p.answer(false)
            is Prompt.Password -> p.answer(null)
            is Prompt.KeyboardInteractive -> p.answer(null)
            null -> Unit
        }
    }
}

@Composable
fun PromptDialogs(prompter: ConnectPrompter) {
    when (val prompt = prompter.current) {
        is Prompt.HostKey -> HostKeyDialog(prompt)
        is Prompt.Password -> PasswordDialog(prompt)
        is Prompt.KeyboardInteractive -> KeyboardInteractiveDialog(prompt)
        null -> Unit
    }
}

@Composable
private fun HostKeyDialog(prompt: Prompt.HostKey) {
    val check = prompt.check
    val changed = check as? HostKeyCheck.Changed
    AlertDialog(
        onDismissRequest = { prompt.answer(false) },
        title = { Text(if (changed != null) "⚠ Host key changed" else "Unknown host") },
        text = {
            Column {
                if (changed != null) {
                    Text(
                        "The key for ${check.hostPort} is different from the one saved earlier. " +
                            "If you didn't reinstall or re-key this server, someone may be intercepting the connection.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Saved key:", style = MaterialTheme.typography.labelMedium)
                    Text(changed.previousFingerprint, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("New key:", style = MaterialTheme.typography.labelMedium)
                } else {
                    Text("This is the first connection to ${check.hostPort}. Check that the fingerprint matches the server's.")
                    Spacer(Modifier.height(12.dp))
                }
                Text(check.keyType, style = MaterialTheme.typography.labelMedium)
                Text(check.fingerprint, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.answer(true) }) {
                Text(if (changed != null) "Replace saved key" else "Trust and connect")
            }
        },
        dismissButton = { TextButton(onClick = { prompt.answer(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(prompt: Prompt.Password) {
    var password by remember(prompt) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { prompt.answer(null) },
        title = { Text("Password") },
        text = {
            Column {
                Text("${prompt.target.username}@${prompt.target.host}")
                if (prompt.attempt > 1) {
                    Text("Wrong password, try again.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text("Password") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { prompt.answer(password) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { prompt.answer(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun KeyboardInteractiveDialog(prompt: Prompt.KeyboardInteractive) {
    val answers = remember(prompt) { mutableStateListOf(*Array(prompt.prompts.size) { "" }) }
    AlertDialog(
        onDismissRequest = { prompt.answer(null) },
        title = { Text(prompt.name.ifBlank { "Authentication" }) },
        text = {
            Column {
                if (prompt.instruction.isNotBlank()) {
                    Text(prompt.instruction)
                    Spacer(Modifier.height(8.dp))
                }
                prompt.prompts.forEachIndexed { i, (label, echo) ->
                    OutlinedTextField(
                        value = answers[i],
                        onValueChange = { answers[i] = it },
                        singleLine = true,
                        label = { Text(label.trim().removeSuffix(":")) },
                        visualTransformation = if (echo) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { prompt.answer(answers.toList()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { prompt.answer(null) }) { Text("Cancel") } },
    )
}
