# Third-party work

Everything in this repository that someone else made, with its licence. `scripts/check-rules`
fails if a code dependency is added without a row here, without an exact version or revision, or
under a licence not on the allowed list (MIT, BSD-2-Clause, BSD-3-Clause, Apache-2.0, MPL-2.0, ISC,
Zlib, Unicode, GPL-3.0-compatible). Nothing that collects data or phones home is ever allowed.

## Code libraries

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| UniFFI | https://github.com/mozilla/uniffi-rs | MPL-2.0 | 0.32.2 | Alarm core: Swift and Kotlin bindings for the shared Rust logic |
| serde | https://github.com/serde-rs/serde | MIT OR Apache-2.0 | 1.0.229 | Alarm core: archive encoding |
| serde_json | https://github.com/serde-rs/json | MIT OR Apache-2.0 | 1.0.154 | Alarm core: JSON backups in the layout the Swift app wrote |
| uuid | https://github.com/uuid-rs/uuid | MIT OR Apache-2.0 | 1.29.2 | Alarm core: record identifiers |
| jiff | https://github.com/BurntSushi/jiff | Unlicense OR MIT | 0.2.43 | Alarm core: alarm times and daylight-saving rules in a named time zone |
| unicode-segmentation | https://github.com/unicode-rs/unicode-segmentation | MIT OR Apache-2.0 | 1.13.3 | Alarm core: character counts that match Swift's `String.count` |
| unicode-normalization | https://github.com/unicode-rs/unicode-normalization | MIT OR Apache-2.0 | 0.1.26 | Alarm core: mission answers compared as Swift compares strings |
| thiserror | https://github.com/dtolnay/thiserror | MIT OR Apache-2.0 | 2.0.21 | Alarm core: error types |

Their own dependencies are pinned in `apps/alarm/core/Cargo.lock`.

## Bundled data

| Name | URL | Licence | Version | Used for |
|---|---|---|---|---|
| IANA Time Zone Database, via jiff-tzdb | https://www.iana.org/time-zones | Public domain (jiff-tzdb: Unlicense OR MIT) | jiff-tzdb 0.1.9 | Alarm core: the same zone rules on iOS and Android |

