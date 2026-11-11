# The Sevens

The apps of [$7&D, Seven and Done](https://sevenanddone.drei.gg): simple apps, paid for once.

Each app lives in `apps/<name>/` and starts with a `SPEC.md`, which describes what the app does in plain words. The iOS app is built from it first; an Android port, if the app earns one, is built from the same spec.

| App | Status |
| --- | --- |
| [7&D Alarm](apps/alarm/SPEC.md) | building ([changelog](apps/alarm/CHANGELOG.md)) |

## Rules every app follows

- Paid for once. A free 7-day trial with no card, then $7 to unlock. Nothing auto-bills, ever.
- The first screen says what the phone already does built in. If that's enough, keep your seven dollars.
- No third-party SDKs: no analytics, ads, or crash reporters. We collect nothing.
- No backend of ours unless a feature truly needs one, you agree to it, and we say exactly what's sent.
- Native: SwiftUI on iOS, Kotlin and Compose on Android.
- Built on open-source work where the problem is already solved. Every library is pinned, read before it ships, and credited in the app's notices. Nothing that collects data or phones home.

## Working documents

Plans, build reports and reviews are kept outside this repository. What is public here is each app's `SPEC.md`, its `CHANGELOG.md`, the code and the tests.

## License

Code: GPL-3.0, see [LICENSE](LICENSE). The GPL covers the code, not the $7&D name, wordmark or icons; see [TRADEMARKS.md](TRADEMARKS.md). Builds from this repo are unbranded; official builds add the brand from a private repo.

Copyright © 2026 Drei Guintu.

## Shared kit: adaptive layouts and Watch companions

SevenKit provides `SevenAdaptiveColumns` (two children; vertical at narrow widths or accessibility text sizes), `sevenAdaptiveTabs()` and `sevenReadableSheet()`. Layout follows its current proposal. Keep navigation, selection and editor state above rearranged content; use system safe areas.

Not yet tested: no full-display iPhone Duo build has run. For a Duo build, use an Apple-silicon Mac and select Xcode with iOS SDK 27.1 or later and run `scripts/check-duo` on that Mac. Older-SDK builds remain useful for regular iPhone development but do not prove Duo optimization. Verify narrow/wide transitions with an open draft, rotation, split windows, keyboards, largest text and system sidebars on the Duo runtime before release.

`SevenCompanion` is a separate iOS/watchOS product without purchase UI. It owns one WatchConnectivity session per process and transports opaque snapshots and acknowledged requests. The app owns its schema, authorization, atomic persistence, idempotency and error presentation. Do not treat successful message submission as a saved action. Family Sharing never synchronizes personal records.

## Build Alarm & Loops

```sh
scripts/test-linux apps/alarm/Kit
SEVENS_APP=alarm scripts/vm build
SEVENS_APP=alarm scripts/vm test
SEVENS_APP=alarm scripts/vm ui
```

Open `apps/alarm/Alarm.xcodeproj` on a Mac with the iOS 26.1+ SDK. Debug-only `-alarm.demo` uses in-memory fixtures; `-alarm.ui -alarm.reset` uses a separate archive. Both disable actual alarm scheduling and use a development purchase fixture. Physical-phone delivery acceptance is separate.
