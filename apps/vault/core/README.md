# vault-core

Vault's shared logic in Rust, for the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a credential rule, an index field or a backup frame can't drift between platforms.

**Source:** ported from the iOS app's `Kit/Sources/VaultCore` and the pure parts of `Kit/Sources/VaultStorage` as committed on 15 November 2026.

**In the core:**

- Credentials (`VaultCredentialKind`): passphrase, PIN and pattern rules, their domain-separated key material, and 5×5 pattern canonicalization (`pattern_append`, `pattern_decode`), crossed dots included.
- The library index (`VaultLibrary`, `VaultItem`, `VaultFolder`, `VaultOrder`): JSON keys and defaults, validation in Swift's order, search (`localizedStandardContains`) and ordering (`localizedStandardCompare` for names).
- The repository's library edits without storage: import ordering, notes, rename, favorite, trash, move, folders, preferences, manual reorder, permanent delete, backup stamp.
- The format around the cryptography: `header.json` (`VaultHeader`, `KeyEnvelope`), the key-derivation parameters and their check, the AES-GCM associated-data strings, the HKDF salt and info for file keys, blob names, size limits (`vault_format`), and which envelope a credential unlocks (`unlock_route`).
- The `.vault7` backup framing: name rules, the writer's prefixes and an in-memory codec whose reader applies Swift's checks in Swift's order.

**Stays native:**

- All cryptography: PBKDF2-HMAC-SHA256 (CommonCrypto), AES-GCM key wrapping and index sealing, HKDF-SHA256 file keys and SHA-256 digests (CryptoKit), the Apple Encrypted Archive streams for originals (AppleArchive, profile `hkdf_sha256_aesctr_hmac__symmetric__none`), and randomness for master keys, salts and recovery keys (`SecRandomCopyBytes`).
- Keychain custody for biometric unlock, iOS file protection and backup exclusion, atomic writes, staging and directory moves, streamed backup reading and writing, temporary-file cleanup, the session actor and lock lifecycle, SevenBackup delivery, purchases and every pixel.

**Android's crypto, when it comes:** PBKDF2 from `pbkdf2` with `hmac` and `sha2`, AES-256-GCM from `aes-gcm`, HKDF from `hkdf`, SHA-256 from `sha2` (all RustCrypto, audited `aes-gcm`), randomness from `getrandom`, or the platform's `javax.crypto` and `SecureRandom`. The originals are the hard part: Apple Encrypted Archive has no Android or RustCrypto implementation, so Android needs a reimplementation of that profile (HKDF-SHA256, AES-CTR, HMAC per segment) checked against Apple-written files, or a new versioned blob format with a migration.

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
- `tests/golden.rs`: files and answers from Swift's real code.
  - The app encodes the index and header with a plain `JSONEncoder()`, whose key order is not fixed on swift-foundation, so those files cannot be byte for byte. Rust must read Swift's files back equal and re-encode the same JSON value (`swift_written_*_read_back_equal`), and Swift must read the files Rust writes (`rust_written_files_are_the_ones_swift_read`).
  - The `.vault7` framing is deterministic: Swift's container must come back byte for byte.
  - `behaviour-swift.json` holds Swift's answers for credential validation, key material, gesture canonicalization, names, digests, search and name order, and Rust must give the same ones.

The fixtures were made by `tests/swift-golden/main.swift`, built in `docker.io/library/swift:6.3` with copies of `Credential.swift`, `Library.swift`, `BackupContainer.swift` (CryptoKit guard removed, `ProtectedFiles.protect` stubbed) and the `KeyEnvelope`/`VaultHeader` structs from `Crypto.swift`. It writes the Swift fixtures to `out/`, and checks the Rust files in `rust/` (`tests/fixtures/rust/` plus the committed Swift container) read back equal. All salts and sealed boxes are byte patterns, not secrets. Name order and search were checked on swift-foundation (Linux, ICU), not on Apple's Foundation, and the Rust collation models ICU root order rather than bundling it, so uncommon scripts and symbols may still order differently on a phone.

Platform tests keep covering what stays native (encryption round trips, tampering, truncation, restore without overwrite, lock cleanup, UI).

## Known differences

- A malformed index or header is `VaultError::Damaged` here. Swift throws a `DecodingError` instead (its unlock loop does turn an unreadable header into `damaged`).
- Dates are held at `Date` precision (a Double of seconds since 2001) so a library equals itself after a round trip; nanoseconds from `SystemTime::now()` or a Kotlin `Instant` are rounded the same way.
