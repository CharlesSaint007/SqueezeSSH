# SqueezeSSH

An open-source Android SSH client written in Kotlin, built as a replacement for the no-longer-maintained JuiceSSH.

> **Status:** early development. Milestone 1 (project scaffold, CI/CD, theming) is in place. See the roadmap below.

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
2. Terminal emulator + local shell + theme switching
3. SSH core: hosts, identities, key generation/import, known hosts, jump hosts
4. Background sessions and notifications
5. Port forwarding + snippets
6. Bitwarden / Vaultwarden sync
7. Export / import
8. Polish, then the first `v0.1.0` release

## Installing with Obtainium

Every build is published to [GitHub Releases](../../releases). Add the repo URL `https://github.com/CharlesSaint007/SqueezeSSH` to Obtainium once for each channel:

| Channel | App ID | Obtainium settings |
|---|---|---|
| Stable | `me.nickreed.squeezessh` | Include prereleases **off**. APK filter: `^squeezessh-\d.*\.apk$` |
| Dev | `me.nickreed.squeezessh.dev` | Include prereleases **on**. APK filter: `^squeezessh-dev-.*\.apk$` |

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
