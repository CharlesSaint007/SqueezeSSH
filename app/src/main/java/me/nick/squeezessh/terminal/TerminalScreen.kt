package me.nick.squeezessh.terminal

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import com.termux.terminal.KeyHandler
import kotlinx.coroutines.delay
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.nick.squeezessh.session.NoOpSessionClient
import me.nick.squeezessh.session.SessionEntry
import me.nick.squeezessh.session.SessionManager
import me.nick.squeezessh.data.SnippetEntity
import me.nick.squeezessh.settings.ExitBehavior
import me.nick.squeezessh.ssh.SnippetText
import me.nick.squeezessh.settings.Settings
import me.nick.squeezessh.ui.theme.ColorMath

/** Holds the view and the sticky-modifier state so the view clients and the extra-keys row share them. */
private class TerminalState {
    var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var functionKeys by mutableStateOf(false)
    var panel by mutableStateOf<SessionPanel?>(null)

    /** Where the last long press was, so "Select text" can start the selection there. */
    var longPressEvent: MotionEvent? = null

    fun releaseModifiers() {
        ctrl = false
        alt = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    entry: SessionEntry,
    sessionManager: SessionManager,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    /** Volume up/down change the font size while this screen is shown. */
    volumeKeysFontSize: Boolean,
    keyRepeat: KeyRepeatConfig,
    snippets: List<SnippetEntity>,
    onManageSnippets: () -> Unit,
    /** Remember the keyboard-autocomplete choice on this session's saved host. */
    onKeyboardAutocomplete: (Boolean) -> Unit,
    /** Connect this session's host again; null when it can't be (local shell, quick connect). */
    onReconnect: (() -> Unit)?,
    autoReconnect: Boolean,
    exitBehavior: ExitBehavior,
    onRememberExitBehavior: (ExitBehavior) -> Unit,
    /** Leave the screen; [keepRunning] says whether the session stays open in the background. */
    onLeave: (keepRunning: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val state = remember(entry.id) { TerminalState() }
    var appliedAutocomplete by remember(entry.id) { mutableStateOf(entry.keyboardAutocomplete) }
    var askExit by remember { mutableStateOf(false) }

    // An ended session has nothing to keep; otherwise follow the saved preference or ask.
    val requestLeave = {
        when {
            !entry.session.isRunning -> onLeave(false)
            exitBehavior == ExitBehavior.KeepRunning -> onLeave(true)
            exitBehavior == ExitBehavior.Disconnect -> onLeave(false)
            else -> askExit = true
        }
    }
    BackHandler(onBack = requestLeave)
    // Font size in px for the current font scale; recomputed when the configuration changes.
    val textPx = with(LocalDensity.current) { fontSizeSp.sp.roundToPx() }
    // The view client outlives recompositions, so it reads these through state rather than capturing them.
    val currentFontSp = rememberUpdatedState(fontSizeSp)
    val onFontSizeChangeState = rememberUpdatedState(onFontSizeChange)
    val volumeKeysState = rememberUpdatedState(volumeKeysFontSize)

    // When the remote side ends the session (`exit`, a dropped connection), leave the screen; there is nothing
    // left to type into. If it ended on its own right after connecting, the message stays briefly visible.
    val leave = rememberUpdatedState(onLeave)
    var finished by remember(entry.id) { mutableStateOf(!entry.session.isRunning) }
    val reconnect = rememberUpdatedState(onReconnect)
    var dropped by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        if (entry.droppedUnexpectedly && reconnect.value != null) {
            // The connection broke: keep the screen so the output stays readable, and reconnect from here.
            dropped = true
            if (autoReconnect) {
                delay(AUTO_RECONNECT_DELAY_MS)
                reconnect.value?.invoke()
            }
        } else {
            delay(FINISHED_LINGER_MS)
            leave.value(false)
        }
    }

    // Attach this screen to the session's events while it is shown; sessions keep running when it is not.
    DisposableEffect(entry.id) {
        sessionManager.client.delegate = screenSessionClient(context, state, onFinished = { finished = true })
        onDispose { sessionManager.client.delegate = null }
    }

    val keyboardVisible = WindowInsets.isImeVisible
    val view = LocalView.current
    // The terminal fills the window edge to edge, so the status bar sits on the terminal background; match its
    // icon color to that background.
    val darkBackground = ColorMath.isDark(entry.theme.background)
    DisposableEffect(view, darkBackground) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = !darkBackground
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(entry.theme.background))
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    state.view = this
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // setTextSize creates the renderer; setTypeface needs it to exist.
                    setTextSize(textPx)
                    setTypeface(loadTerminalTypeface(ctx))
                    setTerminalViewClient(
                        ScreenViewClient(
                            state = state,
                            currentFontSp = { currentFontSp.value },
                            onFontSizeChange = { onFontSizeChangeState.value(it) },
                            volumeKeysFontSize = { volumeKeysState.value },
                            normalKeyboard = { entry.keyboardAutocomplete },
                            onTap = { entry.keyBarVisible = !entry.keyBarVisible },
                            onLongPressed = { e ->
                                state.view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                state.longPressEvent = MotionEvent.obtain(e)
                                state.panel = SessionPanel.Snippets
                            },
                        ),
                    )
                    attachSession(entry.session)
                    // Focus can only be taken once the view is in the window; until then key presses would go
                    // to whatever Compose focused first. A live session opens with the keyboard up, ready to type.
                    post {
                        requestFocus()
                        if (entry.session.isRunning) showKeyboardWhenFocused(this)
                    }
                }
            },
            update = { view ->
                // Keeps the screen on only while this session is on screen; the flag leaves with the view.
                view.keepScreenOn = entry.keepScreenOn
                // The keyboard reads the field type when it starts typing, so tell it when the type changed.
                if (appliedAutocomplete != entry.keyboardAutocomplete) {
                    appliedAutocomplete = entry.keyboardAutocomplete
                    view.context.getSystemService(InputMethodManager::class.java).restartInput(view)
                }
                view.setTextSize(textPx)
                view.attachSession(entry.session)
                view.invalidate()
            },
            onRelease = { state.view = null },
        )
        if (entry.keyBarVisible) {
            ExtraKeysBar(
                rows = if (state.functionKeys) FunctionKeys else MainKeys,
                activeModifiers = buildSet {
                    if (state.ctrl) add(StickyModifier.Ctrl)
                    if (state.alt) add(StickyModifier.Alt)
                },
                keyboardVisible = keyboardVisible,
                keyRepeat = keyRepeat,
                onKeyEnd = { state.releaseModifiers() },
                onKey = { key ->
                    onExtraKey(key, state, entry.session)
                    // Tapping a Compose button moves focus off the terminal; hand it back.
                    state.view?.requestFocus()
                },
                onToggleKeyboard = {
                    state.view?.let { if (keyboardVisible) hideKeyboard(it) else showKeyboard(it) }
                },
            )
        } else {
            // The bar normally covers the navigation-bar area; without it, keep the terminal clear of it.
            Spacer(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars))
        }
    }

    if (dropped) {
        AlertDialog(
            onDismissRequest = { dropped = false },
            title = { Text("Connection lost") },
            text = {
                Text(if (autoReconnect) "Reconnecting to ${entry.label}…" else "The connection to ${entry.label} dropped.")
            },
            confirmButton = { TextButton(onClick = { reconnect.value?.invoke() }) { Text("Reconnect") } },
            dismissButton = { TextButton(onClick = { dropped = false; onLeave(false) }) { Text("Close") } },
        )
    }

    when (state.panel) {
        SessionPanel.Snippets -> SnippetPicker(
            snippets = snippets,
            onPick = { snippet ->
                state.panel = null
                entry.session.write(SnippetText.of(snippet))
                state.view?.requestFocus()
            },
            onManage = { state.panel = null; onManageSnippets() },
            onSelectText = state.longPressEvent?.let { event ->
                {
                    state.panel = null
                    state.view?.startTextSelectionMode(event)
                }
            },
            // Only the long-press version of the dialog (the one that can select text) has session options.
            options = state.longPressEvent?.let {
                SessionOptions(
                    keepScreenOn = entry.keepScreenOn,
                    onKeepScreenOn = { entry.keepScreenOn = it },
                    keyboardAutocomplete = entry.keyboardAutocomplete,
                    onKeyboardAutocomplete = { on ->
                        entry.keyboardAutocomplete = on
                        onKeyboardAutocomplete(on)
                    },
                )
            },
            onDismiss = { state.panel = null; state.longPressEvent = null },
        )
        SessionPanel.Forwards -> ForwardsSheet(entry.forwards, onDismiss = { state.panel = null })
        null -> Unit
    }

    if (askExit) {
        ExitDialog(
            label = entry.label,
            onKeep = { remember ->
                askExit = false
                if (remember) onRememberExitBehavior(ExitBehavior.KeepRunning)
                onLeave(true)
            },
            onDisconnect = { remember ->
                askExit = false
                if (remember) onRememberExitBehavior(ExitBehavior.Disconnect)
                onLeave(false)
            },
            onDismiss = { askExit = false },
        )
    }
}

