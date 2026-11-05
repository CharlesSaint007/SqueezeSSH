# <img src="docs/icon/squeezessh-icon.svg" alt="" width="40" height="40" align="center"> SqueezeSSH

An open-source Android SSH client written in Kotlin, built as a replacement for the no-longer-maintained JuiceSSH.

> **Status:** early development. SSH connections, a local shell, themes, and background sessions work. See the roadmap below.

## Features

- SSH connections with reusable identities (password and/or key), groups, quick connect, and jump hosts
- Port forwarding: local (`-L`), remote (`-R`), and dynamic SOCKS5 (`-D`)
- Snippets, plus a startup snippet per host
- Multiple concurrent sessions that keep running in the background, with one notification per session that takes you back to it
- Local device shell
- Terminal themes (Gruvbox Dark, Solarized Dark, Tokyo Night, Dark, Light, and custom). The app UI follows the terminal theme or uses its own.
- **Bitwarden / Vaultwarden sync** of SSH keys in both directions, stored as native Bitwarden *SSH Key* items
- Export/import of all hosts and settings, with an optional passphrase-encrypted secrets section

## Roadmap

1. ✅ Scaffold: Gradle project, `stable`/`dev` flavors, signing, CI and release workflows
2. ✅ Terminal emulator + local shell + theme switching
3. ✅ SSH core: hosts, identities, key generation/import, known hosts, jump hosts
4. ✅ Background sessions and notifications
5. ✅ Port forwarding + snippets
6. ✅ Bitwarden / Vaultwarden sync
7. ✅ Export / import
8. ✅ Polish: shortcuts, history, auto-reconnect, theme editor, then the first `v0.1.0` release

## Everyday use

- **Shortcuts:** long-press the app icon for your most recently used hosts, or pin any host to the home screen
  from its settings (the *add to home screen* icon). Either one connects straight away.
- **Biometric lock:** *Settings → Security* can require your fingerprint, face or screen lock to open the app. The
  prompt appears by itself as the app opens, and again after it's been in the background for a minute; the app is
  hidden from screenshots and the recent-apps view while that is on. A host's settings have *Require fingerprint to
  connect*. Both are off by default, and once on they protect themselves: turning either off needs your
  fingerprint, and so does editing, copying or deleting a protected host, connecting through it as a jump host,
  or importing a backup while one exists. A protected host's address is masked in the list (`pi@192...3`) and
  isn't searchable. If the phone has no screen lock, neither can be turned on.
- **Known hosts:** host-key fingerprints you've accepted are remembered, and a changed key is flagged loudly.
  *Settings → Clear known hosts* forgets them all, so each host asks to be trusted again on its next connection.
- **Host menu:** long-press a host for *Edit*, *Copy as new config* (opens a new host pre-filled with this one's
  settings and port forwards, saved only if you tap Save) and *Delete*.
- **Quick connect:** type `user@host[:port]` in the search box. The last 5 that connected are listed under
  *Recent quick connects*.
- **Terminal:** tap the terminal to show or hide the key bar. The bar's keyboard key shows or hides the keyboard,
  and **FN** switches to F1–F12, snippets (SNIP) and port forwards (PORTS). Pinch or use the volume keys to zoom.
- **Reconnect:** if a connection drops while you're looking at it, the app reconnects after a moment, or asks
  first if *Reconnect automatically* is off. A normal `exit` just closes the session.
- **Themes:** in Settings, long-press any theme to edit it, or use *New theme from the current one*. Built-in
  themes are copied first. Custom themes are included in backups.

## Port forwarding and snippets

Each host can have port forwards, set up in the host's settings:

- **Local** (`-L`): a port on the phone that reaches a destination through the server.
- **Remote** (`-R`): a port on the server that reaches a destination through the phone.
- **SOCKS** (`-D`): a SOCKS5 proxy on the phone. Point an app (e.g. Firefox) at `127.0.0.1:<port>` to browse from the server.

