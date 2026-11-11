# 7&D Fast Tracker

Draft 0.1 · 11 November 2026

A private fasting tracker with a history worth keeping. Paid for once.

| Where | Name |
| --- | --- |
| App Store | 7&D Fast Tracker |
| Subtitle | Timer, history & journal |
| Under the icon | 7&D Fast |
| Public build | Fast Tracker |

## What it adds

The iPhone's Clock already times things, Notes keeps a journal, and Health records weight. Say that on the first screen. This app brings editable fasting and eating windows, a calendar, long-term patterns and a private journal together. No account, ads, social feed, remotely published articles or subscription.

Native SwiftUI, iOS 17+, Swift 6. Shared SevenKit for purchase, restore, trial and About. Pure Foundation FastCore with portable tests, Apple-only FastUI. System backgrounds, controls, type and accent; brand only in About. String catalogs, Dynamic Type, VoiceOver and Reduce Motion from the start. No third-party dependencies.

## Today

- Start now or correct the start time. Choose 12:12, 14:10, 16:8, 18:6, 20:4, a custom duration, or an open-ended record. These are timer presets, not prescriptions.
- Time elapsed, time remaining, start and expected finish. Goal reached never ends a fast automatically. The clock derives from persisted timestamps and survives app termination.
- Edit the active fast's start, goal, intention and tags. End with an editable time; preserve early finishes without calling them failures. Discard requires confirmation.
- Optional eating-window timer starts at the actual end, with its own reminder.
- One active fast; no overlapping actual records. Touching endpoints and multiple fasts on one date are allowed. Future plans are separate from actual records and never start themselves.
- Quick check-in and water logging; everything optional.

## History is the main feature

Calendar, list and heatmap are different views of the same records, never separate counters.

- Month calendar: actual fasting hours for each day, journal indicator, planned sessions and rest days. Tap a day for its timeline, records and notes. Move freely through all months.
- Heatmap: a compact thirteen-week overview; move backward to any period. Shade encodes recorded hours, not health, virtue or a score. Accessible day labels and a legend; list view is always available.
- List: newest first, search intention/reflection/tags, filter goal reached or shorter than planned. Add a forgotten fast, correct both endpoints and target, or delete with confirmation.
- Actual duration is elapsed time; daily totals split each interval at local midnight. Calendar arithmetic handles DST and month/year boundaries. A fast spanning two days appears on both; it is one completed session, counted at its end. Stored timezone identifiers preserve provenance; calendar views use the current timezone and say so.
- No locked historical years. Reading, correcting, searching, exporting and deleting existing records remain available after trial expiry. A fast already started can always be ended and saved.
- Rest days are explicit optional annotations. No broken-streak warnings, punishment or competitive fasting-length badges.

## Journal

Numina informs the concept only: intention → observation → reflection. No shared branding, account, lore or mystical health claims.

- Before: an optional intention in the person's own words.
- During: timestamped observations with optional mood, energy and hunger (1–5), tags and free text. Multiple entries per day, with or without a fast.
- After: a reflection on the fast. Prompts are invitations, never required forms.
- Browse/search the journal independently, and see linked observations beside the fast. Deleting a fast keeps its journal entries but removes their link. Deleting a journal entry is explicit.
- No AI interpretation, diagnosis or generated coaching. Record what happened; comparisons describe associations, never causes.

## Trends and measurements

- Week, month, quarter and year: total recorded hours, completed sessions, typical duration, goal completion among sessions with a goal, and charts.
- Optional weight (kg/lb), water (mL/US fl oz), waist and sleep records. Editable dates and values, export included. Hide weight when it is irrelevant.
- Comparisons use the chosen period, so returning after a break does not require destroying old history to get a fresh baseline. Show sample counts and empty states.
- Manual measurements work offline. Apple Health is an explicit, manual, read-only weight import with duplicate-sample protection; do not invent a fasting HealthKit type or write fasting as a workout.

## Plans and reminders

- Saved custom presets; flexible daily patterns and dated future fasts.
- Local notifications only, asked for when enabled. Goal reached, eating-window ending, optional weekly start reminders, and scheduled-session reminders. No nagging about inactivity.
- Edits, deletion and stopping cancel or replace the matching notifications. Permission denial is visible and never prevents tracking. Notifications do not change stored records.
- Lock-screen/home widgets and Live Activity show the current timer without opening the app. Journal text is never exposed on these surfaces. Live Activities are limited by iOS to eight active hours; the persisted fast continues, and reopening the app can refresh the surface. The paired Apple Watch companion shows the current timer and supports acknowledged Start, End and Water actions; phone tracking remains independent.

