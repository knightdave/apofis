# Apofis

Apofis is a native Android client (Kotlin, Jetpack Compose) for the Hermes Agent API server. It lets you chat with your own Hermes agent from your phone.

Apofis is an unofficial project. It is not affiliated with, endorsed by or connected to Hermes Agent or Kelivo.

## Requirements

- An Android phone running Android 12 (API 31) or newer.
- A Hermes API server reachable from the phone (local network, VPN or public address).

## Install

Install and update with [Obtainium](https://github.com/ImranR98/Obtainium):

- Open on the phone: `obtainium://add/https://github.com/knightdave/apofis`
- Or add the repository URL in Obtainium by hand: https://github.com/knightdave/apofis

APKs are published on the [Releases](https://github.com/knightdave/apofis/releases) page. Every push to `main` produces a pre-release; tags `v*` produce normal releases.

## Build

The dev shell provides JDK 21 and the Android SDK through Nix:

```sh
nix develop -c ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Run the checks with:

```sh
nix develop -c ./gradlew testDebugUnitTest lintDebug
```

## License

MIT, see [LICENSE](LICENSE).

The icons in `app/src/main/res/drawable/lucide_*.xml` are converted from [Lucide](https://github.com/lucide-icons/lucide) and are under the ISC license, see [licenses/LUCIDE-LICENSE](licenses/LUCIDE-LICENSE).
