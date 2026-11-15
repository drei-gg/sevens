# Fast for Android

The native Kotlin/Compose phone port of Fast. Product behavior and schema 1 follow the Swift implementation, including editable timers, eating windows, calendar/heatmap/list history, private journal, dated plans/rest days/custom timers, water and other measurements, selected-period trends, JSON merge and CSV export.

See the shared [Android build guide](../../../android/README.md) for the generic/personal/store matrix, private branding, explicit personal unlock, signing and release gates. This module is the first consumer of the reusable `seven.android-app` convention, `sevenkit` and store-only `billing` modules.

## Platform differences

- Material navigation, bottom sheets with pinned Save/Cancel actions, forms, date/time pickers and system document pickers replace their SwiftUI counterparts. Kizitonwose Calendar supplies calendar/heatmap layout; Vico supplies charts. The custom timer ring retains the iOS visual intent and becomes text plus a native progress indicator at large font sizes.
- Water ports the Swift circular liquid simulation (area-based fill, overfill layer, accelerometer/gyroscope response, reduced motion and lifecycle cleanup). With no personal goal, it shows the same drop placeholder as iOS; no intake target is invented.
- Calendar cells use 24-hour arcs and cross-day links; large text uses the date picker and day records. Measurement charts use line/point marks with fitted vertical ranges. Journal cards open the editable native sheet directly.
- Health Connect replaces HealthKit for optional, manual weight import. It reads the last 30 days after permission and preview; it does not write health data or run background reads.
- A native display-only home-screen widget and optional ongoing chronometer notification provide timer surfaces. Android reminders are inexact and subject to OS delivery restrictions; tracking itself does not require notifications.
- Google Play Billing replaces StoreKit only in the store edition. Generic/personal builds have no purchase SDK or Internet permission. Real purchase acceptance requires a configured Play product and internal-track install.
- This is the phone/tablet baseline. There is no Wear OS companion or cross-device sync. Backup records transfer between platforms; purchase entitlements do not.

## Verification

Pure tests cover liquid dynamics/area, day-arc midnight/DST boundaries, validation, atomic merge, timezones/DST, history, reminder planning and a fixture exported by the actual Swift codec. Native tests cover repository durability/failure, expired access and start/end/journal/water workflows across activity recreation, rating choices, water-goal unit conversion and selected-day quick-add/undo. Build verification and a disposable signed store fixture are described in the shared guide. Private visual/device evidence belongs outside the public source tree.
