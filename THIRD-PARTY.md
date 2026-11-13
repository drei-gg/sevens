# Third-party work

Everything in this repository that someone else made, with its licence. `scripts/check-rules`
fails if a code dependency is added without a row here, without an exact version or revision, or
under a licence not on the allowed list (MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0, ISC,
Zlib, Unicode, GPL-3.0-compatible). Nothing that collects data or phones home is ever allowed.

## Code libraries

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| UniFFI | https://github.com/mozilla/uniffi-rs | MPL-2.0 | 0.32.2 | Fast core: Swift and Kotlin bindings for the shared Rust logic |
| serde | https://github.com/serde-rs/serde | MIT OR Apache-2.0 | 1.0.229 | Fast core: archive and Watch receipt encoding |
| serde_json | https://github.com/serde-rs/json | MIT OR Apache-2.0 | 1.0.154 | Fast core: JSON backups in the layout the Swift app wrote |
| uuid | https://github.com/uuid-rs/uuid | MIT OR Apache-2.0 | 1.29.2 | Fast core: record identifiers |
| jiff | https://github.com/BurntSushi/jiff | Unlicense OR MIT | 0.2.43 | Fast core: calendar days and daylight-saving rules in a named time zone |
| unicode-segmentation | https://github.com/unicode-rs/unicode-segmentation | MIT OR Apache-2.0 | 1.13.3 | Fast core: character counts that match Swift's `String.count` |
| unicode-normalization | https://github.com/unicode-rs/unicode-normalization | MIT OR Apache-2.0 | 0.1.26 | Fast core: accent-insensitive search |
| thiserror | https://github.com/dtolnay/thiserror | MIT OR Apache-2.0 | 2.0.21 | Fast core: error types |

Their own dependencies are pinned in `apps/fast/core/Cargo.lock`.

## Bundled data

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| IANA Time Zone Database, via jiff-tzdb | https://www.iana.org/time-zones | Public domain (jiff-tzdb: Unlicense OR MIT) | jiff-tzdb 0.1.9 | Fast core: the same zone rules on iOS and Android |


## Android Fast and shared kit

Versions are pinned in `android/buildSrc/build.gradle.kts` and module build files. AndroidX transitive versions are selected by the pinned Compose BOM; resolved dependency locks accompany the build. iOS Fast has no third-party dependencies to reuse. Native Apple facilities map to the Android platform or AndroidX; the fasting domain rules and JSON schema are Kotlin ports of FastCore.

| Dependency | Version | License / source | Purpose |
| --- | --- | --- | --- |
| Kotlin / standard library / compiler plugins | 2.2.21 | Apache-2.0, https://github.com/JetBrains/kotlin | Language and Compose compilation |
| kotlinx.serialization | 1.9.0 | Apache-2.0, https://github.com/Kotlin/kotlinx.serialization | Schema-compatible JSON; no handwritten parser |
| kotlinx.coroutines | resolved AndroidX dependency | Apache-2.0, https://github.com/Kotlin/kotlinx.coroutines | Lifecycle, state flows and serialized storage work |
| AndroidX Compose | BOM 2025.12.01 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Native Material controls, adaptive layouts, accessibility |
| AndroidX Activity / Lifecycle / Core | 1.12.2 / 2.9.4 / 1.17.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Lifecycle, document pickers, AtomicFile and notifications |
| Vico charts | 2.3.6 | Apache-2.0, https://github.com/patrykandpatrick/vico | Counterpart to Swift Charts; chart rendering, axes, scroll and zoom |
| Kizitonwose Calendar | 2.9.0 | MIT, https://github.com/kizitonwose/Calendar | Month and heatmap layout, paging and annotated history days |
| AndroidX Health Connect | 1.1.0 | Apache-2.0, https://android.googlesource.com/platform/frameworks/support | Explicit read-only weight import |
| Google Play Billing | 9.1.0 | Android SDK terms, https://developer.android.com/google/play/billing | Store variant only; platform-purchase exception in LICENSE-PLATFORM-EXCEPTION.md |
| JUnit / AndroidX Test | 4.13.2 / runner 1.7.0 / ext 1.3.0 | EPL-1.0 (JUnit, tests only); Apache-2.0 (AndroidX), https://github.com/junit-team/junit4 and https://github.com/android/android-test | Tests only |

MIT copyright and permission text and Apache-2.0 license text ship in the app's notices. The calendar's month layout/state and serialization's schema/default behavior were reviewed before integration. These libraries do not add app networking.

Google Play Billing is a platform SDK, not an open-source library. Its declared dependencies include Google transport and Play services; store builds may contact Google for billing and billing-related diagnostics. No journal, fasting or measurement records are passed to the billing adapter. No developer analytics, advertising SDK, backend or application network client is added. Do not claim that the store binary contains no SDK networking. Generic and personal builds have neither the billing module nor its transitive Google dependencies.
