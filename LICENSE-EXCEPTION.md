# Iskra for Android — copyright and licence

Copyright © 2026 Boris Zhuravlev and the Iskra contributors.

Iskra is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, **version 3**
([`LICENSE`](LICENSE)), together with the additional permission below.

Iskra is distributed in the hope that it will be useful, but **without any warranty**;
without even the implied warranty of merchantability or fitness for a particular purpose.
See the GNU General Public License for more details.

This is the same licence, with the same permission, as the iPhone, iPad and Mac version in
[`iskra-app/iskra`](https://github.com/iskra-app/iskra). The two repositories are one project
and are licensed as one.

## Additional permission under GNU GPL version 3, section 7

As an additional permission, the copyright holders of Iskra allow you to convey this
program, or a work based on it, in object code form through an application distribution
platform — such as Google Play, the App Store, the Mac App Store or TestFlight — even
though the terms of that platform, or the technical measures it applies, restrict
recipients in ways that would otherwise conflict with sections 6 and 10 of the GNU GPL.

This is permitted on the condition that the Corresponding Source of the work you convey
is also made available under the GNU GPL version 3, with or without this permission,
through a channel that imposes no such restrictions.

As section 7 allows, anyone who conveys a modified version may remove this permission
from their copy. It cannot be removed from code they did not write.

## Contributions

A contribution to Iskra is licensed under the GNU GPL version 3 **with this permission
included**. That is what lets the app you can download be built from everybody's code,
and the contributor agrees to it by signing off their commits — see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## What this file does not cover

- **The bridge protocol** in [`third_party/iskra-tunnel-proto/`](third_party/iskra-tunnel-proto) —
  a copy of the crate from [`iskra-app/iskra-bridge`](https://github.com/iskra-app/iskra-bridge),
  under the MIT licence, so that the protocol can be implemented anywhere.
- **The blobatar port** (`design/src/main/kotlin/moscow/swag/iskra/design/blobatar/`) and its golden
  fixture — a port of [blobatar](https://blobatar.dev), MIT, notice kept verbatim in
  [`Licenses/blobatar.txt`](Licenses/blobatar.txt).
- **Dependencies** keep their own licences: `matrix-rust-sdk` and its Kotlin bindings
  (Apache-2.0), AndroidX and Kotlin libraries (Apache-2.0), and the rest as listed on the
  app's "About" screen.
- **The name and the icon.** The licence covers the code, not the identity. A fork that is
  distributed to other people should go by its own name and icon, so that nobody mistakes
  it for this project.
