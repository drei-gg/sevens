# Cycle & Health

A local-first period, wellbeing and birth-control journal. iOS 17+ with a watchOS 10+ companion. The Rust core is shared with the Kotlin/Compose Android base described below. The feature list in this section describes iOS.

## Included

- Daily flow, explicit period starts, spotting, symptom severity (unlogged and absent are distinct), custom symptoms, notes, tags, basal temperature, cervical mucus, ovulation and pregnancy tests.
- Separate sex/contact events, protection, libido, pain, orgasm and private notes.
- Monthly observation calendar, search and corrections; cycle-length and temperature charts; symptom frequency with explicit denominators and pre-period counts.
- Conservative period estimates, stopped for pregnancy, postpartum, lactation and enabled hormonal plans. Optional conception window requires at least three stable completed cycles. No prediction is contraception, confirmation of ovulation or diagnosis.
- Pill packs with configurable active/break days, daily/weekly/interval/one-time schedules, home-zone or travelling local time, start/end dates, explicit taken/skipped/snoozed actions, historical corrections, refills and remaining supply.
- Pregnancy history and due-date-based week counter; appointments with optional reminders. Postpartum and perimenopause tracking modes.
- On-device notification queue (up to 56, with displayed horizon). Reopening extends coverage. No guarantee of delivery under Focus, disabled permissions, a powered-off device or OS restrictions. No medicine-specific missed-dose advice.
- Apple Health flow transfer by explicit action and import preview; no automatic sync. Contact data, symptoms and temperatures are not transferred.
- Opt-in Watch snapshot and durable pending dose actions. An acknowledgement from the phone is required before an action is presented as saved. Stale requests fail visibly; replay is idempotent.
- Opt-in privacy-sensitive widget. Discreet mode is the default; names and cycle day stay out of external surfaces.
- App lock through device authentication. Keychain-backed AES-GCM local persistence with file protection, atomic replacement, authenticated content and exclusion from device backup. No account or journal backend.
- Passphrase-encrypted age backups; JSON and CSV export; all-or-nothing conflict-checked import with new schedules dormant. Reports exclude private notes and contacts by default, preview exactly what will be shared, and export as text or paginated PDF.
- SevenKit trial/unlock/restore. Family Sharing shares the purchase only. Existing records, corrections, deletion, exports and ongoing dose logging remain available after expiry.

## Data and reuse

`core` depends on `alarm-core` for time-zone/DST occurrence generation and stable identifiers. JSON is the versioned UniFFI boundary. Swift `BloomWire` is shared by phone and Watch; widgets use `BloomSurfaces`. One repository actor serializes writes. Command IDs, payload digests and archive revisions protect against retries and stale editors.

Imports accept this app’s schema only. No claimed Flo importer without a verified export fixture. Selected report sharing is manual; there is no live partner account or family journal. Medical assessment, expert chat, a social community and an equivalent editorial library are outside this implementation. The guides link to independent NHS sources instead.

## Build

From the repository root:

```
cargo test --manifest-path apps/bloom/core/Cargo.toml --tests
scripts/bloom-apple core
scripts/bloom-apple build
scripts/bloom-apple test
scripts/bloom-apple ui
scripts/check-rules
```

The Apple helper uses configured simulator slot 2, the per-slot lock, one stable source copy and `~/src/dd-personal-bloom`. Tests disable parallel simulator creation. It never erases or shuts down a simulator.

Before release: real-device notification/time-zone, Health, app-lock, paired-Watch and StoreKit/Family Sharing acceptance; reviewed health language, accessibility/localization and privacy disclosure; production identifiers, signing and App Store Connect family sharing. Build success is not release acceptance.

## Android base

The native Android foundation uses the same Rust archive, rules and Alarm-backed scheduling engine via UniFFI. Included: daily flow and four symptom inputs, notes, separate contact records, plan schedules, manual taken/skipped dose actions and undo, basic cycle/symptom summaries, mode/lactation controls, JSON/CSV export, passphrase-encrypted backups and reviewed merge. Editing preserves imported fields that the base UI does not yet expose.

Records use AES-GCM with an Android Keystore key, authenticated versioned content, serialized operations and AtomicFile replacement in the no-backup directory. Malformed or undecryptable files remain intact. SevenKit supplies trial/unlock, generic/personal/store distribution separation and store-only Play Billing. Unlocks on Android are per account.

This is a base, not iOS parity: notification delivery, app authentication lock, Health Connect, wearable/widgets, advanced journal fields, calendar/charts, pregnancy/appointment/refill editors and PDF/report UI remain open. The Care screen explicitly says to retain existing alarms. Importing compatible records preserves them, but does not implement these platform features.

```
# JDK 21, Android SDK/NDK and Rust Android targets required (android/README.md).
android/gradlew -p android :bloom-core:test :bloom:lintGenericDebug :bloom:assembleGenericDebug :bloom:assembleGenericRelease
scripts/android bloom personal debug -Psevens.brandDir=/path/to/private-brand
# Local unlocked fixture only:
android/gradlew -p android :bloom:connectedPersonalDebugAndroidTest -Psevens.brandDir=/path/to/private-brand -Psevens.personalUnlock=true
```

The approved private icon catalog is an external build input. For Apple, set `SEVENS_BRAND_DIR` when invoking `scripts/bloom-apple build`; phone and Watch catalogs and identity are overlaid only into the stable guest copy. Generic assets remain in public source.
