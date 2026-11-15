# 7&D Alarm

Development 0.1 · 11 November 2026

Wake-up missions and time loops. Seven-day trial, then a one-time unlock through SevenKit. Public builds use the neutral name **Alarm & Loops**. Native SwiftUI, iOS 26.1+, Foundation AlarmCore, Apple-only AlarmUI. No backend, accounts, advertising, analytics or third-party dependencies.

## Alarms and time loops

One routine owns its name, enabled state, delivery, mission and history. Weekly routines have shared time rows with independently selected weekdays. The defining example is 07:00 on Monday/Friday and noon/14:00 on Monday/Wednesday/Friday, all inside one routine. Editing noon changes its selected days together; Wednesday can omit only the morning time.

One-off dates and interval windows are also supported. An overnight window belongs to its starting weekday; the endpoint is included only when reached by an interval. The editor previews the coming seven days in the current timezone. Preview uses the next valid local time for a DST gap and the first repeated local time. Native DST/travel behavior needs physical-device comparison before release.

AlarmKit supplies real system alarms, fixed dates and weekly repetition. Quiet loops use local calendar notifications, split into weekday registrations. Permission denial and schedule errors are visible. Desired schedules and native receipts are separate; receipts are stored before native installation, changes retry on foreground, and failed additions preserve old registrations. There is no background finite-queue replenishment dependency for weekly schedules. The app limits quiet reminder registrations to 60 and each interval routine to 96 unique clock times, refusing silent truncation. AlarmKit may impose its own lower capacity, surfaced as a scheduling error.

Alarms offer the system default plus three original bundled tones: Soft chime, Pulse and Bright bells. Media-volume preview is distinct from system alarm volume. Settings includes a ten-second alarm test. No claim of guaranteed wake-up, sound playback, unbypassable dismissal or force-quit prevention.

## Missions and snooze

Eight optional challenges: arithmetic (1–10 questions), exact phrase entry, matching a locally saved QR/barcode using Apple's DataScanner, memory tiles, shake, walking, number order and Color clash. The five additional challenges have three difficulty levels. Motion/step sensing starts explicitly and stops on leaving the challenge or backgrounding. Memory provides spoken tile cues for VoiceOver; Color clash exposes the ink color to accessibility and is optional. Scanned contents are compared as data and never opened as URLs. Preview missions do not schedule alarms or create completed-history records.

The system alarm's Open button launches the mission. The system Stop control remains available and records a dismissal, not a completed mission. In-app snooze schedules a fixed-date alarm with the selected delay and count limit. An optional wake-up check schedules one follow-up after completion or system dismissal; the follow-up does not recursively schedule itself. Emergency stop remains available. Mission state is persisted for reopening.

## Convenience controls

Optional groups have visible bulk switches. Quick nap presets create one-off alarms at 15, 20, 30 or 60 minutes. Duplicates start disabled; search covers name/group and lists sort by next occurrence. An optional user-written reason appears prominently in the mission. Pending wake-up checks can be cancelled without disabling the recurring routine.

Custom naps accept 1–720 minutes and preview their end time. Reusable defaults for tone, snooze duration/count and wake-up checks are saved locally and apply only to newly created alarms/naps, never existing schedules or quiet reminders. Weekday/weekend/every-day presets reduce repeated setup. Routine cards show time remaining and the next local date; clock labels follow the system's time format. Defaults are included in backup exports; merging an import preserves this device's defaults.

## History and ownership

Calendar day filter, searchable event list, manual check-ins, correction of dates/notes and deletion. Corrections are marked manual. Scheduled time passing is never interpreted as a completed activity or proof of audible delivery. Existing history survives routine deletion.

Protected, atomic local JSON with validation before publication. Invalid/future-version files are preserved and block writes. Backup export includes routines and history; import validates before mutation, retains existing routine IDs, adds new routines disabled and ignores source-device native receipts/pending missions. Conflicting event IDs reject the whole merge. CSV quotes fields and neutralizes spreadsheet formulas. No automatic cloud synchronization; OS backups may include records.

An explicit diagnostic JSON export contains app/OS versions, alarm permission, registration counts and error flags. An allowlist excludes names, routine schedules, codes, phrases, history, identifiers and raw error messages. No automatic transmission. Missing-registration counts are a troubleshooting snapshot, not evidence of audible delivery.

## Trial

SevenKit handles the explicit trial, non-consumable unlock and restore. Expiry gates creation/editing and re-enabling routines. Already enabled alarms continue. Disabling/deleting alarms, dismissal, history correction/export and dormant backup import stay available. Purchase UI never interrupts an active mission. Production products, signing and store configuration remain release work.

## Interface

Alarms, Loops, History and Settings. Native controls in the Sevens' look (SevenKit K17): ink as the accent, paper behind every screen, SevenKit's text roles and spacing scale; toggles keep system green. Alarm's own visuals (the next-alarm dial, the History chart, mission progress) use the SevenKit warm data hue for time; controls never do. Color clash draws its words in four game inks that pass 4.5:1 on paper and card. Shared adaptive tabs; weekday controls wrap at narrow widths. State lives with the editor. Public build uses placeholder icon assets; final brand is injected privately. Before-you-buy names the built-in Clock alternative. No editorial content or morning feed.

## Current boundary

Implemented local development scope: weekly/one-off/window schedules, eight missions, in-app snooze and one follow-up, history, backup/import/export and SevenKit integration. Native alarm delivery and mission actions require physical iPhone acceptance; a simulator build is not that evidence. Camera scanning is unavailable on unsupported hardware/simulator.

Skip-next, date-specific overrides, independently moved weekday times within a shared slot, user-imported audio, completion-relative schedules, multistage timer sequences, template sharing, widgets and a Watch app are not implemented. Per-slot weekday inclusion/exclusion is implemented and covers the original Time Loops example. Fasting and its journal remain in Fast Tracker. Android is not built.

## Verification

Portable tests cover the exact MWF case, shared edits, overlap deduplication, overnight rollover, DST gaps/folds, invalid bounds, mission matching, backup merge, atomic-write failure, corrupt/future files and CSV escaping. Apple model tests exercise reconciliation, retry, expiry continuity and split reminder weekdays. UI tests exercise creation/relaunch and light/dark screen tours. Actual results are recorded separately; test source alone is not a passing result.
