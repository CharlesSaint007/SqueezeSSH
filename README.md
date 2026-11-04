# SqueezeSSH

An open-source Android SSH client written in Kotlin, built as a replacement for the no-longer-maintained JuiceSSH.

> **Status:** early development. SSH connections, a local shell, themes, and background sessions work. See the roadmap below.

## Planned features

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
7. Export / import
8. Polish, then the first `v0.1.0` release

## Port forwarding and snippets

Each host can have port forwards, set up in the host's settings:

- **Local** (`-L`): a port on the phone that reaches a destination through the server.
- **Remote** (`-R`): a port on the server that reaches a destination through the phone.
- **SOCKS** (`-D`): a SOCKS5 proxy on the phone. Point an app (e.g. Firefox) at `127.0.0.1:<port>` to browse from the server.

Forwards listen on `127.0.0.1` unless you allow other devices. They start when the host connects (or from the
session with **FN → PORTS**, which also shows each forward's status and errors), and stop when it disconnects.

**Snippets** (*⋮ → Snippets*) are saved commands. In a session, **FN → SNIP** types one in. A host can also run
a *startup snippet* when it connects.

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

Every build is published to [GitHub Releases](../../releases). Add the repo URL `https://github.com/CharlesSaint007/SqueezeSSH` to Obtainium once for each channel:

| Channel | App ID | Obtainium settings |
|---|---|---|
| Stable | `me.nick.squeezessh` | Include prereleases **off**. APK filter: `^squeezessh-\d.*\.apk$` |
| Dev | `me.nick.squeezessh.dev` | Include prereleases **on**. APK filter: `^squeezessh-dev-.*\.apk$` |

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
