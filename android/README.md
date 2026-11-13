# Android baseline

Native Kotlin / Compose, Android 8+ (API 26), compile/target API 36. JDK 21 and Android SDK 36 are required. The checked-in Gradle 8.14 wrapper verifies its distribution checksum. Plugin/library versions are pinned; resolved dependency locks are checked in. Run from the repository root, or open `android/` in Android Studio.

## Porting contract

1. iOS wins: port its chosen behavior, data contracts, edge cases and visual intent to Kotlin. Document platform differences rather than silently redesigning the product.
2. Check the iOS library choice first. Prefer its Android counterpart, then another reputable, license-compatible library, then custom code when necessary.
3. Native controls where they work. New UI controls require a concrete UX benefit. Material handles navigation, forms, dialogs, switches, buttons and pickers. Kizitonwose Calendar handles month/heatmap layout and paging; our day content shows fasting history. Vico is the Android counterpart to Swift Charts and owns chart rendering, axes, scrolling and zoom. The timer ring is an app-specific visual, not a replacement for a standard control.
4. Public code and generic resources stay unbranded. Private assets are Gradle resource overlays; building never copies them into tracked source.
5. Google Play Billing is the Android equivalent of the StoreKit platform-purchase exception. It exists only in the store dependency graph. See the root platform exception and dependency notices.

## Build matrix

| Distribution | Identity / icon | Access | Artifact / signing |
| --- | --- | --- | --- |
| `genericDebug` | `org.example.sevens.fast.debug`, Fast Tracker, neutral hourglass | Local 7-day trial, no purchase provider | Debug APK |
| `genericRelease` | `org.example.sevens.fast`, neutral | Same; no unlock override | Optimized unsigned APK |
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

```sh
android/gradlew -p android :core:test :fast:lintGenericDebug :fast:assembleGenericDebug
# With one emulator/device connected:
android/gradlew -p android :fast:connectedGenericDebugAndroidTest
# After intentionally reviewing dependency changes:
android/gradlew -p android :core:test :fast:assembleGenericDebug --write-locks
# Release gates + signed AAB using a disposable identity/key; never uploads:
scripts/check-android-variants
```

The `seven.android-app` Gradle convention supplies flavors, signing gates, version flags, SDK levels, shrinker and lint defaults for future app modules. `sevenkit` supplies theme/access contracts. `billing` supplies the isolated Play implementation. `core` is Fast's Rust core seen from Kotlin: UniFFI bindings generated from `apps/fast/core` at build time plus a small façade (`Fast.kt`). Building it needs Rust, the Android targets and cargo-ndk (`apps/fast/core/README.md`). Other apps add their own crate and binding module the same way.

Fast uses AndroidX `AtomicFile` with serialized IO; a failed write does not publish state. A corrupt/newer archive remains intact and blocks edits. JSON import validates and previews a stable-ID merge, keeping local settings. CSV includes every record type. Reminders use Android's inexact, idle-capable alarms and reconcile after boot, clock/timezone changes, app updates and edits; delayed water reminders outside the chosen window are dropped. Permission denial does not affect tracking.

Android provides a display-only home-screen timer widget and optional ongoing timer notification using native RemoteViews / NotificationCompat. Health Connect imports available weight records from the last 30 days only after explicit permission and preview, skipping already imported sample IDs; no writes or background reads. Physical Health permission/data and widget/notification behavior require device acceptance. Wear OS companion and cross-device sync are not part of this phone baseline.
