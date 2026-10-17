# Iskra for Android

A native [Matrix](https://matrix.org) messenger for Android — the same Iskra as on iPhone, iPad and
Mac ([`iskra-app/iskra`](https://github.com/iskra-app/iskra)): the same features, the same words in
seven languages, the same faces, the same bridge over nostr and the same privacy decisions.

Kotlin and Jetpack Compose on top; all of Matrix and all of its cryptography is
[`matrix-rust-sdk`](https://github.com/matrix-org/matrix-rust-sdk) through its official Kotlin
bindings. Nothing cryptographic is written here.

**Status: early.** The project skeleton builds and runs; signing in is next. See
[`ROADMAP.md`](ROADMAP.md).

## Building

JDK 21 and the Android SDK (platform 37). From a single clone, with no access to any other
repository and no Firebase configuration:

```sh
./gradlew assembleFossRelease   # unsigned
./gradlew check                 # tests and lint
```

Two flavours: **`gms`** delivers notifications through Firebase Cloud Messaging; **`foss`** uses
[UnifiedPush](https://unifiedpush.org) and contains no Google libraries at all. Releases will be
published as APKs on this repository's GitHub Releases, one per flavour and CPU architecture.

## Licence

GPL-3.0 with an additional permission for app stores — [`LICENSE`](LICENSE),
[`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md). The same licence as the Apple version. Generated
avatars are a port of [blobatar](https://blobatar.dev) (MIT,
[`Licenses/blobatar.txt`](Licenses/blobatar.txt)); the bridge protocol in `third_party/` is MIT.
