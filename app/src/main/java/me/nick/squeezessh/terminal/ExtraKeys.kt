package me.nick.squeezessh.terminal

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A key on the extra-keys row. */
sealed interface ExtraKey {
    val label: String

    /** Sends an Android key code through the emulator, so modes like application-cursor keys are respected. */
    data class Code(override val label: String, val keyCode: Int) : ExtraKey

    /** Sends literal text. */
    data class Text(override val label: String, val text: String) : ExtraKey

    /** A sticky modifier: applies to the next key typed, then releases. */
    data class Modifier(override val label: String, val modifier: StickyModifier) : ExtraKey
}

enum class StickyModifier { Ctrl, Alt }

val DefaultExtraKeys: List<ExtraKey> = listOf(
    ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Modifier("CTRL", StickyModifier.Ctrl),
    ExtraKey.Modifier("ALT", StickyModifier.Alt),
    ExtraKey.Code("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("END", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Text("|", "|"),
    ExtraKey.Text("/", "/"),
    ExtraKey.Text("-", "-"),
    ExtraKey.Text("~", "~"),
)

@Composable
fun ExtraKeysRow(
    keys: List<ExtraKey>,
    ctrlActive: Boolean,
    altActive: Boolean,
    onKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            for (key in keys) {
                val active = key is ExtraKey.Modifier &&
                    ((key.modifier == StickyModifier.Ctrl && ctrlActive) || (key.modifier == StickyModifier.Alt && altActive))
                TextButton(
                    onClick = { onKey(key) },
                    modifier = Modifier.height(44.dp).widthIn(min = 48.dp),
                ) {
                    Text(
                        key.label,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
