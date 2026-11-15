# Storage format and security boundaries

Version 1, development format. This documents the implementation; it is not a security audit or a promise of compatibility with future formats. Any format change must have explicit versioning and a tested migration before shipping to users.

## Key hierarchy

Each vault has an independent random 256-bit master key. The user supplies a passphrase of 12–4096 characters (also limited to 4096 UTF-8 bytes). Alternatively, use an 8–32 ASCII-digit PIN or an eight-dot-minimum 5×5 pattern. PIN and pattern inputs are versioned/type-prefixed before derivation, and headers route only the chosen credential type. Pattern serialization includes crossed intermediate dots consistently. These human-chosen credentials do not inherit the entropy of the 256-bit master key. A separate recovery credential contains 32 random bytes encoded as Base64. Each credential uses its own random 32-byte salt and PBKDF2-HMAC-SHA256 with 600,000 iterations to derive a wrapping key. CryptoKit AES-GCM wraps the master key, with associated data binding the purpose, version and vault UUID. The recovery credential does not reconstruct lost files.

The encrypted JSON index uses AES-GCM with a fresh random nonce and vault-specific associated data. Each original file derives its own key from the master key using HKDF-SHA256, the random file UUID as salt, and a versioned context string. Files use Apple's encrypted archive stream profile `hkdf_sha256_aesctr_hmac__symmetric__none`, with compression disabled. No custom cipher or chunk authentication scheme is implemented here.

The plaintext `header.json` contains format version, optional credential type (absent means legacy passphrase), vault UUID, credential salts, iteration counts and encrypted key envelopes. Vault names, filenames, types, lengths, digests, folders, dates, favorites and trash state are inside `index.sealed`. Directory count, ciphertext sizes and filesystem timestamps remain observable. Independent vaults provide separate access, not forensic deniability. A damaged credential envelope can prevent recovery through that credential even when the other remains usable.

## Files and commits

Imports calculate original length/SHA-256, encrypt to a new blob, then authenticate and verify the encrypted copy before atomically publishing its index entry. The source is never deleted. Note edits create a replacement blob before changing the index. Interrupted writes can leave unreachable encrypted blobs; they cannot make an incomplete import appear committed. Permanent deletion removes an index entry then its encrypted blob; this is ordinary filesystem deletion, not a flash-erasure guarantee. Backups and exported copies survive deletion.

Exports decrypt into a protected partial file and publish its final name only after whole-stream verification and digest/length checks. Encryption and decryption use bounded stream buffers, but previews/thumbnails require a complete temporary plaintext copy. Large-video startup and free-space requirements must be measured on devices. Notes and the index have explicit size limits.

Owned storage and temporary directories use iOS complete file protection and are excluded from OS backups. Temporary preview/export files are removed on dismissal, lock and next launch; removal at lock is best effort. Imported Photos/camera staging is removed after processing and on next launch. Swift strings, framework objects, system viewers and OS-managed copies do not provide a general secure-memory-erasure guarantee. A receiving app controls any exported copies it receives.

## Backups

`.vault7` is transport framing around the encrypted header/index/blob files: `7DVAULT1`, a big-endian UInt64 entry count, then repeated UInt64 UTF-8 name length, name bytes, UInt64 payload length and payload bytes. Only fixed metadata names and canonical UUID `.aea` names are accepted. Reject duplicate entries, traversal, unsupported versions, truncation, trailing bytes, unexpected payloads and oversized metadata.

Restore extracts into an isolated staging directory, unwraps the supplied credential, authenticates the index and every referenced original, then moves the verified directory into place. An existing UUID is never overwritten. The container framing is not separately cryptographically authenticated; the index and each original are. Verification cannot establish freshness, prevent rollback to an older valid backup, or authenticate an unused credential envelope. Password/recovery changes affect new backups; old backups retain their previous credentials.

## Automatic delivery

Snapshots are prepared with the unlocked vault key, verified and then queued as ciphertext in separate app-owned storage. There are no background encryption credentials. The snapshot path is outside plaintext preview cleanup so an already-queued backup can finish after locking. The queue and destination bookmarks use complete-until-first-user-authentication protection and are excluded from OS backups. Failed or interrupted preparation is retried after the next unlock; startup removes uncommitted local preparation artifacts.

Provider filenames contain an opaque vault UUID and revision hash of the encrypted header/index. Provider folder names/bookmarks and copy timestamps exist outside the encrypted vault, so enabling cloud backup adds observable metadata. The app offers no forensic concealment guarantee. The provider can observe ciphertext sizes and versions. Old provider versions keep old credentials; changing a secret is not remote revocation.

SevenBackup obtains scoped directory access and coordinates streamed copy, hash comparison and final rename. It never deliberately overwrites a conflicting version. Retention removes only successful versions tracked by this queue after a newer copy is recorded. The provider controls subsequent remote upload. OS termination can leave an uncommitted provider-side partial file; cancellation normally removes it. Source code and tests cannot establish live provider durability or guarantee iOS scheduling.

## Unlock and lifecycle

Optional biometric unlock stores the master key in Keychain with `whenPasscodeSetThisDeviceOnly` and `biometryCurrentSet`. It requires biometric authentication without an offered device-passcode fallback. Enrollment changes invalidate that convenience route; the independent credentials remain necessary.

The UI covers app windows on inactivity and discards its session on background or explicit lock. Generation checks prevent an in-flight operation from reopening a locked interface. An already executing storage operation may finish its encrypted commit before the actor discards its key. The app does not block screenshots, secure a compromised OS, or stop an observer of an unlocked session.

## Release review

The automated storage suite covers byte preservation, independent credentials, password/recovery changes, metadata/blob tampering, swapped/truncated files, malformed backup framing, restore without overwrite, notes and trash. It does not replace device tests, fault injection or an independent cryptographic/application review. No telemetry SDK, app account or app-operated backend is included. StoreKit communicates with Apple; choosing a Files provider or share destination invokes that provider's behavior.
