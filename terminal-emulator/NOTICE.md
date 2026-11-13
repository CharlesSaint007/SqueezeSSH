# Vendored code: Termux terminal emulator and view

`terminal-emulator/` and `terminal-view/` are copied from [termux/termux-app](https://github.com/termux/termux-app) at tag **v0.118.3** (commit `5b657c6adf4304e5198951ce815fe0205dcac29c`).

Termux as a whole is GPLv3, but these two libraries are a documented exception. They derive from [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator) and are released under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); see the Termux `LICENSE.md`. The rest of SqueezeSSH is GPLv3; these two modules stay Apache-2.0 (a license that can be included in a GPLv3 project), and a copy of it is kept next to the code in `LICENSE-APACHE-2.0.txt`. We use the v0.118.3 tag deliberately. Later commits on Termux `master` add Sixel/iTerm image support whose license status isn't stated, so they are not vendored here.

## SqueezeSSH modifications

- `TerminalSession`: the PTY subprocess code has moved out. A session is now driven by a `TerminalBackend`, so the same emulator and view serve both the local shell and SSH channels. It can also take a per-session `TerminalColorScheme`.
- New files: `TerminalBackend` (interface), `PtyBackend` (the original JNI PTY code, moved here), `StreamBackend` (backend over a pair of input/output streams, e.g. an SSH channel, with an `onConnectionLost` hook).
- `TerminalColors`: resets to a per-instance scheme instead of the single static `COLOR_SCHEME`.
- `TerminalColorScheme.of(...)`: builds a scheme from theme colors.
- `TerminalRenderer`: always paints the session's own background color, instead of relying on the host app to set the view's background.
- `TerminalViewClient.shouldUseNormalKeyboard()` (new) and `TerminalView.onCreateInputConnection`: when it returns true the view asks for ordinary text input (suggestions, autocorrect, multi-line) instead of the plain-key mode, and its input connection sends the keyboard's composing text to the terminal as it is typed, so letters appear before a word is committed, and keeps typed text in its editable so a keyboard can reopen a word to autocorrect it or swap in a suggestion (the old text is backspaced and the new typed). It also handles `replaceText`, which newer Gboard uses for tapped suggestions like "I'd" and the double-space period, and asks for sentence capitalization. With it false the connection behaves exactly as upstream.
- New file `ComposingDiff`: the backspaces and new characters that turn the composing text already in the terminal into its replacement.
- Build files converted to Gradle Kotlin DSL; `package` attributes removed from the manifests (AGP now uses `namespace`); upstream unit tests not vendored yet.
