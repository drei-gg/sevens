# Bloom Wear companion

Native Wear Compose Material 3 companion. The paired phone owns the encrypted journal and evaluates commands through `bloom-core`; this app stores only an opted-in minimal snapshot and one pending request.

- Personal/store phone builds include the Data Layer bridge; the generic phone excludes Google Play services and the companion toggle.
- Phone and watch must have matching application IDs and signing identities. The `bloom_phone` capability locates the paired reachable phone; request and snapshot paths are defined in `:bloom-wire`.
- Snapshot order, version and age are checked before recording. Discreet snapshots hide reminder names and cycle day. Disabling the companion clears private snapshot content and pending actions on receipt.
- A request is committed to local watch storage before transmission. Retries retain its original receipt; no optimistic saved-dose status is shown. A matching error-free phone acknowledgement clears it. Clearing a pending action is separately confirmed and cannot undo a phone commit.
- No independent offline journal, Tile or complication is implemented. The existing iOS capability being matched is the companion app. Paired physical-device and background-delivery acceptance remain required.

Build from the repository root with `android/gradlew -p android :bloom-wear:assembleGenericDebug`. Test the pure wire with `:bloom-wire:test`, queue behavior with `:bloom-wear:testGenericDebugUnitTest`, and native round-screen behavior with `WatchUiTest` on a Wear emulator. Release branding follows `seven.android-app`, just like the phone. Dependencies and lockfiles are explicit; third-party notices are bundled under `assets/notices` and credited in the root `THIRD-PARTY.md`.
