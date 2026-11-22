# vault-core

Vault's shared logic in Rust, for the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a credential rule, an index field, a backup frame or, from format version 2, the encryption itself can't drift between platforms.

**Source:** ported from the iOS app's `Kit/Sources/VaultCore` and the pure parts of `Kit/Sources/VaultStorage` as committed on 15 November 2026; format version 2 added on 16 November 2026.

**In the core:**

- Credentials (`VaultCredentialKind`): passphrase, PIN and pattern rules, their domain-separated key material, and 5×5 pattern canonicalization (`pattern_append`, `pattern_decode`), crossed dots included.
- The library index (`VaultLibrary`, `VaultItem`, `VaultFolder`, `VaultOrder`): JSON keys and defaults, validation in Swift's order, search (`localizedStandardContains`) and ordering (`localizedStandardCompare` for names).
- The repository's library edits without storage: import ordering, notes, rename, favorite, trash, move, folders, preferences, manual reorder, permanent delete, backup stamp.
- Format version 1 (the iPhone's legacy files, readable and writable until a vault migrates): `header.json` (`VaultHeader`, `KeyEnvelope`), the key-derivation parameters and their check, the AES-GCM associated-data strings, the HKDF salt and info for file keys, blob names, size limits (`vault_format`), which envelope a credential unlocks (`unlock_route`) and the `.vault7` framing (`7DVAULT1`). Its cryptography stays native on iOS.
- Format version 2 (cross-platform, below): all of it, cryptography included, built on age.

**Stays native:** randomness for version 1 only, Keychain / Android Keystore custody for biometric unlock, file protection and backup exclusion, directory staging and moves, temporary-file cleanup, the session and lock lifecycle, automatic-backup delivery, document pickers, purchases and every pixel.

## Format version 2 (the cross-platform spec)

Version 2 is one encrypted format for every platform: an Android vault and an iPhone vault are the same files, and a `.vault7` backup from one restores on the other. It is built on [age](https://age-encryption.org/v1) (the C2SP specification `c2sp.org/age`) through the `age` crate (str4d/rage, MIT OR Apache-2.0), so the cryptography is a reviewed, specified format with independent implementations rather than our own composition of primitives. Nothing in this crate implements a cipher, a KDF or a MAC. Randomness is the operating system's CSPRNG (`getrandom`, through `rand::rngs::OsRng` and the `age` crate).

### Keys

- Each vault has a random **age X25519 identity** (`AGE-SECRET-KEY-1…`), generated once (`vault_identity_generate`). It is the vault's master key. Its public **recipient** (`age1…`) is stored in plain text in `header.json`.
- The identity is never written unsealed. It lives in two **envelopes**, each a complete age file encrypted with age's **scrypt recipient** (a passphrase stanza, `-> scrypt <salt> <log N>`):
  - `password`, over `credential_kind_material(kind, value)`, the same domain-separated material as version 1 (`Vault/PIN/v1/…`, `Vault/Pattern/5/v1/…`, a passphrase as typed), after the same credential rules (`credential_kind_validate`).
  - `recovery`, over the recovery key exactly as shown to the person: 32 random bytes, standard Base64 (`generate_recovery_key`), the same shape as version 1.
- An envelope's plaintext is a context line, a line feed, then the identity's secret (UTF-8, no trailing newline):

  ```text
  Vault/password/v2/<VAULT UUID>
  AGE-SECRET-KEY-1…
  ```

  The recovery envelope's context is `Vault/recovery/v2/<VAULT UUID>`.

  Opening checks that the context names this vault and this role, and that the identity's public key is the header's `recipient`. A wrong secret is `InvalidCredential`; an envelope copied from another vault (even one with the same passphrase), a recovery envelope moved into `password`, or an identity that is not the header's recipient is `Damaged`.
- **scrypt work factor:** new envelopes use log₂N = **17** (`SCRYPT_LOG_N`), with age's fixed r = 8, p = 1: 128 · N · r = **128 MiB** of memory per attempt; one envelope opens in about 0.5 s on a current desktop CPU (`cargo run --release --example scrypt_cost`), so expect roughly 1–2 s on a mid-range phone (not yet measured on a physical device). That is OWASP's minimum recommendation for scrypt (N = 2¹⁷, r = 8, p = 1), the most a low-memory phone can give one attempt without risking the process, and unlock tries one envelope per vault of the chosen kind, so the cost multiplies with the number of vaults. Opening accepts at most log₂N = **18** (`SCRYPT_MAX_LOG_N`, 256 MiB), refused before any work otherwise, so a crafted backup cannot ask a phone for gigabytes. A later version can raise the factor by rewrapping (below); the cap moves with it.
- **Why scrypt, not Argon2id:** age specifies exactly one passphrase KDF, scrypt, for its passphrase recipient. Using Argon2id would mean wrapping the identity ourselves (our own KDF-plus-AEAD composition), which is what building on an established format avoids; the envelopes are standard age files any age implementation opens with the material. Argon2id is the stronger memory-hard function per byte of memory, and the difference is acknowledged: short PINs and patterns remain guessable whichever KDF is used (the SPEC says so), and a passphrase's strength dominates. If age adopts an Argon2id stanza, a later format version can rewrap to it without touching content.

### Files in a vault directory

| File | Contents |
| --- | --- |
| `header.json` | JSON, at most 16,384 bytes: `version` (2), `id` (upper-case UUID), `recipient` (`age1…`), `credentialKind` (`passphrase`, `pin`, `pattern`; required), `password` and `recovery` (Base64 of the envelope age files). |
| `index.age` | An age file encrypted to `recipient` whose plaintext is a 32-byte **tag** followed by the library JSON (version 1's `VaultLibrary` coding, `library_encode`; `recoveryUnconfirmed: true` while the current recovery key awaits the person's confirmation, omitted otherwise). The tag is HMAC-SHA256 over exactly those JSON bytes, keyed by HKDF-SHA256 (no salt) from the identity's secret text `AGE-SECRET-KEY-1…` with info `Vault/index-mac/v2/<VAULT UUID>`. At most 32,000,000 bytes; opening decrypts, verifies the tag in constant time before decoding anything (missing or wrong: `Damaged`), then `library.id` must equal the header's `id`. |
| `<UUID>.age` | Each original, upper-case item UUID, age-encrypted to `recipient`, streamed file to file in 1 MiB chunks (age's STREAM: 64 KiB authenticated chunks, the last one marked final, so truncation and trailing bytes fail). |

- Decrypting verifies the plaintext's length and SHA-256 against the item's `bytes` and `digest`, so an original swapped for another item's (same vault) is `Damaged`; another vault's file does not decrypt at all.
- **Why the index carries a tag.** age gives confidentiality, not sender authentication, and the recipient is public in `header.json`. Without the tag, anyone who can write to a vault directory or a `.vault7` could encrypt their own index (and originals with matching digests) and it would open cleanly with the real identity. The tag can only be made with the identity, so the index is the vault's own; the index's size and SHA-256 for every item then bind the originals. Version 1 had this property through AES-GCM under the master key.
- **What needs the secret.** Originals need only the recipient to encrypt (`file_encrypt`, `file_encrypt_fd`, `data_encrypt`), so an import or capture already in flight can finish writing its original after the session locks. Writing the index (`index_seal`) needs the identity. An operation that starts unlocked takes its own copy with `VaultIdentity::fork()`, commits with it and drops it; locking wipes the session's identity immediately, the operation never hands its copy to the UI, and the UI is never repopulated by it. A new import still needs the decrypted index to append to, so it cannot start while locked.
- **Locking never waits.** Every operation copies the secret under a short lock and works on the copy (zeroized when dropped), so `wipe()` returns at once even during a multi-gigabyte export; the running operation finishes on its copy and every later use of the wiped identity is `Locked`.
- The unlocked identity crosses to the platforms only as `VaultIdentity`, an opaque UniFFI object. `wipe()` (call it on lock) drops the secret, which zeroizes it; so does releasing the object. Besides `fork()` (another opaque object), its one export, `export_for_custody()`, exists solely so the platform can wrap the secret with a hardware-held key for biometric unlock (Android Keystore / StrongBox through androidx.biometric; the iPhone Keychain); `vault_identity_from_custody` takes it back and checks it against the header's recipient.
- **Changing the unlock method or secret** replaces only the `password` envelope (`header_v2_rewrap_password`); a new recovery key replaces only `recovery` (`header_v2_rewrap_recovery`). The index and originals are untouched. Older backups keep the envelopes they were made with.
- Automatic backup's revision is SHA-256 (hex) over `header.json` then `index.age` (`vault_revision_v2`).

### Backup (`.vault7`, version 2)

The version 1 framing with a new magic: `7DVAULT2`, a big-endian UInt64 entry count, then per entry a UInt64 name length, the UTF-8 name, a UInt64 payload length and the payload. Entries are `header.json`, `index.age` and every item's `<UUID>.age`, in that order; payloads are the vault's files byte for byte (already authenticated ciphertext). `backup_v2_write` streams a vault directory to a file; `backup_v2_read` streams a backup into an empty staging directory, and `backup_v2_container_decode` is the same reader in memory. Both refuse, before writing anything further: any other magic (`Unsupported`), a version 1 backup (`7DVAULT1`: `OlderBackup`, "made by an older iPhone version; update Vault on the iPhone and back up again", never `Damaged`), fewer than 2 or more than 1,000,000 entries, a name that is empty, over 80 bytes, not UTF-8, not one of the three accepted forms or repeated, a payload past the end of the file, a header over 16,384 or an index over 32,000,000 bytes, trailing bytes, and a missing header or index (all `Damaged`). No path from a backup is used: entries are created only under their validated fixed names, with create-new semantics. The caller then opens the header and index, checks `backup_v2_names_match`, verifies every original, and only then publishes the directory. A version 1 reader (the iPhone today) refuses a version 2 backup as `Unsupported`.

### Errors

`VaultError` keeps Swift's cases in Swift's order; version 2 appends `OlderBackup` and `SecretInUse` (a restore whose unlock secret already opens another vault).

### Migrating a version 1 vault (iPhone)

The iPhone opens the version 1 vault with its own cryptography as today, then: `vault_identity_generate`; `header_v2_new` with the credential just used to unlock and a new `generate_recovery_key` (version 1's recovery key cannot be recovered from the vault, so the person must save the new one); `index_seal(library, identity)` the library; for each item, decrypt the AppleArchive original to a protected temporary file and `file_encrypt` it to `<UUID>.age` (then `file_verify`), in a staging directory published only when complete; then remove the version 1 files. Version 1 functions stay so older vaults and backups keep opening during and after the migration.

## Build

```sh
scripts/rust-core vault test       # Linux: Swift-parity suite + golden files
scripts/rust-core vault bindings   # Kotlin + Swift sources into generated/
scripts/rust-core vault android    # .so for four ABIs into generated/jniLibs
scripts/rust-core vault apple      # VaultCoreFFI.xcframework, built in the macOS VM
```

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/swift_parity.rs`: every VaultCore test from the iOS app, one to one, under the Swift names. Where a VaultStorage test also checks a rule this crate owns (framing, credential routing, library edits), that part is ported under the Swift name too.
- `tests/v2.rs`: the SPEC's verification gates on format version 2, against the real encryption: round-trip original bytes across chunk boundaries, wrong credentials and kinds, modified/truncated/extended ciphertext, randomized encryption, independent vaults and envelope swaps, interrupted writes, credential rotation without touching content, identity wipe, fork and custody, an index forged with only the recipient (with or without a tag, or tagged by another vault), an original planted with a forged index, lock during a 64 MiB decrypt returning at once, an in-flight commit after lock, header versions and limits, backup restore into clean storage, framing refusals (path traversal, duplicates, trailing data, truncation, oversize, old and foreign magic), trash and restore, revisions; and interoperability with the reference implementation through C2SP CCTV age test vectors made by Go age (`tests/fixtures/age/`: decrypting an X25519 file to its published payload hash, real scrypt stanzas, and a work factor of 2²³ refused before any work).
- `tests/golden.rs`: files and answers from Swift's real code.
  - The app encodes the index and header with a plain `JSONEncoder()`, whose key order is not fixed on swift-foundation, so those files cannot be byte for byte. Rust must read Swift's files back equal and re-encode the same JSON value (`swift_written_*_read_back_equal`), and Swift must read the files Rust writes (`rust_written_files_are_the_ones_swift_read`).
  - The `.vault7` framing is deterministic: Swift's container must come back byte for byte.
  - `behaviour-swift.json` holds Swift's answers for credential validation, key material, gesture canonicalization, names, digests, search and name order, and Rust must give the same ones.

The fixtures were made by `tests/swift-golden/main.swift`, built in `docker.io/library/swift:6.3` with copies of `Credential.swift`, `Library.swift`, `BackupContainer.swift` (CryptoKit guard removed, `ProtectedFiles.protect` stubbed) and the `KeyEnvelope`/`VaultHeader` structs from `Crypto.swift`. It writes the Swift fixtures to `out/`, and checks the Rust files in `rust/` (`tests/fixtures/rust/` plus the committed Swift container) read back equal. All salts and sealed boxes are byte patterns, not secrets. Name order and search were checked on swift-foundation (Linux, ICU), not on Apple's Foundation, and the Rust collation models ICU root order rather than bundling it, so uncommon scripts and symbols may still order differently on a phone.

Platform tests keep covering what stays native (version 1 encryption on iOS, storage staging, restore without overwrite, lock cleanup, biometric custody, UI).

## Known differences

- A malformed index or header is `VaultError::Damaged` here. Swift throws a `DecodingError` instead (its unlock loop does turn an unreadable header into `damaged`).
- Dates are held at `Date` precision (a Double of seconds since 2001) so a library equals itself after a round trip; nanoseconds from `SystemTime::now()` or a Kotlin `Instant` are rounded the same way.
