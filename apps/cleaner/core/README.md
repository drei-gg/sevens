# cleaner-core

Cleaner's shared logic in Rust, for the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a review rule, a guard or a file format can't drift between platforms.

**Source:** ported from the iOS app's `Kit/Sources/CleanerCore` and the pure parts of `Kit/Sources/CleanerPlatform` as committed on 15 November 2026.

**In the core:**

- From CleanerCore: items and review decisions, exact-match groups, the keeper and default-selection rules (never a favorite, never a deliberate decision, never a similar-only group), storage totals that never count an unknown size as zero, compression assessment (which can never authorize deleting a source), contact records, candidate pairs (never transitive groups; switchboard identifiers make no pairs), merge plans and their blockers, the review state's changes, its file format and version gate, and the removal guard.
- From CleanerPlatform: file and photo revision strings (Swift's `Double` spelling, bit for bit), photo tags and resource fingerprints, largest-first ordering, the album-request guard, the similarity window and its suggestion text, PSNR arithmetic over RGBX tiles, the compression profile's source and pixel gates, email match keys, display names, contact field order and the contacts export package with its read-back check.
- For Android only (additive; iOS keeps Vision): a perceptual image hash (DCT pHash of a 32 × 32 thumbnail), its Hamming distance and the similar-pair suggestion it feeds into the same similarity window. Android has no feature-print API; the threshold is provisional, like Vision's.

**Stays native:** Photos (inventory, resource reads, deletion, saving copies, favorites, albums), Vision feature prints and their distances, Contacts fetching and `NSKeyedArchiver` archives (opaque bytes here), PhoneNumberKit normalization (Android uses libphonenumber), SHA-256 file hashing, the folder walk and security-scoped access, ImageIO encoding, pixel reads and metadata comparison, the atomic file writes, purchases and every pixel.

Swift `Set`s cross as lists, kept sorted and deduplicated. Strings compare as Swift's do, under canonical equivalence. Dates cross as `SystemTime` and are stored as Foundation writes them: seconds since 2001-01-01, as a double. A revision takes `timeIntervalSince1970` as the platform computed it, so its digits match what was stored.

Deliberate differences: where Swift's `Dictionary(uniqueKeysWithValues:)` would trap on a repeated item id, `matching_proposed_removal` takes the last and `validate_removal` blocks. Swift visits a dictionary in hash order, so a contact pair with both a shared email and a shared phone lists those reasons in either order; here email comes first. Decoding failures, which Swift throws as `DecodingError`, are `CleanerError::Unreadable`.

## Build

```sh
scripts/rust-core cleaner test       # Linux: Swift-parity suite, platform rules, golden files
scripts/rust-core cleaner bindings   # Kotlin + Swift sources into generated/
scripts/rust-core cleaner android    # .so for four ABIs into generated/jniLibs
scripts/rust-core cleaner apple      # cleaner_coreFFI.xcframework, built in the macOS VM
```

**Where iOS picks it up.** The script puts the bindings in `Kit/Sources/CleanerCore/Generated/` and the library in `Kit/Generated/cleaner_coreFFI.xcframework`. `CleanerCore` adds a thin façade (`Facade.swift`) that keeps the Swift names the app and CleanerPlatform use, and the native `ReviewStore` (the atomic file). CleanerPlatform calls the core for every pure rule above and keeps the platform work. On Linux only CleanerPlatform's phone keys build.

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/swift_parity.rs`: all 15 CleanerCore tests from the iOS app, one to one, under the Swift names.
- `tests/platform_logic.rs`: the rules ported from CleanerPlatform and the review store, line by line from the Swift, plus the Android perceptual hash. CleanerPlatform's own tests exercise PhoneNumberKit, Contacts, ImageIO and CryptoKit, which stay native.
- `tests/golden.rs`: the app writes with a plain `JSONEncoder()`, whose key order is hash-random, so byte identity with Swift is impossible. `swift-*.json` came from Swift 6.3's real encoder (`swiftc` in `docker.io/library/swift:6.3`, CleanerCore's types and the app's encoder settings); Rust must read each back equal to the same sample values and re-encode the same JSON value. `rust-*.json` are Rust's output for those samples, which Swift 6.3 decoded back equal to its own (`ReviewStore.read` included); a test keeps Rust's output identical to them. `swift-revisions.tsv` holds Swift's revision strings for 25 timestamps, including the fixed/exponential boundaries. To regenerate or re-check: copy CleanerCore's three Swift files next to `tests/swift-golden/main.swift`, run `podman run --rm -v <dir>:/w:Z -w /w docker.io/library/swift:6.3 bash -c 'swiftc -O *.swift -o /tmp/t && /tmp/t out'`, and `/tmp/t read <dir with rust-*.json>` for the other direction.

Platform tests keep covering what stays native (atomic writes, archives, encoders, phone parsing, UI).

## Adding the next Seven's core

1. `apps/<app>/core` with this `Cargo.toml` shape (`cdylib` + `staticlib`, the `uniffi-bindgen` bin) and a `uniffi.toml` naming the Kotlin package and Swift module, with UUIDs mapped to `java.util.UUID` / `UUID`.
2. Port the iOS core module by module; keep Swift's JSON keys and defaults (`serde` renames), its validation order and its error cases.
3. Port the iOS tests first-class into `tests/swift_parity.rs` and add golden files from Swift's real encoder. Byte identity only where the app encodes with `.sortedKeys`; otherwise read-back both ways. Green before any platform switches over.
4. Move geometry that both apps draw into spec functions (data in, strokes/layers out).
5. `scripts/rust-core <app> all`, then point each app at the bindings and delete its own copy of the logic.
