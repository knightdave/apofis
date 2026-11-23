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

APKs are published on the [Releases](https://github.com/knightdave/apofis/releases) page. Every push to `main` produces a pre-release (`0.1.0-pre.N`, tag `pre-N`); normal releases are signed tags `v*`.

Once 0.1.0 is published, and before checking for the update, open the app's settings in Obtainium, turn off "Include prereleases" and turn on "Verify the 'latest' tag", so Obtainium follows normal releases only. A phone that runs a pre-release with a higher versionCode than 0.1.0 cannot install 0.1.0 over it; it gets the next normal release instead.

## Verify a release

Every APK, pre-releases included, is signed with one key. Its certificate's SHA-256 digest is recorded in [release/signing-cert.sha256](release/signing-cert.sha256):

```
b13d0d93e9f095a9c74a42647d03df2b25c6a703e86981aeb90a7f864c2c0c73
```

Check a downloaded APK with the Android SDK's build tools:

```sh
apksigner verify --print-certs apofis-0.1.0.apk
```

It must show exactly one signer with that `certificate SHA-256 digest`. Release tags are annotated and signed with an SSH key whose public half is in [release/allowed-signers](release/allowed-signers) (fingerprint `SHA256:PtPAxwgKEbi47CtvfmAp8yRCRrzf+3lyQBtfrIWH7NM`). In a clone:

```sh
git -c gpg.ssh.allowedSignersFile=release/allowed-signers tag -v v0.1.0
```

It must print `Good "git" signature` with that fingerprint.

## How releases are made

A normal release is made only from a commit of `main` that passed CI and the emulator scenario set: `scripts/tag-release.sh` creates and pushes the signed tag after checking the commit, the CI run and the signature. For a tag, CI refuses to build unless the tag is a signed `vX.Y.Z` on a commit of `main`, and it refuses to publish an APK whose signing certificate is not the recorded one. The procedure, the checks and the rollback rules are in [RELEASING.md](RELEASING.md).

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
