# Contributing to Iskra for Android

Thank you for wanting to help. Two things come before any code: the licence you
contribute under, and how you say yes to it.

## The licence, and the one thing it adds

Iskra is GPL-3.0 **with an additional permission** that lets it be published through
app stores — [`LICENSE-EXCEPTION.md`](LICENSE-EXCEPTION.md). It is the same licence as the
Apple version in [`iskra-app/iskra`](https://github.com/iskra-app/iskra), and a contribution is
accepted only under **the GPL-3.0 with that permission included**.

## How you agree: sign off every commit

```sh
git commit -s -m "…"
```

The `Signed-off-by:` line means:

> I wrote this change, or otherwise have the right to submit it, and I agree that it is
> distributed under the GNU GPL version 3 together with the additional permission in
> `LICENSE-EXCEPTION.md` — including through app stores.

That is the [Developer Certificate of Origin](https://developercertificate.org) plus one
sentence about the stores. A pull request with commits that are not signed off is not merged.

## Before you open a pull request

- **Read [`CLAUDE.md`](CLAUDE.md)** — the working guide: how the modules depend on each other and
  the invariants that fail quietly and far from their cause. The full brief is
  [`docs/PROMPT.md`](docs/PROMPT.md).
- **`./gradlew check` passes**, and anything that touches a screen has been looked at on a phone.
- **No user-facing string in a composable.** Shared words are edited in the iOS repository in all
  seven languages and brought here with `scripts/sync-from-ios.sh`; words only Android needs go
  into `values*/strings_android.xml`, in all seven. Write the Russian first.
- **No code from Element X** ([iOS](https://github.com/element-hq/element-x-ios),
  [Android](https://github.com/element-hq/element-x-android)) — AGPL-3.0, Element's copyright.
  Reading it is fine; copying is not.
- **No cryptography of your own.** Matrix cryptography is the Rust SDK's.
- **Nothing in a log** that is a message, a token, a password or an OAuth callback URL.
- **No secrets in a commit**, ever: the whole history will be public.

## Reporting a vulnerability

See [`SECURITY.md`](SECURITY.md).
