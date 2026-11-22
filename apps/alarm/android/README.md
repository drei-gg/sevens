# Alarm & Loops for Android

The native Kotlin/Compose phone port of Alarm & Loops, on the shared Android base (`seven.base-app`). Behaviour, wording, the free/paid split and the archive follow the iOS app. Routines, validation, schedules, missions, the backup format and the delivery rules come from the Rust core (`apps/alarm/core`); this module keeps the system alarms, ringing, sensors, the camera, files and every pixel.

See the shared [Android build guide](../../../android/README.md) for the generic/personal/store matrix.

## How an alarm rings

- Each registration the core asks for (`desired_registrations`) is one `AlarmManager.setAlarmClock` alarm, armed for its next ring (`registration_next`): exact in Doze and shown as the phone's next alarm. Android has no weekly repeating alarm, so the receiver re-arms a weekly registration when it rings; a one-off rings once. Receipts are stored before arming, as on iOS, and reconciliation runs on launch, resume, every edit, boot, app update, exact-alarm permission changes, and clock or time-zone changes (which re-arm everything, because an armed instant is stale).
- Quiet reminders are notifications, one registration per weekday like iOS, at most 60. They are exact while idle when allowed.
- Ringing is a foreground service (`systemExempted`, the type Android gives alarm clocks) playing the tone at alarm volume with vibration, under a high-priority notification whose full-screen intent shows the ringing screen over the lock screen. It has the system alarm's two choices: **Stop** records a dismissal (and schedules the wake-up check), **Open** asks to unlock and opens the mission. The alarm keeps ringing while the unlock prompt shows and is silenced only once the phone unlocks; a cancelled unlock leaves it ringing with Stop and Open. An alarm left alone goes quiet after 10 minutes, as Android's Clock does; nothing is recorded.
- Alarms that go off while one is ringing wait in order, each under its own notification with its own Stop and Open, so each gets its own dismissal record. The first keeps ringing; when it is stopped, opened or goes quiet, the next one rings (and gets its own 10 minutes). The ringing screen says how many are waiting.
- Settings' test alarm uses the default tone and nothing else from the defaults: no snooze, no wake-up check, and its Stop or Open never writes History, as on iOS.
- Clock times in the notification and ringing screen follow the system's 12/24-hour setting even when the process was started by the alarm.
- OS backup and device transfer include the archive but not imported sounds (`AlarmLoops/Sounds`, up to 128 MiB, past Auto Backup's 25 MB quota); a restored alarm plays its built-in fallback until the sound is re-imported.
- The archive lives in device-protected storage, so alarms are restored and can ring after a reboot before the first unlock. iOS keeps it readable after the first unlock instead.
- Permissions: `USE_EXACT_ALARM` (alarm clocks; `SCHEDULE_EXACT_ALARM` before Android 13), notifications, full-screen alarms (Android 14+), all asked from "Allow alarms and reminders" with the system's own screens, and the status line says which one is missing.

## Platform differences

- AlarmKit's system alert becomes the app's ringing screen and notification. Open stops the sound before the mission, as AlarmKit's Open does; on a locked phone that happens after the unlock succeeds.
- DataScanner becomes CameraX with ZXing (Apache-2.0) in every flavor; Swift Charts becomes Vico. Shake uses the linear accelerometer through the core's counter; Walk uses the step detector with the activity-recognition permission.
- Material 3 Expressive replaces the SwiftUI screens: a navigation bar (a rail on wide screens) for the four tabs, full-screen forms for the editors, Material's clock-face time picker where iOS has a wheel, connected day toggles and validated numeric entry, a bottom sheet for the custom nap, long-press menus and TalkBack actions where iOS has context menus and swipe actions, a snackbar with Undo after deleting a record.
- The trial-ended sheet is never shown: iOS presents expiry only in Settings, never over a ringing alarm or a mission.
- Saved schedule is a native Glance Material 3 Home Screen widget, matching the new iOS WidgetKit capability. It displays a minimal persisted projection and opens the app; no mutation or scheduling runs from the widget. Missing/unverified schedules and snapshots older than 24 hours are explicit. Add it through Settings or the launcher picker. No ongoing notification beyond the ringing one.

## Verification

`scripts/rust-core alarm test` covers the core, including `tests/delivery.rs` for the rules this module calls. `testGenericDebugUnitTest` covers the ringing queue and the ringing screen's Open (unlock) order without a device. `connectedGenericDebugAndroidTest` covers the model on the real core (receipts, expiry, reminder split, snooze ownership and limit, dismissal and wake-up check, emergency stop, naps and duplicates, backup round trip and dormant import, corrupt-file preservation, atomic persistence, arming a real alarm clock) and the main screens (creating an alarm, a quick nap, the expired-trial refusal, a mission preview, a ringing alarm's mission, history correction, alarm defaults). Ringing over the lock screen, Stop, Open into the mission, snooze, reboot restoration and time-zone re-arming were checked on the API 36 emulator; physical-phone acceptance (OEM battery managers, real Doze) remains release work.
