# The Sevens

The apps of [$7&D, Seven and Done](https://sevenanddone.drei.gg): simple apps, paid for once.

Each app lives in `apps/<name>/` and starts with a `SPEC.md`, which describes what the app does in plain words. The iOS app is built from it first; an Android port, if the app earns one, is built from the same spec.

| App | Status |
| --- | --- |
| [7&D Fast Tracker](apps/fast/SPEC.md) | building ([changelog](apps/fast/CHANGELOG.md)) |
| [7&D QR Code Kit](apps/qr/SPEC.md) | building ([changelog](apps/qr/CHANGELOG.md)) |
| [7&D Alarm](apps/alarm/SPEC.md) | building ([changelog](apps/alarm/CHANGELOG.md)) |
| [7&D Document Scanner](apps/scanner/SPEC.md) | building ([changelog](apps/scanner/CHANGELOG.md)) |
| [7&D Photo Cleaner](apps/cleaner/SPEC.md) | building ([changelog](apps/cleaner/CHANGELOG.md)) |
| [7&D Vault](apps/vault/SPEC.md) | development build ([changelog](apps/vault/CHANGELOG.md)) |

## Rules every app follows

- Paid for once. A free 7-day trial with no card, then $7 to unlock. Nothing auto-bills, ever.
- The first screen says what the phone already does built in. If that's enough, keep your seven dollars.
- No developer analytics, ads, or crash reporters. Platform purchase integrations (StoreKit / Google Play Billing) are the narrow exception; see [the platform permission](LICENSE-PLATFORM-EXCEPTION.md) and [dependency/network notices](THIRD-PARTY.md). Tracking records are not sent to billing.
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

## Shared backup infrastructure

`SevenFileExportPicker` in SevenKit is used by Scanner and Vault for manual Files-provider exports. `SevenBackupFolderPicker` grants persistent access to a chosen folder. The separate Apple-only `SevenBackup` product provides a durable queue for prepared artifacts, coordinated file copying, byte verification, retry/backoff, receipts and three-version retention. It owns no encryption keys, app schema or cloud account. See [the shared backup contract](SevenKit/BACKUP.md). Foundation-only SevenKitCore remains portable.

## Build Fast Tracker

```sh
scripts/test-linux apps/fast/Kit
SEVENS_APP=fast scripts/vm build
SEVENS_APP=fast scripts/vm test-kit
SEVENS_APP=fast scripts/vm ui
```

Open `apps/fast/Fast.xcodeproj` on a Mac. Public builds are unbranded; the scheme includes a local StoreKit configuration for purchase testing. Debug-only `-fast.demo` shows sample history in memory; `-fast.ui -fast.reset` uses a separate test file. Neither flag changes production records.

Fast Tracker uses the shared adaptive components in Today and History. Its phone is authoritative; its Watch caches only timer and water metadata.

Fast embeds `FastWatch` (watchOS 10+). Not yet tested: the Watch app has not run on a Watch or a Watch simulator. Xcode needs the watchOS platform installed even when building the containing iPhone app; install it through Components or `xcodebuild -downloadPlatform watchOS`. A generic watchOS build needs no booted Watch simulator. Records are protected until the phone's first unlock after restart, so the Watch can save while the phone is locked. Physical pairing, background launches, disconnect/retry and protected-data behavior still require device acceptance. The shared VM's one-simulator rule remains in force.

## Build Fast on Android

The [Android baseline](android/README.md) defines Kotlin/Compose architecture, dependency selection, generic/personal/store flavors, private brand overlays, optional personal unlock, release signing and publishing boundaries. Start with `scripts/android fast generic debug` using JDK 21 and Android SDK 36. The Kotlin app lives in `apps/fast/android`; the pure FastCore port and shared app kit are under `android/`.
## Build Alarm & Loops

```sh
scripts/test-linux apps/alarm/Kit
SEVENS_APP=alarm scripts/vm build
SEVENS_APP=alarm scripts/vm test
SEVENS_APP=alarm scripts/vm ui
```

Open `apps/alarm/Alarm.xcodeproj` on a Mac with the iOS 26.1+ SDK. Debug-only `-alarm.demo` uses in-memory fixtures; `-alarm.ui -alarm.reset` uses a separate archive. Both disable actual alarm scheduling and use a development purchase fixture. Physical-phone delivery acceptance is separate.

## Build Document Scanner

```sh
scripts/test-linux apps/scanner/Kit
SEVENS_APP=scanner scripts/vm build
SEVENS_APP=scanner scripts/vm test
SEVENS_APP=scanner scripts/vm ui
```

Open `apps/scanner/Scanner.xcodeproj` on a Mac. Debug-only `-scanner.ui -scanner.reset` use an isolated library and a local unlocked purchase source; `-scanner.seed` adds two synthetic receipt pages, and `-scanner.audit` exposes the processing audit. None of them is production purchase evidence.

## Build Cleaner

```sh
scripts/test-linux apps/cleaner/Kit
SEVENS_APP=cleaner scripts/vm build
SEVENS_APP=cleaner scripts/vm test
SEVENS_APP=cleaner scripts/vm ui
```

Cleaner uses the single shared simulator and reuses `~/src/sevens-personal-cleaner` and `~/src/dd-personal-cleaner` on Tahoe. Debug-only `-cleaner.ui -cleaner.reset -cleaner.seed` uses isolated review state, an unlocked local purchase source and synthetic inventory. It does not modify real Photos or Contacts merely by launching.
