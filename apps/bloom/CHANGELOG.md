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