Forwards listen on `127.0.0.1` unless you allow other devices. They start when the host connects (or from the
session with **FN → PORTS**, which also shows each forward's status and errors), and stop when it disconnects.

**Snippets** (*⋮ → Snippets*) are saved commands. In a session, **long-press the terminal** or use **FN → SNIP**
to type one in. (Long-press used to start text selection; that's now *Select text to copy* in the same list.) A host can also run
a *startup snippet* when it connects.

## Backup and moving to a new phone

**Settings → Backup & restore** exports hosts, identities, keys (public halves and Bitwarden links), port
forwards, snippets, known hosts and settings to a `.squeezessh.json` file through the system file picker.

- The whole file is always encrypted with a passphrase you choose (Argon2id + AES-256-GCM), so even host names
  and addresses aren't readable without it.
- *Include passwords and private keys* adds those too, inside the same encryption.
- Backups made by earlier versions (unencrypted, with only the secrets encrypted) still import.
- **Import** offers *Merge* (add to what's here; items with the same ID are updated) or *Replace* (delete
  everything first).
- **New phone:** install, import the backup, then sign in to Bitwarden sync. Keys restored without their
  private half are filled in from the vault on the first sync. Until then, connecting with one says so.

## Bitwarden / Vaultwarden sync

SSH keys sync both ways with the **SSH Key** items in a Bitwarden (bitwarden.com or bitwarden.eu) or
self-hosted Vaultwarden vault. Set it up from **Keys** or **Settings → Bitwarden / Vaultwarden**, using your
personal API key (web vault: *Settings → Security → Keys → View API key*) plus your master password.

- The master password is used once to unlock the vault and is never stored. The API key and the vault's
  decryption key are kept encrypted with the device's Android Keystore.
- Keys created or imported in the app go to a `SqueezeSSH` folder in the vault. By default only that folder is
  brought into the app; turn off *Only the SqueezeSSH folder* to import every SSH key in the vault.
- Renames go both ways. If a key was changed on both sides since the last sync, the newer change wins.
- Deleting a synced key asks whether to also move the vault item to the vault's trash. If an item is deleted in
  the vault, the app keeps its copy but stops syncing it.
- Each key has a *Sync with Bitwarden* switch, for keys that should stay on this device only.
- Only personal vault items are synced; organization/collection items are skipped.

Debug builds may use plain `http://` to `10.0.2.2`, `127.0.0.1` or `localhost`, for testing against a local
Vaultwarden. Release builds require HTTPS.

## Background sessions

When you leave a running session, SqueezeSSH asks whether to **keep it running** or **disconnect**. You can make that choice permanent under Settings → Sessions. Each session kept running gets its own notification: tap it to go back to that session, or tap **Disconnect**. A summary notification shows how many are open and has **Disconnect all**.

Sessions survive the app being backgrounded or swiped from Recents. Some phones (Samsung, Xiaomi, OnePlus and others) kill background apps more aggressively. If sessions drop with the screen off:

- Settings → Apps → SqueezeSSH → Battery → **Unrestricted**
- In SqueezeSSH, Settings → Sessions → **Keep device awake** (uses more battery)

## Installing with Obtainium

Every build is published to [GitHub Releases](../../releases).

**Stable:** in Obtainium, add `https://github.com/CharlesSaint007/SqueezeSSH` and leave the defaults
(*Include prereleases* off). Stable releases contain only the stable APK and dev builds are always pre-releases,
so no APK filter is needed.

**Dev** (optional, for testing new builds): add the same URL a second time, turn *Include prereleases* on, and set
the APK filter to `^squeezessh-dev-.*\.apk$`.

| Channel | App ID | Obtainium settings |
|---|---|---|
| Stable | `me.nick.squeezessh` | Defaults: prereleases off, no filter |
| Dev | `me.nick.squeezessh.dev` | Prereleases **on**, APK filter `^squeezessh-dev-.*\.apk$` |

The two channels have different app IDs, so they install side by side.

## Branches and releases

- `dev`: integration branch. Every push builds a signed **pre-release** (`dev-<run>`), and the last 10 are kept.
- `master`: stable. Pushing a `vX.Y.Z` tag on master builds a signed **release**.
- Feature work goes on branches and merges into `dev` through a PR. To cut a release, merge `dev` into `master` and tag it.

## Building locally

Requires the Android SDK (compileSdk 37) and JDK 17+. Android Studio's bundled JDK works.

```sh
./gradlew testStableDebugUnitTest assembleDevDebug
# APK: app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

Or open the project in Android Studio and run the `devDebug` variant.

## Release signing

CI signs release builds with a keystore stored in repository secrets:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | `base64 -i squeezessh-release.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

Keep a backup of the keystore. If it's lost, existing installs can't be updated and have to be uninstalled and reinstalled.

## License

[Apache License 2.0](LICENSE)
