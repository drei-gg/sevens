# Vault

A private library for photos, videos, files and notes. Native iPhone and iPad; a seven-day trial followed by one purchase. No account, ads, analytics, crash SDK or app-operated storage service. Public source builds are unbranded.

## Before you buy

Photos already has a locked Hidden album. Notes can use its own password for supported notes. iOS can lock or hide eligible apps. Use those if they are enough. Vault adds independent PINs, patterns and passphrases, encrypted mixed-file libraries, organization and portable encrypted backups. It cannot protect an open session from a compromised operating system, erase copies already exported, or conceal its installation from device inspection.

## Initial implementation contract

- Multiple independent vaults. A PIN, pattern or passphrase opens its matching vault; the locked screen does not list vault names. The number and size of encrypted vault directories remain observable to an examiner. This is interface discretion, not forensic deniability.
- Choose a twelve-character-minimum passphrase, an 8–32 ASCII-digit PIN, or a 5×5 pattern with at least eight distinct dots. Draw continuously or tap dots in order; crossed collinear dots are included consistently. Confirm at creation/change. Credentials are type-separated and feed key derivation, not a UI-only comparison. Short predictable PINs/patterns remain vulnerable to guessing. Keep a separately generated random recovery key; optional Face ID/Touch ID unlock remains a convenience for one vault. Losing both credentials loses access. A recovery key cannot recreate missing files; keep a backup separately. Changing a password does not invalidate older backups.
- Files and media encrypted with Apple's authenticated encrypted-archive streams. Names, folders, notes, favorites and trash state live in an encrypted index. Random per-vault keys, salted PBKDF2 credential wrapping and CryptoKit authenticated encryption. No custom cipher or Secure Enclave AES claim.
- Import multiple files through Files and multiple current media representations through the system Photos picker. Preserve supplied bytes. Import never deletes the source. Progress and individual failures are visible; interrupted imports never make incomplete files appear saved. Unsupported/paired representations must be described honestly.
- Capture a photo with the system camera into the vault without deliberately adding it to Photos. Requires camera permission and real-device acceptance.
- Folders, subfolders, rename, notes, favorites, search, persistent ordering, list/grid library, multi-select, trash and restore. Permanent deletion requires explicit confirmation. No automatic permanent purge.
- Native image/file/video previews and originals export. An unsupported preview must still permit export. Previews and exports necessarily create temporary plaintext; remove owned temporary material on lock, dismissal and next launch. Copies handed to another app are outside Vault's control.
- Lock when backgrounded and hide sensitive content whenever inactive; explicit lock always available. In-flight operations may finish committing encrypted data but must not reopen or repopulate a locked UI.
- Portable encrypted backup to Files/share sheet, including credentials envelopes, encrypted index and original encrypted files. Display last successful backup preparation time and distinguish it from confirmation of cloud upload. Restore validates the entire container and all referenced files before publishing a new vault. Never overwrite an existing vault implicitly. Restore is not live sync.
- Change the unlock method or secret by replacing the key wrapper; do not re-encode original media. Biometric unlock uses explicit opt-in key custody tied to current enrollment. A device passcode fallback does not replace the independent vault passphrase.
- Trial expiration gates new vaults/imports/new notes; existing content remains readable, editable, recoverable, exportable and backable up. Restore and deletion remain available. Prices come from StoreKit, never hard-coded into purchase UI. Family Sharing concerns purchase entitlement, not shared vault data.

## Automatic encrypted backup

Opt in per vault by selecting a destination folder through Files (including iCloud Drive and compatible third-party providers). The shared SevenBackup queue stores the folder bookmark. After changes settle for two seconds, prepare a verified encrypted `.vault7` snapshot, persist it to the delivery queue and copy automatically. Unchanged revisions are skipped. New changes while preparation is running trigger another pass. Vault names and original filenames do not appear in provider filenames; opaque IDs/revisions do.

Already-prepared ciphertext can retry while locked, on launch/foreground and through an iOS BGProcessingTask. No encryption credential or master key is retained for background access. If locking/termination interrupts preparation, the next unlock catches up. Background execution is opportunistic; no exact interval or continual execution is promised. A full snapshot needs additional local/provider storage and must be performance-tested for large libraries.

Coordinated delivery streams to a partial file, verifies its bytes and publishes a versioned final file. Failures preserve the pending copy, prior successful receipt and prior backups. Backoff increases to one hour. Keep three successful versions tracked by this device; do not delete unrelated files. Turning automatic backup off leaves existing destination copies intact. Access revocation/moved destinations may require choosing the folder again.

Show pending preparation, queued delivery, failures and last provider-copy time separately. A Files-provider copy receipt does not establish remote upload completion. Automatic backup, retries and restore remain available after trial expiry. This is backup, not multi-device synchronization.

## Interface

System colors, type and controls. A quiet lock screen, immediate add actions in the library, search and filter above content, readable metadata, accessible controls. Respect Dynamic Type, dark mode, landscape, split-view and iPad navigation. Product claims describe concrete behavior rather than “military-grade,” “unbreakable,” or “invisible.”

## Verification gates

Round-trip original bytes; wrong credentials; modified/truncated ciphertext; random encryption; independent vaults; interrupted write/backup; archive path traversal, duplicate entries and trailing data; manifest integrity; restore into clean storage; trash then restore; password rotation without content change; locked-state async completion; expiry still allows export. Native build and UI create/import/note/lock/unlock/recovery/export tour. Real-device camera/biometrics/large video, kill/disk-full, purchase, accessibility and independent security review remain release gates until actually exercised.

## Follow-on capabilities, included in the same purchase if delivered

Conflict-safe multi-device sync, recipient-controlled sharing, alternate icons, break-in camera, direct Wi-Fi transfer, full Live Photo paired playback, streaming video seek, OCR, browser and desktop tooling. These are not claimed implemented by the initial build. No destructive duress behavior is included.
