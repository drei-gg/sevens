# Changelog

## 0.1.0 — unreleased

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
