# Cycle & Health

## 0.1.0

- Private period, symptom, temperature, test, and sex/contact journals.
- Flexible pill packs, intervals and birth-control reminders with explicit dose history.
- Pregnancy records, appointments, cycle estimates and symptom patterns.
- Selected reports, PDF/CSV exports, encrypted backups and reviewed imports.
- Optional Apple Health flow transfer, Watch companion and discreet widgets.
- One-time family-shareable unlock; saved records remain readable, correctable and exportable.

### Journal and presentation pass

- Clearer Today context, observed-week strip, monthly calendar/agenda, configurable pinned observations, optional contact shortcuts and pack/refill progress in native paper/ink styling.
- Version-2 archives add multiple timed observations per day without changing legacy daily summaries or inventing timestamps.
- Pregnancy, postpartum/lactation intervals, dated hormonal plans and manual exclusions inform estimate eligibility while preserving history.
- Android adds complete observation/contact/dose/refill/pregnancy/appointment editors, charts, report preview and paginated PDF export.
- Android reminders now deliver through AlarmManager, with permission-aware precise/approximate scheduling, recovery after boot/clock changes, replay deduplication and stale-alert removal.
- Both platforms retain encrypted storage, portable backups, reviewed imports and access to saved records after trial expiry. iOS purchase sharing does not share journals; Android unlock remains per account.
- At this stage Android Health Connect, app authentication lock and wearable integrations remained open; the November 18 entry records subsequent changes. Physical-device and store acceptance are still required before release.

## 2026-11-17 — Android controls and widgets

- Material 3 Expressive date/time fields, clock/keyboard time entry, exposed dropdowns, connected choices, labelled switch rows and compact navigation replace generic button-shaped controls. Dose actions show the saved Taken/Skipped state; snoozing is a separate action.
- Opt-in Android home-screen widget with compact/wide layouts, launcher preview and Add widget action. Discreet mode hides the cycle day; notes and contact records never appear. Changes refresh existing widgets without waiting for a new process.

## 2026-11-18 — Device protection and reviewed Health flow

- Android adds optional device authentication, background locking and protected screenshots/recents; iOS cancels authentication when backgrounded.
- Android adds manual Health Connect flow review/import and explicit sharing with separate permissions. Existing dates and local edits are kept, including changes made during review.
- Apple Health now includes timestamped flow observations. Both platforms use the shared flow-only transfer boundary and stable export IDs.
- Wear integration on Android and physical biometric/paired-device acceptance remain open.

### Watch companions — 18 November 2026

- Added the Wear OS companion with native Wear Material 3 controls, round-screen scrolling, optional dynamic color, minimal opt-in snapshots and scheduled Taken/Skip actions.
- Added phone Data Layer transport in personal/store editions. Encrypted phone commits publish only after persistence and refresh the visible journal, notifications and widget.
- Shared snapshot redaction and permitted watch commands in Rust; hardened both watch clients against stale/future data, unmatched acknowledgements and accidental queue clearing. Android keeps one durable pending request and retries its original receipt.

## 2026-11-22 — Privacy fixes and platform parity

- Discreet mode now hides the estimated-period reminder's text as well as its title. iOS writes nothing to the shared widget store unless widgets are on, and turning them off clears the last shared values.
- Doses on later days can no longer be marked Taken or Skipped on either platform; the core's future-date messages now name the record being saved.
- Temperatures are shown and entered in °C or °F according to the regional preference and stored in Celsius; the core converts and validates both units.
- Today shows pack progress for active pill packs on both platforms. Import previews count every record type.
- Android: repeated backup passphrase checked before the file picker opens, partial export files removed on failure, report text kept out of saved state, report sharing as text or PDF alongside saving, usual cycle and period settings, Guides and sources, recording past scheduled doses from Calendar, search limited to visible fields, named editors and worded libido/pain levels.
- Android exports survive the app lock: the export is prepared before the system picker opens and the picker's answer is handled at Activity level, so the file is written even if the journal locked meanwhile. Cancelling leaves nothing; a write interrupted by process death is removed on the next start. Editor drafts are held in memory only and close on lock and erase.
- Android imports work the same way: a backup picked while the journal locks is held with read access and reviewed after unlock; its access is released on the next start if the journal is never unlocked.

### Close starts, report units and localization groundwork — 22 November 2026

- A new bleed fewer than 10 days after the previous period's start, whether marked as a start or flow after a gap, now counts as part of that period instead of a new cycle, so it no longer blanks estimates. The record is kept as entered; Calendar says "Counted as part of the period that started …" on that day and reports list it under its period.
- Text and PDF reports print temperatures in °C or °F, as the app shows them. CSV keeps Celsius and names it in a `temperature_c` column.
- Android and Wear OS words now live in `strings.xml`, with plurals and locale-aware dates, times, weekdays and lists, ready for community translations. iOS gains a String Catalog for new strings.
- Android: Android 11 or newer; strong biometrics or the device credential unlock the journal. The generic build is fully unlocked.
