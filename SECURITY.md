# Security

## Reporting a vulnerability

Please do not open a public issue. Use **Security → Report a vulnerability** on GitHub, which
reaches the maintainer privately.

## What is in scope

- **Storage.** Tokens and the store passphrase are encrypted with a non-exportable Android Keystore
  key; the Matrix store is SQLite encrypted by the SDK. Backup and device-to-device transfer are off.
  Anything that leaves a secret readable outside the app's sandbox, or lets it reach a backup, is a
  vulnerability.
- **Notifications.** A push carries only a room ID and an event ID (`event_id_only`); the words are
  decrypted on the device. Anything that puts message content, names or avatars into a push, or into
  a notification shown while the app is locked, is a vulnerability.
- **The bridge.** With a bridge configured, every request — sign-in, sync, media, the app's own
  requests, update checks — goes through it or does not go at all. A silent direct connection is a
  vulnerability, and so is anything that lets the bridge see inside the TLS stream.
- **Updates.** An update is installed only if its size, SHA-256 and signing certificate match.
- **Logs.** Message bodies, tokens, passwords and OAuth callback URLs never reach a log.

Matrix cryptography itself (Olm, Megolm, cross-signing, attachment encryption) belongs to
[`matrix-rust-sdk`](https://github.com/matrix-org/matrix-rust-sdk); report issues in it there.
