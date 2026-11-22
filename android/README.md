# Android baseline

Native Kotlin / Compose, Android 11+ (API 30), compile API 37 / target API 36. JDK 21 and Android SDK 37 are required. The checked-in Gradle 9.9.1 wrapper verifies its distribution checksum. Plugin/library versions are pinned; resolved dependency locks are checked in. Run from the repository root, or open `android/` in Android Studio.

## Porting contract

The [Android interaction guidelines](UI_GUIDELINES.md) consolidate Material 3 Expressive component choices, accessibility/state rules, and the per-screen evidence required for UI iterations.

1. iOS is the capability and behavior reference. Preserve data contracts and edge cases, including widget availability; use native Material 3 Expressive controls and Android interaction conventions instead of reproducing iOS presentation (Drei, 2026-11-17). Document intentional platform differences.
2. Check the iOS library choice first. Prefer its Android counterpart, then another reputable, license-compatible library, then custom code when necessary.
3. Native controls where they work. New UI controls require a concrete UX benefit. Material handles navigation, forms, dialogs, switches, buttons and pickers. Kizitonwose Calendar handles month/heatmap layout and paging; our day content shows fasting history. Vico is the Android counterpart to Swift Charts and owns chart rendering, axes, scrolling and zoom. The timer ring is an app-specific visual, not a replacement for a standard control.
4. Public code and generic resources stay unbranded. Private assets are Gradle resource overlays; building never copies them into tracked source.
5. Google Play Billing is the Android equivalent of the StoreKit platform-purchase exception. It exists only in the store dependency graph. See the root platform exception and dependency notices.

## Build matrix

| Distribution | Identity / icon | Access | Artifact / signing |
| --- | --- | --- | --- |
| `genericDebug` | `org.example.sevens.fast.debug`, Fast Tracker, neutral hourglass | Built from source: everything unlocked, no trial, nothing for sale (`SourceBuildPurchaseSource`) | Debug APK |
| `genericRelease` | `org.example.sevens.fast`, neutral | Same: unlocked, the build F-Droid would ship | Optimized unsigned APK |
| `personalDebug` | private base (or `gg.drei.sevens.fast`) + `.personal.debug`, private icon/name | Trial, or explicit build-time personal unlock | Debug APK |
| `personalRelease` | private base (or fallback) + `.personal`, private icon/name | Same explicit unlock choice | Optimized, development-signed APK |
| `storeRelease` | Actual Play package/name/private icon | Local 7-day trial + verified Play non-consumable | Optimized signed AAB |

Personal and store installs cannot share application data or replace one another. Generic and store binaries compile the personal-unlock constant to false. Store tasks reject `personalUnlock=true`. No launch argument, intent extra or settings toggle grants access. Backups do not contain entitlements.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
scripts/android fast generic debug
scripts/android fast generic release
scripts/android fast personal release -Psevens.brandDir=/private/sevens-brand -Psevens.personalUnlock=true
# Use -Psevens.personalUnlock=false (the default) to exercise a personal trial.
```

Private overlay layout:

```text
/private/sevens-brand/fast/android/
  identity.properties
  res/                       # already-generated launcher resources
```

`identity.properties` uses Java properties syntax (escape special characters accordingly):

```properties
displayName=7&D Fast
# Required only for store distribution; use real Console values, not placeholders:
applicationId=YOUR_ACTUAL_PLAY_PACKAGE
unlockProductId=YOUR_ACTUAL_ONE_TIME_PRODUCT
billingPublicKey=BASE64_RSA_PUBLIC_KEY_FROM_PLAY
uploadCertificateSha256=SHA256_OF_UPLOAD_CERTIFICATE
```

The personal fallback ID is a development namespace, not a reserved Play identity. The public repo contains no private icon, upload key, credential, production identifier or product claim.

## Store build / publishing boundary

Set `SEVENS_UPLOAD_KEYSTORE`, `SEVENS_UPLOAD_KEY_ALIAS`, `SEVENS_UPLOAD_STORE_PASSWORD`, and `SEVENS_UPLOAD_KEY_PASSWORD` in the environment. Passwords never go into command arguments, tracked properties or output. Store builds check the configured certificate fingerprint and reject Android Debug certificates. A store build requires explicit version code/name, real identity/product/public key and private resources.

```sh
scripts/android fast store release \
  -Psevens.brandDir=/private/sevens-brand \
  -Psevens.versionCode=1 -Psevens.versionName=0.1.0
