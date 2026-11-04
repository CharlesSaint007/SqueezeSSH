package me.nick.squeezessh.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import me.nick.squeezessh.session.NoOpSessionClient
import me.nick.squeezessh.session.SessionEntry
import me.nick.squeezessh.session.SessionManager
import me.nick.squeezessh.settings.Settings

/** Holds the view and the sticky-modifier state so the view clients and the extra-keys row share them. */
private class TerminalState {
    var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var title by mutableStateOf<String?>(null)

    fun releaseModifiers() {
        ctrl = false
        alt = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    entry: SessionEntry,
    sessionManager: SessionManager,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = remember(entry.id) { TerminalState() }
    val density = context.resources.displayMetrics.scaledDensity

    // Attach this screen to the session's events while it is shown; sessions keep running when it is not.
    DisposableEffect(entry.id) {
        sessionManager.client.delegate = screenSessionClient(context, state, onFinished = { /* stay on screen */ })
        onDispose { sessionManager.client.delegate = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title ?: entry.label, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { pasteClipboard(context, entry.session) }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Paste")
                    }
                    IconButton(onClick = { state.view?.let { showKeyboard(it) } }) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Show keyboard")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        state.view = this
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // setTextSize creates the renderer; setTypeface needs it to exist.
                        setTextSize((fontSizeSp * density).toInt())
                        setTypeface(loadTerminalTypeface(ctx))
                        setTerminalViewClient(
                            ScreenViewClient(
                                state = state,
                                currentFontSp = { fontSizeSp },
                                onFontSizeChange = onFontSizeChange,
                            ),
                        )
                        attachSession(entry.session)
                        // Focus can only be taken once the view is in the window; until then Compose keeps
                        // it on the top bar, and key presses (Enter included) go to the back button.
                        post { requestFocus() }
                    }
                },
                update = { view ->
                    view.setTextSize((fontSizeSp * density).toInt())
                    view.attachSession(entry.session)
                    view.invalidate()
                },
                onRelease = { state.view = null },
            )
            ExtraKeysRow(
                keys = DefaultExtraKeys,
                ctrlActive = state.ctrl,
                altActive = state.alt,
                onKey = { key ->
                    onExtraKey(key, state, entry.session)
                    // Tapping a Compose button moves focus off the terminal; hand it back.
                    state.view?.requestFocus()
                },
            )
        }
    }
}

private fun onExtraKey(key: ExtraKey, state: TerminalState, session: TerminalSession) {
    when (key) {
        is ExtraKey.Modifier -> when (key.modifier) {
            StickyModifier.Ctrl -> state.ctrl = !state.ctrl
            StickyModifier.Alt -> state.alt = !state.alt
        }
        is ExtraKey.Code -> {
            var mod = 0
            if (state.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
            if (state.alt) mod = mod or KeyHandler.KEYMOD_ALT
            state.view?.handleKeyCode(key.keyCode, mod)
            state.releaseModifiers()
        }
        is ExtraKey.Text -> {
            val view = state.view
            if (view != null) {
                key.text.codePoints().forEach { cp -> view.inputCodePoint(cp, state.ctrl, state.alt) }
            } else {
                session.write(key.text)
            }
            state.releaseModifiers()
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

        override fun onTitleChanged(changedSession: TerminalSession) {
            state.title = changedSession.title
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
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        // TerminalView passes the cumulative gesture scale; step the font one sp per ~10% of pinch,
        // and return 1 to restart the accumulation after each step.
        if (scale < 0.9f || scale > 1.1f) {
            val step = if (scale > 1f) 1 else -1
            val next = (currentFontSp() + step).coerceIn(Settings.MIN_FONT_SIZE_SP, Settings.MAX_FONT_SIZE_SP)
            if (next != currentFontSp()) onFontSizeChange(next)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        state.view?.let { showKeyboard(it) }
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

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
