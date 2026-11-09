# The Sevens

The apps of [$7&D, Seven and Done](https://sevenanddone.drei.gg): simple apps, paid for once.

Each app lives in `apps/<name>/` and starts with a `SPEC.md`, which describes what the app does in plain words. The iOS app is built from it first; an Android port, if the app earns one, is built from the same spec.

| App | Status |
| --- | --- |
| [7&D QR Code Kit](apps/qr/SPEC.md) | spec |

## Rules every app follows

- Paid for once. A free 7-day trial with no card, then $7 to unlock. Nothing auto-bills, ever.
- The first screen says what the phone already does built in. If that's enough, keep your seven dollars.
- No third-party SDKs: no analytics, ads, or crash reporters. We collect nothing.
- No backend of ours unless a feature truly needs one, you agree to it, and we say exactly what's sent.
- Native: SwiftUI on iOS, Kotlin and Compose on Android. No dependencies beyond the platform.

## License

Code: GPL-3.0, see [LICENSE](LICENSE). The GPL covers the code, not the $7&D name, wordmark or icons.

Copyright © 2026 Drei Guintu.