```

Output: `apps/fast/android/build/outputs/bundle/storeRelease/`. R8 mapping is in `build/outputs/mapping/storeRelease/`; preserve it with each release. `scripts/android` builds and lints only. It has no upload/publish step. Console package/product setup, upload key registration, purchase/restore/pending/cancellation testing from an internal Play track, Data safety and listing review are separate release work.

The free trial is explicit and device-local. Backward clock changes do not extend a trial already observed at a later time, but uninstalling/clearing storage can reset it; there is no server-based trial enforcement. Play purchases are checked locally with the Console RSA key, acknowledged, and restored on resume. A successful ownership query can revoke an old cached entitlement; network errors preserve the previous signed receipt. Client-only verification is a deliberate no-backend tradeoff, not tamper-proof licensing. Pending purchases do not unlock.

## Verification

Changed Compose workflows run the Accessibility Test Framework through `ui-test-junit4-accessibility` (AndroidX, Apache-2.0), backed by Google’s [Accessibility Test Framework](https://github.com/google/Accessibility-Test-Framework-for-Android) (Apache-2.0). These are Android-test dependencies only; they are not shipped in the apps. Keep their resolved dependencies locked. Automated checks complement the visual and service checks in [UI_GUIDELINES.md](UI_GUIDELINES.md).

```sh
android/gradlew -p android :core:test :fast:lintGenericDebug :fast:assembleGenericDebug
# With one emulator/device connected:
android/gradlew -p android :fast:connectedGenericDebugAndroidTest
# After intentionally reviewing dependency changes:
android/gradlew -p android :core:test :fast:assembleGenericDebug --write-locks
# Release gates + signed AAB using a disposable identity/key; never uploads:
scripts/check-android-variants
```

The `seven.android-app` Gradle convention supplies flavors, signing gates, version flags, SDK levels, shrinker and lint defaults for future app modules. `sevenkit` supplies theme/access contracts. `billing` supplies the isolated Play implementation. `core` is Fast's Rust core seen from Kotlin: UniFFI bindings generated from `apps/fast/core` at build time plus a small façade (`Fast.kt`). Building it needs Rust, the Android targets and cargo-ndk (`apps/fast/core/README.md`). Other apps get theirs from `seven.rust-core` (below).

`sevenocr` supplies Scanner and Vault’s bundled English recognition engine. It owns pinned Tesseract/JavaCPP dependencies, the model-fetch checksum and consumer shrinker rules. Consuming APK modules keep `traineddata` uncompressed for `AssetManager.openFd`; recognition itself has no network dependency.

## The shared base: adding a Seven

QR, Alarm, Scanner, Cleaner and Vault start from the shared base; Fast predates it and still wires these pieces by hand. An app on the base is three short files and its UI:

```text
apps/<app>/android/
  build.gradle.kts         extra["sevenApp"], extra["sevenGenericName"], apply(plugin = "seven.base-app")
  proguard-rules.pro       keeps JNA and gg.seven.<app>.core
  core/build.gradle.kts    plugins { id("seven.rust-core") }   (Gradle :<app>-core)
  src/main/…               manifest, generic icon, backup rules, MainActivity, <App>Model, <App>Content, screens
