# qr-core

QR's shared logic in Rust, used by the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a scam check, a payment decoder or a file format can't drift between platforms.

**In the core:** the classifier (which parser handles a scan) and every offline scam check with its verdict rule and reasons; known addresses; embedded links; payments (EMVCo TLV and CRC-16, EPC, Swiss QR-bill with QRR/SCOR, UPI, crypto URIs and bare addresses, IBAN mod-97, Bech32/Bech32m); Wi-Fi, vCard/MeCard, iCalendar, tel, SMS, mailto/MATMSG and geo parsers, and the encoder Make uses; the address parser (Punycode, Public Suffix List, IPv4/IPv6 hosts, compatibility folding, reference resolution); Peek's pure half (charset decoding, HTML entities and tag scanner, script-redirect detection, the page report, RDAP bootstrap and responses, domain age); Make's link compaction and tracking-tag removal; render data (module grid, quiet zones, WCAG contrast, the SVG writer, label-sheet layout); history items, search and CSV/JSON export; the `history.v1.json` and `known.v1.json` files (byte-identical to what the Swift app writes); the lists bundle's format, shape rules and "newer download wins" loading.

**Stays native:** network fetches (Peek's client with its redirect and TLS handling, RDAP lookups, the lists download), the lists bundle's Ed25519 signature check (CryptoKit in QRUI's `ListsUpdater`; Android will need an audited equivalent such as `ed25519-dalek`, checked before the bytes reach `lists_bundle_check`), camera and image code detection (Vision), code generation and drawing (CoreImage, PDF/PNG export), file storage (`ListsStore`, the history and known-address stores), StoreKit and every pixel.

Text is split, searched and compared the way Swift does it: by grapheme cluster, with canonical equivalence and Swift's context-free case mapping (`src/text.rs`). A separator carrying a combining mark (`@` + U+0301) is not a separator on either platform, so an attacker's string falls into the same pieces. The bundled lists are read at build time from the Swift package's own `Kit/Sources/QRCore/Data/`, and the tests read the Swift suite's fixtures from `Kit/Tests/QRCoreTests/Fixtures/`; nothing is copied. Time zones for iCalendar `TZID`s come from jiff's bundled IANA database.

## Build

```sh
scripts/rust-core qr test       # Linux: Swift-parity suites, differential dump, golden files
scripts/rust-core qr bindings   # Kotlin + Swift sources into generated/
scripts/rust-core qr android    # .so for four ABIs into generated/jniLibs
scripts/rust-core qr apple      # QRCoreFFI.xcframework, built in the macOS VM
```

**Where the iPhone app picks it up.** `scripts/rust-core qr apple` puts the bindings in `Kit/Sources/QRCore/Generated/` and the library in `Kit/Generated/qr_coreFFI.xcframework`; `QRCore` adds a thin façade (`Facade.swift`) that keeps the Swift names QRUI and the two share extensions use and only converts. The saved lists file is `QRFiles` (`ListsStore`, plain Swift); `BundledData.shared` hands its bytes to the core on first use. The History and known-address stores, networking, the signature check and drawing stay in QRUI.

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/{analyzer,checks,lists,payments,peek,render_make,structured,url,history_known}.rs`: every QRCore test the iOS app had before it switched to this core, one to one, under the Swift names (parameterised Swift tests loop inside one test). Fixture runners read the fixture files in `Kit/Tests/QRCoreTests/Fixtures/`. The two `ListsStore` tests stay with the platforms' stores (`QRFilesTests` on iOS); `QRCoreTests` now only runs every fixture and a Peek pair through the Swift façade.
- `tests/swift_analysis.rs`: `fixtures/swift-analysis.json` is what the real Swift QRCore returned for every fixture input, a set of adversarial extras and every Peek pair; the Rust core must return the same type, level, signals with their arguments, fields and embedded analyses.
- `tests/golden.rs`: history, known-address and history-export files the Swift encoders wrote (`.sortedKeys`) come back byte for byte, including fractional-second dates read and written as whole seconds. The lists bundle the app stores is the exact bytes `scripts/refresh-lists` publishes, so `fixtures/lists-refresh.json` (written by that script's serialiser) must round-trip byte for byte; Swift's own `JSONEncoder()` has unstable key order, so `fixtures/swift-encoder-lists.json` only has to read back equal.

The `swift-*.json` fixtures were generated in the `swift:6.3` Linux container by a scratch package that depends on `apps/qr/Kit` and calls the real `HistoryFile`, `KnownAddressesFile`, `HistoryExport`, `JSONEncoder`, `Analyzer` and `PeekAnalyzer`. That Swift code has since been removed from the app (it is in the history before the iOS switch), so these files are now fixed references.

## Adding the next Seven's core

1. `apps/<app>/core` with this `Cargo.toml` shape (`cdylib` + `staticlib`, the `uniffi-bindgen` bin) and a `uniffi.toml` naming the Kotlin package and Swift module, with UUIDs mapped to `java.util.UUID` / `UUID`.
2. Port the iOS core module by module; keep Swift's JSON keys and defaults, its validation order and its error cases. Where Swift works on `Character`s, use `text.rs`.
3. Port the iOS tests first-class, under the Swift names, reading the Swift suite's fixtures in place; add golden files the shipped code wrote and, for decision logic, a differential dump from the real Swift implementation. Green before any platform switches over.
4. Move geometry that both apps draw into spec functions (data in, strokes/layers out).
5. `scripts/rust-core <app> all`, then point each app at the bindings and delete its own copy of the logic.
