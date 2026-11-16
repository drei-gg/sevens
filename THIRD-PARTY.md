# Third-party work

Everything in this repository that someone else made, with its licence. `scripts/check-rules`
fails if a code dependency is added without a row here, without an exact version or revision, or
under a licence not on the allowed list (MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0, ISC,
Zlib, Unicode, GPL-3.0-compatible). Nothing that collects data or phones home is ever allowed.

## Code libraries

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| UniFFI | https://github.com/mozilla/uniffi-rs | MPL-2.0 | 0.32.2 | Rust cores (Fast, QR, Alarm, Scanner, Cleaner, Vault): Swift and Kotlin bindings for the shared logic |
| serde | https://github.com/serde-rs/serde | MIT OR Apache-2.0 | 1.0.229 | Rust cores: archives, backups, history and Fast's Watch receipts |
| serde_json | https://github.com/serde-rs/json | MIT OR Apache-2.0 | 1.0.154 | Rust cores: JSON in the layout each Swift app writes |
| uuid | https://github.com/uuid-rs/uuid | MIT OR Apache-2.0 | 1.29.2 | Rust cores: record identifiers |
| jiff | https://github.com/BurntSushi/jiff | Unlicense OR MIT | 0.2.43 | Fast, QR and Alarm cores: calendar days, alarm times, event dates and daylight-saving rules in a named time zone |
| unicode-segmentation | https://github.com/unicode-rs/unicode-segmentation | MIT OR Apache-2.0 | 1.13.3 | Rust cores: character counts that match Swift's `String.count` |
| unicode-normalization | https://github.com/unicode-rs/unicode-normalization | MIT OR Apache-2.0 | 0.1.26 | Rust cores: accent-insensitive search and string comparison as Swift does it |
| unicode-general-category | https://github.com/yeslogic/unicode-general-category | Apache-2.0 | 1.1.0 | QR core: letter and mark classes for look-alike and mixed-script checks |
| base64 | https://github.com/marshallpierce/rust-base64 | MIT OR Apache-2.0 | 0.22.1 | QR, Cleaner and Vault cores: binary fields in JSON, as Swift's `Data` encodes them |
| caseless | https://github.com/unicode-rs/rust-caseless | MIT | 0.2.2 | Vault core: Unicode case folding for search and name order |
| PhoneNumberKit | https://github.com/PhoneNumberKit/PhoneNumberKit | MIT | 5.0.14 (ff40a615c71bf3e1785562c86f7e38bdea29660a) | Contact phone candidate normalization; bundled libphonenumber metadata, no network client |
| thiserror | https://github.com/dtolnay/thiserror | MIT OR Apache-2.0 | 2.0.21 | Rust cores: error types |
| age | https://github.com/str4d/rage | MIT OR Apache-2.0 | 0.12.1 | Vault core, format version 2: the age file format (age-encryption.org/v1) for vault envelopes, the encrypted index and every original, on iPhone and Android |
| age-core | https://github.com/str4d/rage | MIT OR Apache-2.0 | 0.12.0 | Vault core: age's stanza and file-key primitives (a dependency of age) |
| scrypt | https://github.com/RustCrypto/password-hashes | MIT OR Apache-2.0 | 0.11.0 | Vault core: age's passphrase key derivation (through age) |
| x25519-dalek / curve25519-dalek | https://github.com/dalek-cryptography/curve25519-dalek | BSD-3-Clause | 2.0.1 / 4.1.3 | Vault core: age's X25519 recipients and identities (through age) |
| chacha20poly1305 | https://github.com/RustCrypto/AEADs | Apache-2.0 OR MIT | 0.10.1 | Vault core: age's payload and stanza encryption (through age) |
| hkdf / hmac / sha2 | https://github.com/RustCrypto/KDFs | MIT OR Apache-2.0 | 0.12.4 / 0.12.1 / 0.10.9 | Vault core: age's key schedule and header MAC; SHA-256 digests of originals and backup revisions (https://github.com/RustCrypto/MACs, https://github.com/RustCrypto/hashes) |
| bech32 | https://github.com/rust-bitcoin/rust-bech32 | MIT | 0.11.1 | Vault core: age's `age1…` / `AGE-SECRET-KEY-1…` key encoding (through age) |
| rand / getrandom | https://github.com/rust-random/rand | MIT OR Apache-2.0 | 0.8.8 / 0.2.17 | Vault core: the operating system's random numbers for identities, salts and recovery keys (https://github.com/rust-random/getrandom) |
| zeroize / secrecy | https://github.com/RustCrypto/utils | Apache-2.0 OR MIT | 1.9.1 / 0.10.3 | Vault core: wiping keys and credentials from memory (https://github.com/iqlusioninc/crates) |
| i18n-embed / fluent / rust-embed | https://github.com/kellpossible/cargo-i18n | MIT (fluent: Apache-2.0 OR MIT) | 0.16.0 / 0.17.0 / 8.14.0 | Vault core: age's localized error messages (through age; https://github.com/projectfluent/fluent-rs, https://github.com/pyrossh/rust-embed) |

