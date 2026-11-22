# Vault for Android

The iOS app's screens and behaviour on Android 11+ (API 30), Material 3 Expressive, on the shared base (`android/README.md`). Encryption is **format version 2**, implemented once in the Rust core with age; its specification is the "Format version 2" section of [`../core/README.md`](../core/README.md). This app adds no cipher, KDF or key handling of its own beyond Keystore custody for biometric unlock.

```sh
export JAVA_HOME=$(mise where java@temurin-21) ANDROID_SERIAL=emulator-5564
android/gradlew -p android :vault-core:test :vault:testGenericDebugUnitTest :vault:assembleGenericDebug :vault:lintGenericDebug
android/gradlew -p android :vault:connectedGenericDebugAndroidTest      # one emulator, ANDROID_SERIAL set
android/gradlew -p android :vault:connectedPersonalDebugAndroidTest     # adds trial expiry (generic is unlocked)
scripts/rust-core vault test                                            # the format's own gates
```

Words live in `src/main/res/values/strings.xml` (English; translations arrive as `values-<lang>/strings.xml`). The core reports typed cases (`VaultException`, `RecognitionFailure`, `VaultIssue`) and `Messages.kt` words each one; `MessagesTest` checks every core case has a sentence.

Debug builds only: `adb shell run-as org.example.sevens.vault.debug touch files/allow-screenshots` lifts FLAG_SECURE so screens can be captured for review. Release builds always set it.

## What maps to what

| iOS | Android |
| --- | --- |
| `VaultRepository` actor over the same core API (CryptoKit/AppleArchive only to read and migrate legacy version 1) | `VaultRepository` (Kotlin, serialized with a mutex; lock wipes the session identity at once and each operation runs on its own `fork()`) over the core's v2 API: `header_v2_*`, `index_seal`/`index_open`, `file_encrypt(_fd)`/`file_verify`/`file_decrypt`, `data_encrypt`/`data_decrypt`, `backup_v2_write`/`backup_v2_read(_fd)` |
| Application Support / Caches, file protection, excluded from backup | `noBackupFilesDir/Vault` (vault directories) and `cacheDir/Vault-plaintext` (previews, exports, capture), owner-only; Android backup rules include only the trial record |
| Keychain item, `.biometryCurrentSet` | Android Keystore AES-256-GCM key (StrongBox when present), per-use strong-biometric authentication, invalidated by a new enrollment, wrapping the core's `export_for_custody()`; BiometricPrompt with "Use your secret" instead of a device-credential fallback |
| `PrivacyShield`, scene phase | FLAG_SECURE on every window (recents thumbnail, screenshots, recording), a "Vault locked" cover while not resumed, lock on `onStop` (a system screen Vault launched gets a bounded wait, below) |
| `fileImporter`, PhotosPicker | Storage Access Framework (`OpenMultipleDocuments`) and the Android Photo Picker (`PickMultipleVisualMedia`); each source is read once, from its descriptor, never modified |
| UIImagePickerController camera | CameraX inside the app, written only to Vault's temporary area, then encrypted and removed; nothing reaches MediaStore |
| Quick Look | In-app viewers: images (Telephoto zoom), video and audio (Media3), PDF (PdfRenderer), text; anything else offers export |
| Share sheet / Files export | A "ready" sheet: Save to device (SAF create document or folder) or Share (FileProvider, read grant) |
| SevenBackup + BGProcessingTask | `AutomaticBackup` (the same queue rules: two-second settle, skip unchanged revisions, verified partial-then-final copy, backoff to one hour, three versions kept, unrelated files never touched) to a persisted SAF tree, with WorkManager delivery opportunities |

## Platform differences

- **Encryption format.** Both apps write format version 2 (age), so vaults and `.vault7` backups are the same on both. The iPhone app also reads its legacy version 1 and migrates a vault on its first unlock with a PIN, pattern or passphrase. Android reads version 2 only: a version 1 `.vault7` is refused as an older iPhone format ("Update Vault on the iPhone and back up again").
- **Leaving for a system screen.** Leaving Vault locks it at once, as on iOS. The exception: the document, photo or folder picker, SAF save, share chooser, camera permission prompt or biometric prompt that Vault itself launched stops its activity on Android (on iOS these don't background the app). Each launch is tracked (`SystemFlowGuard`) until its result arrives or Vault returns, and the lock waits for it at most two minutes from launch; then it locks, clearing temporary plaintext as usual.
- **Screenshots.** iOS says it does not prevent screenshots; Android's FLAG_SECURE does prevent them (and screen recording), which is the platform's standard for this kind of app.
- **Notes** are encrypted from memory on both platforms; no plaintext note file is written.
- **Add actions** are an Expressive floating action button menu (Files, Photos, Note, Camera) rather than iOS's inline buttons. Multi-select starts with a long press (or Select in the menu) with a contextual top bar; moving to the trash offers Undo.
- **Content types** are stored as uniform type identifiers like iOS (`public.jpeg`, `public.plain-text`), translated from MIME types; an unmapped type keeps its MIME type as `dyn.android.mime.<type>`.
- **Trial notice.** When paid features are unavailable the library says so in one line above the folders; iOS shows nothing until an add action is tapped.
- **Large screens** use a centred 672 dp column for forms and Settings and an adaptive grid; there is no list-detail split pane yet.
- **Credential kind** is a connected button group while all three labels fit on one line, and a radio-button list (labels wrap, nothing truncates) at large font scales or narrow widths.

## Not verified here

Physical-device biometrics, StrongBox, large videos and libraries, low storage, cloud-drive SAF providers, Play purchases and TalkBack walkthroughs need device acceptance, as on iOS.
