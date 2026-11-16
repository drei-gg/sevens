# Vault for Android

The iOS app's screens and behaviour on Android 8+ (API 26), Material 3 Expressive, on the shared base (`android/README.md`). Encryption is **format version 2**, implemented once in the Rust core with age; its specification is the "Format version 2" section of [`../core/README.md`](../core/README.md). This app adds no cipher, KDF or key handling of its own beyond Keystore custody for biometric unlock.

```sh
export JAVA_HOME=$(mise where java@temurin-21) ANDROID_SERIAL=emulator-5564
android/gradlew -p android :vault-core:test :vault:testGenericDebugUnitTest :vault:assembleGenericDebug :vault:lintGenericDebug
android/gradlew -p android :vault:connectedGenericDebugAndroidTest      # one emulator, ANDROID_SERIAL set
scripts/rust-core vault test                                            # the format's own gates
```

Debug builds only: `adb shell run-as org.example.sevens.vault.debug touch files/allow-screenshots` lifts FLAG_SECURE so screens can be captured for review. Release builds always set it.

## What maps to what

| iOS | Android |
| --- | --- |
| `VaultRepository` actor, CryptoKit, AppleArchive | `VaultRepository` (Kotlin, serialized with a mutex) over the core's v2 API: `header_v2_*`, `index_seal`/`index_open`, `file_encrypt(_fd)`/`file_verify`/`file_decrypt`, `data_encrypt`/`data_decrypt`, `backup_v2_write`/`backup_v2_read(_fd)` |
| Application Support / Caches, file protection, excluded from backup | `noBackupFilesDir/Vault` (vault directories) and `cacheDir/Vault-plaintext` (previews, exports, capture), owner-only; Android backup rules include only the trial record |
| Keychain item, `.biometryCurrentSet` | Android Keystore AES-256-GCM key (StrongBox when present), per-use strong-biometric authentication, invalidated by a new enrollment, wrapping the core's `export_for_custody()`; BiometricPrompt with "Use your secret" instead of a device-credential fallback |
| `PrivacyShield`, scene phase | FLAG_SECURE on every window (recents thumbnail, screenshots, recording), a "Vault locked" cover while not resumed, lock on `onStop` |
| `fileImporter`, PhotosPicker | Storage Access Framework (`OpenMultipleDocuments`) and the Android Photo Picker (`PickMultipleVisualMedia`); each source is read once, from its descriptor, never modified |
| UIImagePickerController camera | CameraX inside the app, written only to Vault's temporary area, then encrypted and removed; nothing reaches MediaStore |
| Quick Look | In-app viewers: images (Telephoto zoom), video and audio (Media3), PDF (PdfRenderer), text; anything else offers export |
| Share sheet / Files export | A "ready" sheet: Save to device (SAF create document or folder) or Share (FileProvider, read grant) |
| SevenBackup + BGProcessingTask | `AutomaticBackup` (the same queue rules: two-second settle, skip unchanged revisions, verified partial-then-final copy, backoff to one hour, three versions kept, unrelated files never touched) to a persisted SAF tree, with WorkManager delivery opportunities |

## Platform differences

- **Encryption format.** Android writes format version 2 only (age). The iPhone app still writes version 1 until it migrates; a version 1 `.vault7` offered here is refused as an older iPhone format ("Update Vault on the iPhone and back up again"), and the iPhone's current reader refuses a version 2 backup as unsupported. Once iOS moves to version 2, vaults and backups are the same on both.
- **Leaving for a system screen.** Opening the document picker, photo picker, camera permission prompt or share sheet stops Vault's activity. Locking then would lock the vault you are importing into, so a picker or sheet Vault opened gets five minutes before the lock; coming back cancels it. Leaving any other way locks at once.
- **Screenshots.** iOS says it does not prevent screenshots; Android's FLAG_SECURE does prevent them (and screen recording), which is the platform's standard for this kind of app.
- **Notes** are encrypted from memory; no plaintext note file is written (iOS writes a protected temporary file first).
- **Add actions** are an Expressive floating action button menu (Files, Photos, Note, Camera) rather than iOS's inline buttons. Multi-select starts with a long press (or Select in the menu) with a contextual top bar; moving to the trash offers Undo.
- **Content types** are stored as uniform type identifiers like iOS (`public.jpeg`, `public.plain-text`), translated from MIME types; an unmapped type keeps its MIME type as `dyn.android.mime.<type>`.
- **Trial notice.** When paid features are unavailable the library says so in one line above the folders; iOS shows nothing until an add action is tapped.
- **Large screens** use a centred 672 dp column for forms and Settings and an adaptive grid; there is no list-detail split pane yet.
- At 200% font scale the shared credential-kind button group ellipsizes "Passphrase" (SevenKit's `ConnectedChoice`).

## Not verified here

Physical-device biometrics, StrongBox, large videos and libraries, low storage, cloud-drive SAF providers, Play purchases and TalkBack walkthroughs need device acceptance, as on iOS.