Their own dependencies are pinned in each core's `Cargo.lock` (`apps/<app>/core/Cargo.lock`).

Vault format version 1 (the iPhone app today) uses the operating-system CryptoKit, CommonCrypto, Security/Keychain and AppleArchive frameworks, with PhotosUI, ImageIO and Quick Look. No additional third-party code is bundled. AppleArchive provides the authenticated streaming file format; Vault does not implement its own cipher. Format version 2 (cross-platform, Android first) uses the age crates above, inside the Vault core; again no cipher of our own.

## Bundled data

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| IANA Time Zone Database, via jiff-tzdb | https://www.iana.org/time-zones | Public domain (jiff-tzdb: Unlicense OR MIT) | jiff-tzdb 0.1.9 | Fast, QR and Alarm cores: the same zone rules on iOS and Android |
| Public Suffix List | https://publicsuffix.org/list/public_suffix_list.dat | MPL-2.0 | as dated in `lists-version.json` | Finding a link's registrable domain (`apps/qr/Kit/Sources/QRCore/Data/public_suffix_list.dat`) |
| IANA RDAP bootstrap for DNS | https://data.iana.org/rdap/dns.json | IANA public data (no licence restrictions stated) | as dated in the file's `publication` field | Finding the registry to ask a domain's age (`rdap-dns.json`) |
| Unicode confusables (UTS #39), curated subset | https://www.unicode.org/Public/security/latest/confusables.txt | Unicode | 18.0.0 | Look-alike letters in addresses (`confusables.json`) |
| Libphonenumber metadata | https://github.com/google/libphonenumber | Apache-2.0 | metadata 9.0.43, as bundled in PhoneNumberKit 5.0.14 | International phone numbering plans, transformed XML to JSON by PhoneNumberKit |
| C2SP CCTV age test vectors (`x25519`, `scrypt`, `scrypt_work_factor_23`) | https://github.com/C2SP/CCTV/tree/main/age | Licence permits inclusion without attribution (credited anyway) | main, 16 November 2026 | Vault core tests only: files made by the reference Go age implementation (`apps/vault/core/tests/fixtures/age/`) |

Where each data entry comes from is recorded in `apps/qr/Kit/Sources/QRCore/Data/DATA-SOURCES.md`.


## Android Fast and shared kit

Versions are pinned in `android/buildSrc/build.gradle.kts` and module build files. AndroidX transitive versions are selected by the pinned Compose BOM; resolved dependency locks accompany the build. iOS Fast has no third-party dependencies to reuse. Native Apple facilities map to the Android platform or AndroidX; the fasting rules and JSON schema come from the shared Rust core (apps/fast/core), reached through UniFFI's generated Kotlin and JNA.

| Dependency | Version | License / source | Purpose |
| --- | --- | --- | --- |
| Kotlin / standard library / compiler plugins | 2.2.21 | Apache-2.0, https://github.com/JetBrains/kotlin | Language and Compose compilation |
| JNA | 5.17.0 | Apache-2.0 (dual-licensed with LGPL-2.1), https://github.com/java-native-access/jna | Calls into the Rust core from Kotlin (UniFFI's runtime) |
| AndroidX Compose Material 3 | 1.5.0-rc01 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Material 3 Expressive components on the phone (pinned above the Compose BOM, which still selects 1.4) |
| AndroidX Glance app widget / Glance Material 3 | 1.2.0 / 1.2.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Home-screen timer widget in the app's colours |
| kotlinx.coroutines | resolved AndroidX dependency | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines | Lifecycle, state flows and serialized storage work |
| AndroidX Compose | BOM 2025.12.01 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Native Material controls, adaptive layouts, accessibility |
| AndroidX Activity / Lifecycle / Core | 1.12.2 / 2.9.4 / 1.17.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Lifecycle, document pickers, AtomicFile and notifications |
| Vico charts | 2.3.6 | Apache-2.0, https://github.com/patrykandpatrick/vico | Counterpart to Swift Charts; chart rendering, axes, scroll and zoom |
| Kizitonwose Calendar | 2.9.0 | MIT, https://github.com/kizitonwose/Calendar | Month and heatmap layout, paging and annotated history days |
| AndroidX Health Connect | 1.1.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Explicit read-only weight import |
| Google Play Billing | 9.1.0 | Android SDK terms, https://developer.android.com/google/play/billing | Store variant only; platform-purchase exception in LICENSE-PLATFORM-EXCEPTION.md |
| kotlinx.serialization JSON | 1.9.0 | Apache-2.0, https://github.com/Kotlin/kotlinx.serialization | Wear OS wire protocol codec (`apps/fast/wear/wire`), shared by the watch and the phone's personal/store flavors |
| AndroidX Wear Compose Material 3 / Foundation | 1.7.1 / 1.7.1 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Wear OS app screens |
| AndroidX Wear Tiles / ProtoLayout (expression, Material 3) | 1.6.2 / 1.4.2 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Wear OS timer tile with a live elapsed count |
| AndroidX Wear watch face complications data source (ktx) | 1.3.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Wear OS elapsed-time / goal complication |
| AndroidX Wear Ongoing | 1.1.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Wear OS ongoing activity while a fast runs |
| AndroidX Concurrent Futures | 1.3.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | The tile service's future |
| AndroidX Startup | 1.1.1 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Personal/store phone: starts Wear snapshot publishing at process start |
| Google Play services Wearable | 20.0.1 | Android SDK terms, https://developers.google.com/android/guides/overview | Wear OS Data Layer between watch and phone: the Wear app and the personal/store phone flavors only |
| JUnit / AndroidX Test | 4.13.2 / runner 1.7.0 / ext 1.3.0 | EPL-1.0 (JUnit, tests only); Apache-2.0 (AndroidX), https://github.com/junit-team/junit4 and https://github.com/android/android-test | Tests only |

MIT copyright and permission text and Apache-2.0 license text ship in the app's notices. The calendar's month layout/state and serialization's schema/default behavior were reviewed before integration. These libraries do not add app networking.

Google Play services Wearable (the Wear OS Data Layer, the counterpart of Apple's WatchConnectivity) is likewise a platform SDK, not an open-source library. It is in the Wear OS app and in the phone's personal and store flavors only; the generic phone flavor has neither it nor its Google Play services dependencies (`:fast:dependencies --configuration genericReleaseRuntimeClasspath` shows no `play-services`). Only the timer snapshot (revision, start, goal, today's water total, whether records can be added) and the watch's start / end / add-water requests cross it, device to device through Google Play services; journal, weight and reflections never do. It adds no app networking, analytics or backend.

Google Play Billing is a platform SDK, not an open-source library. Its declared dependencies include Google transport and Play services; store builds may contact Google for billing and billing-related diagnostics. No journal, fasting or measurement records are passed to the billing adapter. No developer analytics, advertising SDK, backend or application network client is added. Do not claim that the store binary contains no SDK networking. Generic and personal builds do not have the billing module; generic builds have no Google Play services dependency at all (personal has the Wear OS Data Layer, above).

## Android Vault

Versions are pinned in `apps/vault/android/build.gradle.kts`; resolved versions are locked in its `gradle.lockfile`. The same libraries in every flavor (no Google Play services). Vault's encryption is the Rust core's (age, above); these libraries are platform plumbing and viewers. Their Apache-2.0 text, and the Rust crates' BSD and MIT notices (`Vault-core-crates.txt`), ship in the app's notices.

| Name | Version | Licence, URL | Used for |
|---|---|---|---|
| AndroidX Biometric | 1.1.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | BiometricPrompt with a Keystore crypto object for the opt-in biometric unlock |
| AndroidX Fragment | 1.9.1 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | FragmentActivity, which hosts BiometricPrompt |
| AndroidX WorkManager | 2.12.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Delivery opportunities for prepared automatic backups (iOS BGProcessingTask) |
| AndroidX DocumentFile | 1.1.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Writing backups and exports into a chosen Storage Access Framework folder |
| AndroidX ExifInterface | 1.4.1 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Upright thumbnails and previews before Android 9 |
| AndroidX CameraX (core, camera2, lifecycle, view) | 1.6.2 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Taking a photo inside the app, straight into the vault, never to MediaStore |
| AndroidX Media3 ExoPlayer / UI | 1.11.1 | Apache-2.0, https://github.com/androidx/media | Video and audio previews from the temporary decrypted copy |
| Telephoto zoomable | 0.19.0 | Apache-2.0, https://github.com/saket/telephoto | Pinch-zoom and pan in image previews |
| org.json | 20250517 | Public domain (JSON License removed; https://github.com/stleary/JSON-java) | Unit tests only: Android's `org.json` on the JVM |

