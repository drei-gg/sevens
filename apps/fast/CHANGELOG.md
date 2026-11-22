# Changelog

## 22 November 2026, not released

- Android now uses the shared SevenKit purchase screens: Before you buy (with Health Connect, the Play price and "once, nothing renews"), the unlock sheet and the one-time trial-ended sheet. An existing trial or unlock carries over, and someone who saw the old welcome isn't shown the first-launch page again.
- Undo after swiping away a drink puts back the same entry, also after the trial; iPhone gets the same Undo.
- Android History: a legend and journal/rest-day marks on the heatmap, an Open ended filter, Add past fast on the selected day, measurements in the day panel and dates in your locale's format. Android Settings: Delete all records, Network contact and the shared About section. Plans can start a fast now.
- Resuming water reminders on Android asks for notification permission again when it's off.
- Open-source notices list the Rust core's crates on both phones and every bundled Android library.
- The elapsed/remaining switch on Today respects Reduce Motion.

## 18 November 2026 — feature continuation, not released

- Native Wear/watchOS preset selection now includes built-in and saved durations with versioned commands, stale-state/replay checks, and no private names in the snapshot. Wear persists its pending queue before sending, observes locale changes, and uses native list transformations. Both watches confirm queue clearing.

- Added Apple Watch timer complications and a timer-only app-group snapshot. Android Wear now distinguishes an unsynced watch from an idle timer and avoids fabricated progress for open-ended fasts. Paired physical-watch acceptance remains open.

## 0.1.0 — in development

- Editable fasting and eating windows, including open-ended records and forgotten fasts.
- Complete calendar, heatmap and searchable history; records remain accessible after trial expiry.
- Private observations, intentions and reflections; optional mood, energy and hunger.
- Weight, water, waist and sleep logs with selected-period charts.
- Dated plans, custom presets and optional local reminders.
- JSON backup/validated merge import and spreadsheet-friendly CSV export.
- Home and Lock Screen timer widgets, with an optional Live Activity.
- Optional manual import of weight from Apple Health.
- The Sevens' look on the iPhone app: ink instead of system blue, paper behind every screen, cards instead of grey boxes, and darker secondary text that passes contrast in light and dark. Text uses a small set of sizes, the timer is the largest thing on Today, and spacing follows one scale.
- Increase Contrast strengthens secondary text, separators and the ink.
- Shared SevenKit trial, one-time unlock and restore.
- Adaptive Today and History workspaces, with readable shared purchase sheets.
- Paired Watch timer, start/end and water actions with confirmed saves and duplicate protection.
- Records stay available to the Watch while the phone is locked (protected until first unlock after restart).

### One core for both phones — 13 November 2026

- Fast's rules, calculations, backup format and the shapes both apps draw (the water glass and the calendar's day rings) now come from one shared core, so the iPhone and Android apps behave the same. Existing records and backups open unchanged.
- Past the goal, the water glass keeps counting: each further goal's worth rises in the other tone, so every drink moves the water.

### Looks like a fasting app — 13 November 2026

- Today is built around one ring: the running time in large rounded digits, a warm arc toward the goal, Started and Goal tiles that open the editor, and one large End fast or Start a fast button. Tap the ring to switch between elapsed and remaining. After a fast, the ring counts down the eating window; with nothing running it shows your last fast.
- Water shows a glass that fills toward your goal. The water moves: it stays level as you tilt the phone, sloshes when you turn it and keeps a slow wave when still (still with Reduce Motion). Past the goal it counts in laps: each further goal's worth rises from the bottom over a full glass, alternating light and deep, so every drink still moves the water. With no goal set, the glass shows a drop and a link to set one, the day's total in large digits, and one-tap glass and Other amount buttons on Today and in Water. Water steps between days, lists drinks with their times, and charts the last seven days with your goal line; tapping a bar opens that day. Goal, glass and reminders move to the gear.
- Trends and Water trends open with summary tiles and rounded bar charts.
- Calendar days are 24-hour rings, midnight at the top and noon at the bottom. Each fast is one line from its start time to its end time; when it runs past midnight, the line carries along the tops into the next day, so overnight and multi-day fasts read as one stroke. Daytime fasts stay inside their day. Planned fasts are dashed and a running fast ends in an open dot. The heatmap uses the same warm hue.
- Switches keep their system green. The app-wide ink tint had made them cream with a white thumb in dark mode, which failed contrast.
- Two data hues, warm for time and blue for water, colour rings, bars and fills only. Controls stay ink. Both pass 4.5:1 on every surface, 7:1 with Increase Contrast.

### Integrated water tracker — 12 November 2026

- Today water card with configurable one-tap glass, undo and optional personal daily goal.
- Water history with daily totals, editable drinks, seven-day overview and complete entry list.
- Optional local water reminders during chosen hours; existing timer and schedule reminders share a bounded notification budget.
- Same water records across phone, Watch, trends and backups; no added service or subscription.
- Added water tracking to the first-launch “what it adds,” unlock and What's New copy.

### Water review follow-up

- Swipe-to-delete drinks with confirmation; edit dates/amounts directly.
- Dedicated water trends with selectable periods, daily totals and averages over logged days; missing logs stay explicit.
- Quick pause/resume for water reminders without losing their schedule or affecting fasting reminders.
- Clarified current-time-zone day grouping in water history.

## Android phone baseline — in development

- Ported circular water motion, live timer seconds/marker, linked calendar day rings, fast details and measurement line charts from the Swift reference.
- Replaced cramped editors with native bottom sheets, pinned Save/Cancel, native choices/switches and hour-based timer fields; added daily water navigation, confirmed swipe deletion and dedicated history/trends.

- Native Kotlin/Compose port with shared schema-1 JSON transfer, timers, history, journal, plans, water and measurement trends.
- Generic, privately branded personal and signed Play store distributions share a reusable Gradle convention; only personal builds accept the explicit unlock flag.
- Native Android timer widget/notification, local reminders and optional manual Health Connect weight import.
- Store-only Google Play one-time purchase integration; production Play configuration and device acceptance remain release work.
- See [Android](android/README.md) for platform differences and the [shared build guide](../../android/README.md) for build/signing gates.

### Android controls and widgets — 17 November 2026

- Shared Material 3 Expressive controls keep date/time pickers accessible, offer keyboard time entry and respect the device's clock preference. Connected choices wrap at large text sizes; inline search has a clear action.
- Compact and wide timer widgets use a live system clock, show the planned finish or goal-reached state, and refresh after saves and at the goal even when reminder notifications are off. Android may delay background updates. The widget can be added from Settings and is eligible for lock-screen placement on supporting hosts.

- Pin the widget worker runtime to WorkManager 2.11.0, matching Bloom; fixes the optimized build failing to initialize the older transitive Room database.