```

- `seven.rust-core` (`buildSrc/SevenRustCorePlugin.kt`) is the crate seen from Kotlin: it generates the UniFFI bindings from `apps/<app>/core` before compiling and runs JVM tests against the crate's host build. Put a façade there only when Kotlin needs one; rules stay in Rust.
- `seven.base-app` (`buildSrc/SevenBasePlugin.kt`) applies Android, Kotlin, Compose and `seven.android-app`, sets the namespace `gg.seven.<app>`, builds the crate's Android libraries before `preBuild`, and adds `:<app>-core`, `sevenkit`, JNA and the AndroidX basics. It adds `android/flavors/<flavor>/kotlin` to each distribution, so `gg.seven.flavor.sevenPurchases` is the local trial in generic/personal and Play Billing in store, with `:billing` in the store graph only.
- `sevenkit` carries the brand moments, ported from iOS SevenKit: `SevenStore` (trial, purchase and launch flags as one `Entitlement`), `SevenRoot` (Before you buy on first launch, the trial-ended sheet once, the unlock sheet on demand), `LocalSevenGate` (`gate.run("Peek") { … }` runs a paid action or offers the unlock), `SevenAbout` (Settings' trial/unlock and about sections, with the notices under `assets/notices`), `SevenTopBar` and `Theme.Seven` (the paper window background). Each app supplies its words in `<App>Content.kt`; "Before you buy" names what Android does built in, so it differs from the iOS copy.
- Register the app in `settings.gradle.kts` (the loop near the end), add its library notices under `src/main/assets/notices`, and its data files to `res/xml/backup_rules.xml` and `data_extraction_rules.xml`.

```sh
android/gradlew -p android :qr-core:test :qr:lintGenericDebug :qr:assembleGenericDebug
scripts/android qr generic debug
```

Fast uses AndroidX `AtomicFile` with serialized IO; a failed write does not publish state. A corrupt/newer archive remains intact and blocks edits. JSON import validates and previews a stable-ID merge, keeping local settings. CSV includes every record type. Reminders use Android's inexact, idle-capable alarms and reconcile after boot, clock/timezone changes, app updates and edits; delayed water reminders outside the chosen window are dropped. Permission denial does not affect tracking.

Android provides a display-only Glance fasting timer widget (home screen and supporting lock-screen hosts) and an optional ongoing timer notification using native Chronometer / NotificationCompat. The widget has compact/wide layouts, launcher previews and a Settings pin action; it refreshes on saved changes, at the chosen goal with an inexact alarm, and on the launcher’s 30-minute schedule. Health Connect imports available weight records from the last 30 days only after explicit permission and preview, skipping already imported sample IDs; no writes or background reads. Physical Health permission/data and widget/notification behavior require device acceptance. Cross-device sync is not part of this phone baseline; the Wear OS companion is below.

## Wear OS companion

`apps/fast/wear` (Gradle `:wear`) ports the Apple Watch app (`apps/fast/Watch`): Compose for Wear OS with Wear Material 3, a timer tile, an elapsed / goal complication and an ongoing activity while a fast runs. It holds no records. It shows the phone's last snapshot and keeps at most one pending request, retried verbatim until the phone answers; the phone applies requests through the Rust core (`watchApply`), whose receipts make a retry idempotent.

- Transport is the Wearable Data Layer (Google Play services), the counterpart of WatchConnectivity: the phone publishes its snapshot as the DataItem `/fast/snapshot` after every committed write, and the watch sends `/fast/request` with `MessageClient.sendRequest` to a node advertising the `fast_phone` capability.
- The wire protocol (`apps/fast/wear/wire`, Gradle `:fast-wire`) keeps the Swift FastWire JSON names, actions, failures, version and 32,768-byte limit, except that dates are integer epoch milliseconds. The two protocols never meet: each watch only talks to its own platform's phone.
- Flavor rule: like Play Billing, the Data Layer is a platform exception. The phone's bridge (`apps/fast/android/src/wearBridge`, wired by `wear-bridge.gradle.kts` with the manifests in `src/personal` and `src/store`) is compiled into the personal and store flavors only. The generic phone flavor has no Play services:

  ```sh
  android/gradlew -p android :fast:dependencies --configuration genericReleaseRuntimeClasspath | grep -c play-services   # 0
  ```

- The Wear app uses the same convention plugin as the phone, so its application ID matches the phone's per flavor and build type, as the Data Layer requires (both must also share a signing key). Personal and store Wear builds take the same private overlay (`-Psevens.brandDir`) as the phone.

```sh
android/gradlew -p android :fast-wire:test :fast:testPersonalDebugUnitTest :wear:lintGenericDebug :wear:assembleGenericDebug
android/gradlew -p android :wear:assemblePersonalRelease -Psevens.brandDir=/private/sevens-brand
```

## Material 3 Expressive and widgets — 2026-11-17

SevenKit owns accessible date/time picker fields, clock/keyboard time entry, exposed dropdowns, connected single-choice groups with a wrapping large-text fallback, inline search with clear/IME behavior, and launcher pin requests. Apps keep the fixed paper/ink scheme. QR uses a Material floating toolbar for scan sources and torch; primary actions use Expressive shape feedback.

Bloom adds its opt-in Glance home-screen widget. The Rust dashboard provides the cycle day and reminder time; discreet mode redacts the day and disabled mode shows only a generic prompt. No journal or contact text is stored in Glance state. Widget refresh generations are persisted because `update()` can reuse an active Glance composition.

Widget instrumentation binds real `AppWidgetHost` views and checks ticking, committed changes and privacy transitions. Device launcher placement, background/Doze timing and lock-screen hosting still require device-specific acceptance.
