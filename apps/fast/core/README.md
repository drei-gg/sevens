# fast-core

Fast's shared logic in Rust, used by the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a rule, a calculation or a file format can't drift between platforms.

**In the core:** records and settings, validation, the JSON archive (byte-identical to what the Swift app wrote), merge-on-import, CSV export, calendar days and period summaries, water totals and reminder hours, which reminders should exist, the phone's side of the Watch protocol, the liquid simulation and its outline, and visual specs: `water_layers` (the glass's laps) and `week_strokes` (the calendar's continuous loops).

**Stays native:** file storage (iOS data protection, Android `AtomicFile`), notifications, Health, purchases, the Watch's wire codec (Swift on both ends) and every pixel. Platforms turn the core's specs into paths with their own drawing APIs.

Time zones come from jiff's bundled IANA database on every platform, so a day's boundaries never depend on the phone's tzdata. Calendar maths take a `CalendarSpec` (zone identifier and Foundation-numbered first weekday).

## Build

```sh
scripts/rust-core fast test       # Linux: Swift-parity suite + golden files
scripts/rust-core fast bindings   # Kotlin + Swift sources into generated/
scripts/rust-core fast android    # .so for four ABIs into generated/jniLibs
scripts/rust-core fast apple      # FastCoreFFI.xcframework, built in the macOS VM
```

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/swift_parity.rs`: every FastCore test from the iOS app, one to one, under the Swift names.
- `tests/golden.rs`: a file the Swift app wrote must come back byte for byte; the Kotlin port's file must read with every field.

Platform tests keep covering what stays native (store atomicity, corrupt-file handling, UI).

## Adding the next Seven's core

1. `apps/<app>/core` with this `Cargo.toml` shape (`cdylib` + `staticlib`, the `uniffi-bindgen` bin) and a `uniffi.toml` naming the Kotlin package and Swift module, with UUIDs mapped to `java.util.UUID` / `UUID`.
2. Port the iOS core module by module; keep Swift's JSON keys and defaults (`serde` renames), its validation order and its error cases.
3. Port the iOS tests first-class into `tests/swift_parity.rs` and add golden files the shipped app wrote. Green before any platform switches over.
4. Move geometry that both apps draw into spec functions (data in, strokes/layers out).
5. `scripts/rust-core <app> all`, then point each app at the bindings and delete its own copy of the logic.
