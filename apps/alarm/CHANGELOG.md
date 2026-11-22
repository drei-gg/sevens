# Changelog

## 18 November 2026 — imported sounds, not released

- Per-alarm PCM WAV imports on both phones: native picker, owned validated copy, preview, replacement/removal and visible built-in fallback. Shared format/duration/size bounds, cancellation safety, draft-aware cleanup and explicit JSON backup/template audio exclusions. Android also releases failed players and falls back when imported/system audio cannot be prepared.

- Added native Android Glance M3 and iOS WidgetKit Saved schedule widgets, minimal shared projections, expiry and scheduling-attention states. Widgets open the app and never arm alarms.

## Unreleased — routine templates

- Save one reusable routine on iOS or Android, review the included mission data, and import it through Settings on either platform as a disabled copy. History and device scheduling state stay out of the file.
- Bound Apple archive reads against the actual bytes, including files replaced after their size was cached; oversized writes cannot replace the saved archive.

## 0.1.0 — Android, 16 November 2026

- Alarm & Loops on Android: Alarms, Loops, History and Settings as on the iPhone, with every mission, naps, groups, wake-up checks, snooze, backups and exports. Alarms are the phone's own alarm clocks, ring over the lock screen with Stop and Open, and come back after a restart or a time-zone change.

## 0.1.0 — one core for both phones, 15 November 2026

- Alarm & Loops' rules, schedules, missions and backup format now come from one shared core, so the iPhone and Android apps behave the same. Existing records and backups open unchanged.

## 0.1.0 — polish pass, 13 November 2026

- Alarms and Loops lead with the next one: its time big in a 24-hour dial (midnight at the top, the arc runs from now to when it rings), its name, the day and how long until it rings. At accessibility text sizes the dial steps aside and the words stack.
- One large Add alarm / Add loop button under the dial; quick naps are one-tap chips (15, 20, 30, 60 min) with Custom nap beneath.
- Each routine is a card with its time large, AM/PM small, the name and the week as initials (ringing days bold), and its switch. Routines with several times list each time with its own days. Tap a routine to edit it; Duplicate, Log done and Delete are in the context menu and swipe actions. The mission an alarm uses is a chip that opens a preview.
- The editor starts with the time as a wheel (one-time routines and single-time alarms) and round day buttons; name, sound, mission and snooze follow.
- Custom nap uses hour and minute wheels with the time it will ring shown big, and one Start nap button.
- History opens with two tiles and a seven-day bar chart of recorded actions, then records grouped by day with a symbol for each kind. Only recorded actions count.
- Missions: the sum is display size, one filled button per step, snooze as a quieter capsule; tiles, Color clash and movement progress use SevenKit colours and type.
- Adopted SevenKit's K17 look (ink, paper, text roles, spacing) across the app; check-rules now enforces it for Alarm. Switches keep system green: SevenKit's root ink tint reached them, so Alarm hands them back their system color at the root.
- Plural-safe minute text, curly apostrophes, no em dashes in UI text. Search appears once there are 8 routines.
- Demo data now has three alarms (one off and in a group, one with a reason) and a loop, and a week of records, and the screenshot tour reaches every screen, including each mission and two accessibility-size captures.

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
