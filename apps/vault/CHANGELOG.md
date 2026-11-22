# Changelog

## 18 November 2026 — feature continuation, not released

- Selected-file OCR review queue on both phones: up to ten images/PDFs, explicit review/save/skip, corrupt-file retry, full bounded PDFs, committed counts and lock cleanup. Originals remain unchanged; no background queue or plaintext index.

- Added opt-in note-content search on both phones, including saved OCR text, with explicit coverage and unreadable/skipped counts. Reads remain authenticated and memory-only; lock clears queries/results. Android uses native search/filter/progress controls and consumes keyboard insets in the library.

- PDF text recognition now uses a native page-range review on both phones, sequential on-device rendering/OCR, and the existing explicit encrypted-note save. Shared bounds limit each request to 20 pages, 2400-pixel renders and the existing note size. Lock discards reviews and stops work between pages; originals remain unchanged.

- Added on-device image text recognition, an editable review and explicit saving as an encrypted note on both phones. Lock clears the review and rejects late results. Android reuses the shared bundled English OCR engine; iOS uses Vision.

## 0.1.0 — unreleased

### Vault for Android, and one encrypted format — 16 November 2026

- Vault runs on Android: independent vaults opened by passphrase, PIN or 5×5 pattern with no vault list, recovery keys, opt-in fingerprint or face unlock for one vault (Android Keystore, StrongBox when present), folders, notes, favorites, search, sorting, list and grid, multi-select, trash with undo, Files and photo-picker imports, in-app camera capture, in-app previews, export, manual and automatic encrypted backup to a chosen folder, and restore that never overwrites. Hidden from screenshots and recents; locks when you leave.
- Format version 2: vaults and backups are encrypted with age (age-encryption.org/v1) in the shared core, the same files on every platform. Android uses it from the start; the iPhone app keeps version 1 until it migrates, and a version 1 backup on Android is refused as an older iPhone format, never as damaged.

### One core for both phones — 15 November 2026

- Vault's credential rules, pattern canonicalization, library index and edits, header format, size limits and `.vault7` backup framing now come from one shared core for the iPhone and Android apps. All encryption, key derivation, Keychain custody and file protection stay native on the phone, and recovery keys and note text never enter the core. Existing vaults and backups open unchanged.

- Added independent PIN and 5×5 drawn/tapped pattern credentials, selection during create/open/change/restore, and compatibility with existing passphrase vaults.
- Added automatic encrypted backup to a persistently selected Files-provider folder, revision coalescing, durable retries, coordinated/verified copying, three-version retention and background delivery opportunities.
- Extracted Scanner's manual export picker into SevenKit; Vault and Scanner now share it. The new SevenBackup product keeps platform transport outside portable SevenKitCore.

- Initial native iPhone/iPad encrypted vault library with independent passphrases, random recovery keys and optional biometric unlock.
- Authenticated streaming encryption for original files, encrypted library metadata, folders, notes, favorites, search, sorting, list/grid views, trash and original export.
- Photos/Files imports, camera capture, system previews, encrypted portable backups and fully verified restore without implicit overwrite.
- SevenKit trial and one-time purchase integration; existing data access and export remain available after expiry.
- Privacy covering on inactivity and locking on background; no telemetry or app-operated backend.

Development build, not a release or independent security audit. Live cloud sync/sharing, paired Live Photo playback, branded icons and destructive duress are not implemented. Automatic folder backup is implemented; actual provider delivery and iOS background scheduling still require device acceptance. Photos picker imports its supplied current representation; Live Photos are imported as stills. Physical-device, large-library, camera/biometric and production-purchase acceptance remain required.
