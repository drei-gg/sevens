# Changelog

## 0.1.0 — in development

- Editable fasting and eating windows, including open-ended records and forgotten fasts.
- Complete calendar, heatmap and searchable history; records remain accessible after trial expiry.
- Private observations, intentions and reflections; optional mood, energy and hunger.
- Weight, water, waist and sleep logs with selected-period charts.
- Dated plans, custom presets and optional local reminders.
- JSON backup/validated merge import and spreadsheet-friendly CSV export.
- Home and Lock Screen timer widgets, with an optional Live Activity.
- Optional manual import of weight from Apple Health.
- Shared SevenKit trial, one-time unlock and restore.
- Adaptive Today and History workspaces, with readable shared purchase sheets.
- Paired Watch timer, start/end and water actions with confirmed saves and duplicate protection.
- Records stay available to the Watch while the phone is locked (protected until first unlock after restart).

## Android phone baseline — in development

- Ported circular water motion, live timer seconds/marker, linked calendar day rings, fast details and measurement line charts from the Swift reference.
- Replaced cramped editors with native bottom sheets, pinned Save/Cancel, native choices/switches and hour-based timer fields; added daily water navigation, confirmed swipe deletion and dedicated history/trends.

- Native Kotlin/Compose port with shared schema-1 JSON transfer, timers, history, journal, plans, water and measurement trends.
- Generic, privately branded personal and signed Play store distributions share a reusable Gradle convention; only personal builds accept the explicit unlock flag.
- Native Android timer widget/notification, local reminders and optional manual Health Connect weight import.
- Store-only Google Play one-time purchase integration; production Play configuration and device acceptance remain release work.
- See [Android](android/README.md) for platform differences and the [shared build guide](../../android/README.md) for build/signing gates.
