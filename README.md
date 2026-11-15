# The Sevens

The apps of [$7&D, Seven and Done](https://sevenanddone.drei.gg): simple apps, paid for once.

Each app lives in `apps/<name>/` and starts with a `SPEC.md`, which describes what the app does in plain words. The iOS app is built from it first; an Android port, if the app earns one, is built from the same spec.

| App | Status |
| --- | --- |
| [Vault](apps/vault/SPEC.md) | development build ([changelog](apps/vault/CHANGELOG.md)) |
| [7&D Document Scanner](apps/scanner/SPEC.md) | building ([changelog](apps/scanner/CHANGELOG.md)) |

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

## Shared backup infrastructure

`SevenFileExportPicker` in SevenKit is used by Scanner and Vault for manual Files-provider exports. `SevenBackupFolderPicker` grants persistent access to a chosen folder. The separate Apple-only `SevenBackup` product provides a durable queue for prepared artifacts, coordinated file copying, byte verification, retry/backoff, receipts and three-version retention. It owns no encryption keys, app schema or cloud account. See [the shared backup contract](SevenKit/BACKUP.md). Foundation-only SevenKitCore remains portable.

## Build Document Scanner

```sh
scripts/test-linux apps/scanner/Kit
SEVENS_APP=scanner scripts/vm build
SEVENS_APP=scanner scripts/vm test
SEVENS_APP=scanner scripts/vm ui
```

Open `apps/scanner/Scanner.xcodeproj` on a Mac. Debug-only `-scanner.ui -scanner.reset` use an isolated library and a local unlocked purchase source; `-scanner.seed` adds two synthetic receipt pages, and `-scanner.audit` exposes the processing audit. None of them is production purchase evidence.
