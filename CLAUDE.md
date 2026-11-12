# SqueezeSSH — notes for Claude

An Android SSH client (JuiceSSH replacement), Kotlin + Jetpack Compose. Public repo
`github.com/CharlesSaint007/SqueezeSSH`. The README is the user-facing doc; this file is what you need to work on it.

## Non-negotiables
- **Git identity is repo-local:** `Nick` / `20345247+CharlesSaint007@users.noreply.github.com`. Never a global or
  work identity. Check `git config user.email` before committing, and `git log --format='%an <%ae>'` before pushing.
- **App ID is `me.nick.squeezessh`** (dev flavor `.dev`). Not `me.nickreed…`; the owner rejected that.
- **Never commit** keystores, `local.properties`, secrets, or test credentials. Passwords and secrets are typed by
  the owner in their own terminal, never through `!` commands or the chat.
- Bind any test server to `127.0.0.1` only. Don't pull private keys or secret files off a device.
- Co-Authored-By line on commits and the "Generated with Claude Code" line on PRs, as the harness instructs.

## Layout
- `app/` — the app. Package `me.nick.squeezessh`: `ssh/` (MINA SSHD connect, shell backend, port forwards),
  `session/` (SessionManager + foreground service), `terminal/` (Compose screen, key bar, panels), `data/`
  (Room: hosts, identities, keys, forwards, snippets, known hosts; `Vault` is the single entry point),
  `crypto/` (Tink-sealed `SecretStore`, key parsing), `bitwarden/` (own client + `SyncPlanner`), `backup/`,
  `settings/` (DataStore), `ui/` (screens, themes).
- **License: GPL-3.0-only** (changed from Apache-2.0 after v0.3.0; the owner is the sole author). The vendored modules below stay
  Apache-2.0 with their own license file; keep that. New dependencies must be GPLv3-compatible (Apache/MIT/BSD are fine).
- `terminal-emulator/`, `terminal-view/` — **vendored Termux** (Apache-2.0) at v0.118.3, modified. Every change is
  listed in `terminal-emulator/NOTICE.md`; add to it when you touch these modules.
- `docs/icon/squeezessh-icon.svg` — source art for the launcher icon (`ic_launcher_*.xml` are its Android vectors).

## Build & test
Run Gradle with Android Studio's bundled JDK. If the network intercepts TLS (a corporate proxy), also point the JVM at a
truststore that includes its certificate, or downloads fail with PKIX errors; keep that truststore outside the repo.
```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon -q :app:testDevDebugUnitTest :app:lintDevDebug :app:assembleDevDebug
# behind a TLS-intercepting proxy, add: -Dorg.gradle.jvmargs="-Xmx4g -Djavax.net.ssl.trustStore=<path> -Djavax.net.ssl.trustStorePassword=<pw>"
# and export the same -D flags in GRADLE_OPTS
```
Lint's known warnings: `OldTargetApi` and `TrustAllX509TrustManager` (inside BouncyCastle). Anything new is yours.
Emulator: AVD `Medium_Phone_API_37.0` (`~/Library/Android/sdk/emulator/emulator -avd … -no-window`). Quote `*` in zsh;
`adb shell input text` mangles some strings, and Escape closes dialogs.

## Branches, CI, releases
- `dev` is integration; `master` is stable and protected (required check `build`). Work on a branch, PR into `dev`
  (**squash**), CI must pass.
- Every push to `dev` publishes a signed **pre-release** `dev-<run>` (`squeezessh-dev-*.apk`, last 10 kept).
- Release: PR `dev` → `master` merged with a **merge commit** (not squash, so the branches stay level), then an
  annotated tag `vX.Y.Z` on master, which publishes `squeezessh-X.Y.Z.apk`; then fast-forward `dev` to `master`.
- Stable Obtainium installs need **no filter**: a stable release holds exactly one APK and dev builds are never
  "latest". The dev channel uses prereleases on + filter `^squeezessh-dev-.*\.apk$`. Don't break that.
- Signing: keystore `~/keys/squeezessh-release.jks` (alias `squeezessh`), 4 `SIGNING_*` repo secrets. Same key for dev
  and stable, so updates install over each other.
- GitHub has had 502s and SSH auth flakes; retrying push/`gh` in a loop works.

