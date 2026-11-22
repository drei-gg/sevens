# Cycle & Health

A local-first period, wellbeing and birth-control journal. iOS 17+ with a watchOS 10+ companion. The Rust core is shared with the native Kotlin/Compose Android app. Platform-specific integrations are identified below.

## Included

- Daily summaries and separate timestamped flow, symptom, temperature, test and period-product observations, with recorded time zones and multiple entries per day. Explicit period starts, spotting, symptom severity (unlogged and absent are distinct), custom symptoms, notes, tags, basal temperature, cervical mucus, ovulation and pregnancy tests.
- Separate sex/contact events, protection, libido, pain, orgasm and private notes.
- Paper/ink Today context with a seven-day observation strip, configurable pinned quick logs, optional contact shortcuts, pack progress and refill context. Monthly observation calendar, search and corrections; cycle-length and temperature charts; symptom frequency with explicit denominators and pre-period counts.
- Conservative period estimates, stopped for pregnancy, postpartum, lactation and enabled hormonal plans. Completed cycles overlapping pregnancy, explicit exclusions or dated hormonal plans remain visible but are omitted from estimate samples. Postpartum/lactation mode transitions create dated exclusions; users can correct intervals with missing records. Optional conception window requires at least three stable completed cycles. No prediction is contraception, confirmation of ovulation or diagnosis.
- Pill packs with configurable active/break days, daily/weekly/interval/one-time schedules, home-zone or travelling local time, start/end dates, explicit taken/skipped/snoozed actions, historical corrections, refills and remaining supply.
- Pregnancy history and due-date-based week counter; appointments with optional reminders. Postpartum and perimenopause tracking modes.
- iOS on-device notification queue (up to 56, with displayed horizon). Reopening extends coverage. No guarantee of delivery under Focus, disabled permissions, a powered-off device or OS restrictions. No medicine-specific missed-dose advice.
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

The Apple helper uses the shared simulator returned by `sim-ensure --print`, one stable source copy and `~/src/dd-personal-bloom`. Tests disable parallel simulator creation. It never erases or shuts down a simulator.

Before release: real-device notification/time-zone, Health, app-lock, paired-Watch and StoreKit/Family Sharing acceptance; reviewed health language, accessibility/localization and privacy disclosure; production identifiers, signing and App Store Connect family sharing. Build success is not release acceptance.

## Android

The native app uses the same Rust archive, calculations and Alarm-backed scheduling rules through UniFFI. Today, monthly calendar/search/agenda, configurable quick logs, timed observations, complete contact fields, pill packs, dose history/corrections, refills, pregnancy/appointment editors, estimate exclusions, cycle/symptom/temperature charts, report preview and text/PDF sharing are included. Calendar and charts reuse Kizitonwose Calendar and Vico.

Records use AES-GCM with an Android Keystore key, authenticated versioned content, serialized operations and AtomicFile replacement in the no-backup directory. Malformed or undecryptable files remain intact. SevenKit supplies trial/unlock, generic/personal/store distribution separation and store-only Play Billing. Unlocks on Android are per account; there is no Google family unlock.

Reminders use one rolling AlarmManager alarm, re-reading the current encrypted archive at delivery. Precise alarms are used when permission is available; the UI explains approximate fallback and notification permission state. Save/resume, boot, time/zone changes, app update and permission grant reconcile the schedule; WorkManager supplies durable recovery. Recovery catches due events within the last 24 hours, deduplicates delivery, and cancels stale alerts after plan/appointment/dose changes. Notifications never record a dose. Discreet content is the default. Force-stop, powered-off devices, denied permissions and OS restrictions still require user attention; reopening recovers scheduling. Android does not inherit Apple's 56-pending-notification queue limit.