@Composable
private fun ExitDialog(
    label: String,
    onKeep: (remember: Boolean) -> Unit,
    onDisconnect: (remember: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave $label?") },
        text = {
            Column {
                Text("Keep the session running in the background? A notification will take you back to it.")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp).clickable { rememberChoice = !rememberChoice },
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                    Text("Remember my choice")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onKeep(rememberChoice) }) { Text("Keep running") } },
        dismissButton = { TextButton(onClick = { onDisconnect(rememberChoice) }) { Text("Disconnect") } },
    )
}

/** Send one keystroke. Sticky CTRL/ALT stay set so a held key repeats with them; the bar releases them at the end. */
private fun onExtraKey(key: ExtraKey, state: TerminalState, session: TerminalSession) {
    when (key) {
        is ExtraKey.Modifier -> when (key.modifier) {
            StickyModifier.Ctrl -> state.ctrl = !state.ctrl
            StickyModifier.Alt -> state.alt = !state.alt
        }
        is ExtraKey.Page -> state.functionKeys = key.functionKeys
        is ExtraKey.Panel -> {
            state.longPressEvent = null
            state.panel = key.panel
        }
        is ExtraKey.Code -> {
            var mod = 0
            if (state.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
            if (state.alt) mod = mod or KeyHandler.KEYMOD_ALT
            state.view?.handleKeyCode(key.keyCode, mod)
        }
        is ExtraKey.Text -> {
            val view = state.view
            if (view != null) {
                key.text.codePoints().forEach { cp -> view.inputCodePoint(cp, state.ctrl, state.alt) }
            } else {
                session.write(key.text)
            }
        }
    }
}

private fun pasteClipboard(context: Context, session: TerminalSession) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
    session.emulator?.paste(text)
}

