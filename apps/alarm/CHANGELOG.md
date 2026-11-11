# Changelog

## 0.1.0 — development

- Added Alarm & Loops: weekly and one-off alarms, interval windows, shared time rows with per-day inclusion, and seven-day previews.
- Added arithmetic, phrase and QR/barcode missions, in-app snooze and optional wake-up checks through AlarmKit.
- Added local history, calendar filtering, editable records and JSON/CSV export with dormant backup import.
- Integrated SevenKit trial/unlock; enabled alarms survive trial expiry.
- Added portable scheduling/storage tests, Apple model tests and simulator smoke/screenshot tests.
- Physical-device alarm, camera, purchase and background-action acceptance remains required before release.

## 0.1.0 — challenge and review-driven additions

- Added memory tiles, shake, walking, number order and Color clash with selectable difficulty.
- Added explicit motion permissions/lifecycle cleanup and backwards-compatible difficulty defaults.
- Added groups, quick naps, disabled duplicate copies, search/upcoming ordering and prominent wake-up reasons.
- Added three original local alarm tones with preview and cancellation of pending wake-up checks.
- Fixed missing app-model environment in root mission sheets, reproduced by challenge UI tests.

## 0.1.0 — everyday convenience pass

- Added custom naps from 1 minute to 12 hours, with end-time preview.
- Added reusable tone/snooze/wake-up-check defaults for new alarms and naps.
- Added weekday, weekend and every-day presets; next-alarm countdowns and system time formatting.
- Added an explicit local diagnostics export that omits personal routine data and raw errors.
- Added backward-compatibility, defaults persistence, nap-boundary and diagnostic privacy coverage, plus simulator flows for custom naps and weekday presets.

## 0.1.0 — audit fixes

- A one-off alarm or nap turns off once it has rung, and its record is kept for an hour so Open and Stop still find it.
- Snoozes and wake-up checks belong to the ringing that started them, so different days never share an alarm ID.
- Clearing wake-up checks and emergency stop skip alarms that have already rung instead of failing partway.
- Editing an alarm replaces its system registration if the system refuses the same ID.