Android home-screen widgets now use Jetpack Glance, with an explicit opt-in and discreet mode that hides the cycle day. Only the cycle day and next reminder time can reach the launcher; journal/contact text never does. Widgets refresh on saved changes, background recovery and a 30-minute launcher schedule. Android now has an opt-in Wear OS companion in personal/store phone editions, using Wear Compose Material 3 and the Google Play services Data Layer. The generic phone edition excludes that SDK and toggle. iOS Health and Watch remain opt-in; physical-device acceptance is still required.

Android app lock uses AndroidX BiometricPrompt with device credentials as a fallback. Enabling/disabling requires authentication; failed authentication or failed preference persistence leaves protection unchanged. It locks after backgrounding, protects screenshots/recents while enabled, preserves the gate during recreation and closes unsaved editors/import reviews. This is a device-local UI gate, separate from the Keystore-encrypted repository and from reminder/widget privacy. iOS invalidates an in-progress authentication request when backgrounded, so a late callback cannot unlock the journal.

Health transfer is manual on both platforms, with independent read/write permissions and no background sync. The Rust boundary exports light/medium/heavy flow from legacy daily entries and timestamped observations, retaining stable source IDs and event instants. Import previews aggregate flow per date and skip any date already represented by a daily record or timed observation. The repository rechecks those dates when committing, so changes made during review are kept. Only reviewed flow enters the import; no notes, contacts, symptoms, medication or temperatures cross the boundary. Android reads available records from the last 30 days; Apple Health reads up to two years. Health Connect has no period-start flag, so those markers are not transferred there. Apple Health preserves period-start and time-zone metadata. Retrying an export updates stable IDs; deletion in the journal does not remove external Health copies. Partial Android export failure reports the accepted count and supports a duplicate-safe retry.

## Archive version 2

Opening/importing a version-1 archive normalizes it to version 2 with empty `observations` and `exclusions`; existing daily records and IDs are preserved. No timestamps are invented for daily summaries. All timed events remain individually editable and exportable; derived daily flow and symptom levels use the maximum recorded value, with one symptom denominator contribution per logged day. Daily test summaries use the latest timed result; the timeline retains all tests. Temperature charts retain separate measurements. Excluded cycle lengths remain in history and reports.

New-schema records are included in encrypted backup, conflict-checked merge and exports. Future schemas are rejected without modifying the original file. Historical pregnancy edits do not change an unrelated current mode. Paused plans retain a bounded hormonal-history interval; legacy disabled plans without an end date are not retroactively assigned invented dates.

```
# JDK 21, Android SDK/NDK and Rust Android targets required (android/README.md).
android/gradlew -p android :bloom-core:test :bloom:lintGenericDebug :bloom:assembleGenericDebug :bloom:assembleGenericRelease
scripts/android bloom personal debug -Psevens.brandDir=/path/to/private-brand
# Local unlocked fixture only:
android/gradlew -p android :bloom:connectedPersonalDebugAndroidTest -Psevens.brandDir=/path/to/private-brand -Psevens.personalUnlock=true
```

The approved private icon catalog is an external build input. For Apple, set `SEVENS_BRAND_DIR` when invoking `scripts/bloom-apple build`; phone and Watch catalogs and identity are overlaid only into the stable guest copy. Generic assets remain in public source.


### Companion reliability — 18 November 2026

Both companions use the Rust core's minimal snapshot and permitted-command boundary. Disabled snapshots contain no cycle day or doses; discreet mode redacts reminder names and cycle day. Watch snapshots include the journal revision, generation time and protocol version. Stale, future and out-of-order snapshots cannot enable recording or replace a newer disabled state. Android offers scheduled Taken/Skip actions, durably queues one request, reuses its receipt on retry, and only reports a save after a matching phone acknowledgement. Native queue clearing requires confirmation and warns that an interrupted reply may already have committed on the phone. Apple Watch now carries the snapshot revision, validates refresh acknowledgements and confirms queue clearing too. The phone remains the source of truth; there is no independent offline journal. Paired-device transport and background-delivery acceptance are separate from repository, wire, model and emulator UI tests.