private fun showKeyboard(view: TerminalView) {
    view.requestFocus()
    view.context.getSystemService(InputMethodManager::class.java).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
}

/**
 * Show the keyboard once the window has input focus. Asked for any earlier (as the screen opens, mid
 * navigation transition), the input method ignores the request.
 */
private fun showKeyboardWhenFocused(view: TerminalView) {
    if (view.hasWindowFocus()) {
        showKeyboard(view)
        return
    }
    view.viewTreeObserver.addOnWindowFocusChangeListener(object : ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) return
            view.viewTreeObserver.removeOnWindowFocusChangeListener(this)
            showKeyboard(view)
        }
    })
}

private fun hideKeyboard(view: TerminalView) {
    view.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
}

/** How long the "[Connection closed]" line stays on screen before returning to the host list. */
private const val FINISHED_LINGER_MS = 600L

/** A short pause before reconnecting by itself, so a flapping network isn't hammered. */
private const val AUTO_RECONNECT_DELAY_MS = 2_000L

private var cachedTypeface: Typeface? = null

private fun loadTerminalTypeface(context: Context): Typeface =
    cachedTypeface ?: runCatching { Typeface.createFromAsset(context.assets, "fonts/JetBrainsMono-Regular.ttf") }
        .getOrDefault(Typeface.MONOSPACE)
        .also { cachedTypeface = it }