## Things that bit us (keep these true)
- MINA SSHD on Android: needs `OsUtils.setAndroid`, BouncyCastle at provider position 1, `IDLE_TIMEOUT=0`, and its
  client **rejects `forwarded-tcpip` channels by default**, so `SshConnector` sets an accept-all forwarding filter
  (without it every `-R` forward fails). Window-change requests must go through the executor, never the main thread.
- `SshShellBackend.resize` must tolerate a closed session (the view resizes after the connection ends).
- A `LaunchedEffect` keyed on a flow value must not clear that value before doing its async work; clearing restarts
  the effect and cancels it (this broke launcher shortcuts).
- Compose `Modifier` params: default `Modifier`, not `Modifier.fillMaxWidth()` (lint).
- Theme: every Material color role is derived in `ThemeDeriver`/`SqueezeTheme`; a role left unset shows Material's
  default purple. Add new roles in both.
- Room: bump the version and add an `AutoMigration`; schemas are exported to `app/schemas/`.
- Bitwarden/Vaultwarden: requests need `Bitwarden-Client-Name/Version` headers or type-5 (SSH key) items are hidden.
  Servers rate-limit API-key logins (HTTP 429), so the access token is cached. Vaultwarden create needs
  `encryptedFor`. The master password is used once and never stored.
- Backups: the whole file is always encrypted (Argon2id + AES-GCM). `BackupFile.secrets` is the **legacy** 0.1.0
  format, kept so old files import. Keys restored without a private half use a `missing-` secret ID and are pulled
  from Bitwarden, never pushed.
- Biometric protection (`security/Biometrics.kt`): once a switch is saved as on, turning it off, and editing /
  copying / deleting a protected host, connecting through it as a jump host, and importing a backup while one
  exists, all need `authorize(...)` in `MainActivity`. Protected hosts are masked via `Masking` and excluded from
  address search. The unlock prompt's `LaunchedEffect` must be keyed on whether settings have loaded (the lock is
  `true` before they do). If the phone has no screen lock, `AuthRules` lets the off-ramps through so nobody is
  trapped. Emulator: set a PIN with `adb shell locksettings set-pin`, tap the system keypad by its UI nodes (key
  events don't reach it), and remove the PIN afterwards; screenshots of the PIN screen are black.
- Key bar: `CODE`/`TEXT` keys fire on **press** (`pointerInput` + `repeatKey`, so a held key repeats); every other
  key stays a plain `clickable` so panels and pages don't act on press. Sticky CTRL/ALT stay set through the whole
  hold and are released once by `onKeyEnd`, not per keystroke.
- Host grouping and keep-screen-on are view state: `groupHosts`/`collapsedGroups` live in DataStore (device-local,
  not in backups); `SessionEntry.keepScreenOn` is per session and never saved.
- Releases: the release job fails if the stable release would carry anything but `squeezessh-<version>.apk`.

## Testing against real things
- Unit tests: `app/src/test` (crypto vectors computed independently with Node, sync planner, backup codec, forwards
  over an in-process MINA server, themes). Keep them green; add one with each fix.
- Bitwarden: a throwaway local Vaultwarden (`docker run … -p 127.0.0.1:8180:80 vaultwarden/server`), reachable from
  the emulator at `10.0.2.2:8180` or via `adb reverse`. **Debug builds only** allow cleartext HTTP to
  localhost/10.0.2.2; release builds are HTTPS-only.
- SSH: a key-only `sshd` on `127.0.0.1:2222` (`adb reverse tcp:2222 tcp:2222`); authorize the key the app generates
  by reading its public key from the UI.
- Look at screenshots of UI changes before calling them done.

## Where things stand
**v0.3.2 released** (latest). Milestones 1–8 of the original plan are done. History: 0.2.0 host copy/long-press menus and
encrypted backups; 0.3.0 biometric protection and Clear known hosts (the last Apache-2.0 release); 0.3.1 the GPL-3.0
license and SSH agent forwarding; 0.3.2 Gradle 9.9.0 / AGP 9.5.1 and a flaky backup-test fix. Ideas not built: Bitwarden login
items for host passwords, WorkManager periodic sync, more fonts, theme import/export as files.
