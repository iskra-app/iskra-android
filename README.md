# Iskra for Android

A native [Matrix](https://matrix.org) messenger for Android — the same Iskra as on iPhone, iPad and
Mac ([`iskra-app/iskra`](https://github.com/iskra-app/iskra)): the same features, the same words in
seven languages, the same faces, the same bridge over nostr and the same privacy decisions.

Kotlin and Jetpack Compose on top; all of Matrix and all of its cryptography is
[`matrix-rust-sdk`](https://github.com/matrix-org/matrix-rust-sdk) through its official Kotlin
bindings. Nothing cryptographic is written here.

**Status: before the first release.** Sign-in, chats, messages, media, encryption recovery and push
notifications are in; see [`ROADMAP.md`](ROADMAP.md) for what is done and what is left.

## Installing

Iskra is published only as APKs on this repository's
[GitHub Releases](https://github.com/iskra-app/iskra-android/releases). Pick one file:

- **`gms`** if your phone has Google services — notifications arrive through Firebase Cloud Messaging;
- **`foss`** if it does not — notifications arrive through [UnifiedPush](https://unifiedpush.org), with
  a distributor app such as [ntfy](https://ntfy.sh); this build contains no Google libraries at all;
- **`arm64-v8a`** for almost every phone, **`x86_64`** for emulators and ChromeOS.

Android asks once to allow installing apps from your browser. After that, Iskra checks for new
versions itself (once a day, only a request for `release.json` to GitHub — it can be turned off in
*Settings → About*) and installs them after verifying the download and its signature. If you prefer
to manage updates yourself, [Obtainium](https://github.com/ImranR98/Obtainium) can follow this
repository's releases.

Every release lists the SHA-256 fingerprint of the signing certificate. To check a downloaded file:
`apksigner verify --print-certs iskra-….apk`. A file signed with any other key is not Iskra.

## Building

JDK 21 and the Android SDK (platform 37). From a single clone, with no access to any other
repository and no Firebase configuration:

```sh
./gradlew assembleFossRelease   # unsigned
./gradlew check                 # tests and lint
```

Two flavours: **`gms`** delivers notifications through Firebase Cloud Messaging; **`foss`** uses
[UnifiedPush](https://unifiedpush.org) and contains no Google libraries at all. `gms` gets push
only when `app/src/gms/google-services.json` is present. How releases are cut:
[`docs/RELEASING.md`](docs/RELEASING.md).

## Licence

GPL-3.0 with an additional permission for app stores — [`LICENSE`](LICENSE),
[`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md). The same licence as the Apple version. Generated
avatars are a port of [blobatar](https://blobatar.dev) (MIT,
[`Licenses/blobatar.txt`](Licenses/blobatar.txt)); the bridge protocol in `third_party/` is MIT.