## Adaptive workspace and Watch

Shared SevenKit supplies a two-column layout driven by the current content proposal, system-adaptive tabs and readable purchase sheets. Today pairs timer with controls; History pairs calendar/heatmap with the selected day. Narrow windows and accessibility text use a vertical arrangement. Draft/editor state belongs above the changing layout. No device-name, physical-screen or orientation heuristics.

Full-display iPhone Duo builds require iOS SDK 27.1 or later; Xcode 27 requires an Apple-silicon Mac. `scripts/check-duo` gates that requirement on the selected Mac toolchain. Compiling with an older supported SDK does not establish Duo optimization; verify folding/unfolding, rotation, split windows, system sidebars, keyboard and accessibility sizes on the actual Duo runtime before claiming it.

The watchOS 10+ paired app uses SevenCompanion's WatchConnectivity transport. It keeps a minimal cached timer, goal and water summary, shows last sync and connection status, and derives elapsed time from the start timestamp. No journal, intentions, reflection, weight or purchase details are copied. Family Sharing shares the unlock, never personal tracking records.

Watch actions currently start a 16-hour fast, finish the specifically displayed active fast after confirmation, or add 250 mL of water. Actions require a reachable phone. The phone refreshes entitlement, checks the displayed archive revision and validates inside its atomic transaction. It persists a command receipt with the record before replying. A transport timeout remains visibly unconfirmed; retry sends the same ID, including after Watch relaunch. Requests expire after two minutes and cannot silently mutate a later fast. Existing fasts can end after trial expiry; new fasts and water require access. Phone errors never become Watch success. Rich preset editing and journal entry remain on the phone.

## Data ownership

Versioned local JSON, atomic protected writes. A failed write must not look like a successful save. Corrupt or newer-version files are preserved and block mutations; recovery is explicit. No backend or analytics. No network needed for tracking.

Export all records as JSON (round-trip backup) and CSV (readable tables with units, timezone and links). Import validates the whole file, previews counts and merges by stable IDs; duplicate IDs with different content (allowing only sub-microsecond date serialization noise) or overlapping records are rejected without partial changes. Export and deletion never require payment. No hidden automatic cloud sync. The OS may include app data in device backups.

## Trial and unlock

SevenKit's explicit free seven-day trial and one-time non-consumable unlock; price from StoreKit. Nothing auto-renews. First-launch options have equal weight. New records and advanced tracking require the trial/unlock; browsing and managing existing records always work. Never interrupt ending an active fast with a paywall. Pending/cancelled/failed purchases do not pretend to unlock anything. Production product IDs and Family Sharing require store configuration before release.

## Health language

This is a record, not a treatment plan. Do not assert that ketosis, fat burning or autophagy starts at a particular timer value. Longer is not presented as better. A short bundled note explains that fasting is not appropriate for everyone, particularly minors, pregnancy/breastfeeding, a history of eating disorders, and some conditions/medicines; consult a clinician where appropriate. An early stop is a valid record. No weight-loss promises or compulsory weight/body questions.

## Network contact

Only Apple through StoreKit when checking, buying or restoring purchases; the OS controls those requests. Health, notification and widget integrations stay on-device. The paired Watch receives a small private snapshot and sends explicit commands over the OS-managed WatchConnectivity channel; no service or account is involved. User-requested exports go to the destination chosen in the system share sheet. About links open only when tapped.

## Verification

Portable tests cover lifecycle, overlaps, multiple fasts/day, DST, cross-midnight totals, goal math, journal linkage, import conflict atomicity, corrupt/future schemas, write failure and CSV escaping. Apple builds use warnings as errors. Simulator tests exercise start/end/edit, persistence across relaunch, calendar and journal navigation, expiry access, dark mode and large text. Physical-device purchase, Health, notifications and widget acceptance remain distinct from simulator evidence.

## Current development boundary

The phone and embedded paired Watch targets implement the behavior above. Watch face complications and independent/offline Watch mutations are not implemented. Widget and Live Activity surfaces are display-only; starting/finishing uses the app. Health integration imports weight on request, with no background sync or Health writes. Plans are dated sessions plus saved presets and weekly start reminders, not an automatic coaching programme.

Actual Duo-runtime acceptance and physical paired-Watch acceptance, production signing, App Store products and Family Sharing, final private brand assets, and physical-device purchase/Health/notification/Lock Screen acceptance remain release work. The local StoreKit configuration is a development fixture, not evidence of production purchase setup.
