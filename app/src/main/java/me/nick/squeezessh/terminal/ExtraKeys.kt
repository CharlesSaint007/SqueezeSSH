package me.nick.squeezessh.terminal

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A key on the extra-keys row. */
sealed interface ExtraKey {
    val label: String

    /** Sends an Android key code through the emulator, so modes like application-cursor keys are respected. */
    data class Code(override val label: String, val keyCode: Int) : ExtraKey

    /** Sends literal text. */
    data class Text(override val label: String, val text: String) : ExtraKey

    /** A sticky modifier: applies to the next key typed, then releases. */
    data class Modifier(override val label: String, val modifier: StickyModifier) : ExtraKey

    /** Switches the bar between the main keys and the function keys. */
    data class Page(override val label: String, val functionKeys: Boolean) : ExtraKey

    /** Opens one of the session's panels. */
    data class Panel(override val label: String, val panel: SessionPanel) : ExtraKey
}

enum class SessionPanel { Snippets, Forwards }

enum class StickyModifier { Ctrl, Alt }

/**
 * The main page: two rows laid out as a grid so the arrows form a cluster. The keyboard toggle is added as the
 * last cell of the second row on every page.
 */
val MainKeys: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
        ExtraKey.Text("/", "/"),
        ExtraKey.Text("|", "|"),
        ExtraKey.Text("-", "-"),
        ExtraKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
        ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
        ExtraKey.Code("END", KeyEvent.KEYCODE_MOVE_END),
        ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
        ExtraKey.Page("FN", functionKeys = true),
    ),
    listOf(
        ExtraKey.Code("TAB", KeyEvent.KEYCODE_TAB),
        ExtraKey.Modifier("CTRL", StickyModifier.Ctrl),
        ExtraKey.Modifier("ALT", StickyModifier.Alt),
        ExtraKey.Text("~", "~"),
        ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
        ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        ExtraKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ),
)

/** The FN page: F1–F12, the snippet and port-forward panels, and a key back to the main page. */
val FunctionKeys: List<List<ExtraKey>> = listOf(
    listOf(
        ExtraKey.Code("F1", KeyEvent.KEYCODE_F1),
        ExtraKey.Code("F2", KeyEvent.KEYCODE_F2),
        ExtraKey.Code("F3", KeyEvent.KEYCODE_F3),
        ExtraKey.Code("F4", KeyEvent.KEYCODE_F4),
        ExtraKey.Code("F5", KeyEvent.KEYCODE_F5),
        ExtraKey.Code("F6", KeyEvent.KEYCODE_F6),
        ExtraKey.Panel("SNIP", SessionPanel.Snippets),
        ExtraKey.Page("BACK", functionKeys = false),
    ),
    listOf(
        ExtraKey.Code("F7", KeyEvent.KEYCODE_F7),
        ExtraKey.Code("F8", KeyEvent.KEYCODE_F8),
        ExtraKey.Code("F9", KeyEvent.KEYCODE_F9),
        ExtraKey.Code("F10", KeyEvent.KEYCODE_F10),
        ExtraKey.Code("F11", KeyEvent.KEYCODE_F11),
        ExtraKey.Code("F12", KeyEvent.KEYCODE_F12),
        ExtraKey.Panel("PORTS", SessionPanel.Forwards),
    ),
)

/**
 * The key bar above the soft keyboard. Its background also fills the navigation-bar area below it, so the
 * bar reaches the bottom of the screen when the keyboard is hidden.
 */
@Composable
fun ExtraKeysBar(
    rows: List<List<ExtraKey>>,
    activeModifiers: Set<StickyModifier>,
    keyboardVisible: Boolean,
    onKey: (ExtraKey) -> Unit,
    /** A held CODE/TEXT key was let go: the keystrokes it sent are over. */
    onKeyEnd: () -> Unit,
    keyRepeat: KeyRepeatConfig,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = rows.maxOf { it.size }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))) {
            rows.forEachIndexed { index, row ->
                Row(Modifier.fillMaxWidth().height(KEY_HEIGHT)) {
                    for (key in row) {
                        val active = key is ExtraKey.Modifier && key.modifier in activeModifiers
                        val sends = key is ExtraKey.Code || key is ExtraKey.Text
                        KeyCell(
                            // Keys that type fire on press and repeat while held; the rest act on a tap.
                            onPress = if (sends) ({ onKey(key) }) else null,
                            onClick = { onKey(key) },
                            keyRepeat = keyRepeat,
                            onPressEnd = onKeyEnd,
                        ) {
                            if (key is ExtraKey.Page && !key.functionKeys) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = "Back to main keys",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                // Shrinks to fit, so labels like HOME and PGDN stay whole on narrow phones.
                                BasicText(
                                    key.label,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    ),
                                    maxLines = 1,
                                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 16.sp),
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
                    }
                    // The keyboard toggle takes the last cell of the last row.
                    if (index == rows.lastIndex) {
                        repeat(columns - row.size - 1) { Spacer(Modifier.weight(1f)) }
                        KeyCell(onClick = onToggleKeyboard) {
                            Icon(
                                if (keyboardVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                                contentDescription = if (keyboardVisible) "Hide keyboard" else "Show keyboard",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.KeyCell(
    onClick: () -> Unit,
    onPress: (() -> Unit)? = null,
    keyRepeat: KeyRepeatConfig = KeyRepeatConfig(enabled = false, delayMs = 0, perSecond = 1),
    onPressEnd: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val click = rememberUpdatedState(onClick)
    val press = rememberUpdatedState(onPress)
    val repeat = rememberUpdatedState(keyRepeat)
    val pressEnd = rememberUpdatedState(onPressEnd)
    val base = Modifier.weight(1f).fillMaxHeight()
    Box(
        contentAlignment = Alignment.Center,
        modifier = if (onPress == null) {
            base.clickable {
                // The same tick as a soft-keyboard key; it follows the system "touch feedback" setting.
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                click.value()
            }
        } else {
            base.pointerInput(Unit) {
                detectTapGestures(onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    coroutineScope {
                        val typing = launch { repeatKey(repeat.value) { press.value?.invoke() } }
                        tryAwaitRelease()
                        typing.cancel()
                    }
                    pressEnd.value()
                })
            }
        },
    ) { content() }
}

/** Two rows come to about 70dp, close to the key bars of other terminal apps. */
private val KEY_HEIGHT = 35.dp