private fun screenSessionClient(context: Context, state: TerminalState, onFinished: () -> Unit): TerminalSessionClient =
    object : TerminalSessionClient by NoOpSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            state.view?.onScreenUpdated()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            state.view?.onScreenUpdated()
            onFinished()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Terminal", text))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            session?.let { pasteClipboard(context, it) }
        }

        override fun onColorsChanged(session: TerminalSession) {
            state.view?.invalidate()
        }
    }

/** Bridges TerminalView's callbacks to the extra-keys modifiers and pinch-to-zoom. */
private class ScreenViewClient(
    private val state: TerminalState,
    private val currentFontSp: () -> Int,
    private val onFontSizeChange: (Int) -> Unit,
    private val volumeKeysFontSize: () -> Boolean,
    private val normalKeyboard: () -> Boolean,
    private val onTap: () -> Unit,
    private val onLongPressed: (MotionEvent) -> Unit,
) : TerminalViewClient {
    private fun stepFont(step: Int) {
        val next = (currentFontSp() + step).coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP)
        if (next != currentFontSp()) onFontSizeChange(next)
    }

    override fun onScale(scale: Float): Float {
        // TerminalView passes the cumulative gesture scale; step the font one sp per ~10% of pinch,
        // and return 1 to restart the accumulation after each step.
        if (scale < 0.9f || scale > 1.1f) {
            stepFont(if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    // A short tap shows or hides the key bar (the keyboard is toggled from the bar).
    override fun onSingleTapUp(e: MotionEvent) {
        onTap()
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseNormalKeyboard() = normalKeyboard()
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (!volumeKeysFontSize()) return false
        // Holding the key repeats, which keeps stepping the size.
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true.also { stepFont(1) }
            KeyEvent.KEYCODE_VOLUME_DOWN -> true.also { stepFont(-1) }
            else -> false
        }
    }

    // Swallow the matching key-up too, so the system doesn't show its volume panel.
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean =
        volumeKeysFontSize() && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
    // A long press opens the snippet picker (text selection moves to a button in it).
    override fun onLongPress(event: MotionEvent): Boolean {
        onLongPressed(event)
        return true
    }

    // The sticky modifiers are consumed by the next key, so reading one also releases it.
    override fun readControlKey(): Boolean = state.ctrl.also { if (it) state.ctrl = false }
    override fun readAltKey(): Boolean = state.alt.also { if (it) state.alt = false }
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {
        state.view?.setTerminalCursorBlinkerState(true, true)
    }

    override fun logError(tag: String?, message: String?) = NoOpSessionClient.logError(tag, message)
    override fun logWarn(tag: String?, message: String?) = NoOpSessionClient.logWarn(tag, message)
    override fun logInfo(tag: String?, message: String?) = NoOpSessionClient.logInfo(tag, message)
    override fun logDebug(tag: String?, message: String?) = NoOpSessionClient.logDebug(tag, message)
    override fun logVerbose(tag: String?, message: String?) = NoOpSessionClient.logVerbose(tag, message)
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
        NoOpSessionClient.logStackTraceWithMessage(tag, message, e)
    override fun logStackTrace(tag: String?, e: Exception?) = NoOpSessionClient.logStackTrace(tag, e)
}
