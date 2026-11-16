# alarm-core

Alarm & Loops' shared logic in Rust, used by the iOS and Android apps through [UniFFI](https://mozilla.github.io/uniffi-rs/). One implementation, so a rule, a schedule or a file format can't drift between platforms.

**In the core:** routines, time slots, receipts, history events and alarm defaults with their validation (same checks, same order, same messages), the JSON archive (byte-identical to what the Swift app's `BackupCodec` wrote), dormant merge-on-import, the CSV history export, the store's validate-encode-size step, schedule compilation (stable registration IDs, weekly slots, overnight windows, the 96-time limit) and occurrences across daylight-saving gaps and folds, the mission checks: arithmetic, typed phrase, scanned code, colour word, the memory sequence and the shake counter, and the delivery rules iOS keeps in its app model (`delivery.rs`: the registrations a device should hold with quiet reminders split per weekday and the 60-reminder limit, the next ring of a registration, snooze and wake-up-check registrations, receipt pruning, naps, duplicates, save checks and group names), which the Android app calls because it has no AlarmKit to repeat weekly alarms for it.

**Stays native:** the archive file and its atomic, publish-after-write store (`LoopRepository`, iOS data protection, Android `AtomicFile`), AlarmKit and Android alarm registration and reconciliation, notifications, sensors and the camera feeding the missions, purchases and every pixel.

Dates are stored as Foundation's default `JSONEncoder` writes them, seconds since 2001-01-01 as a double; dates made in Rust are rounded to that double (`as_swift_date`) so a round trip gives back an equal record. Time zones come from jiff's bundled IANA database on every platform. Occurrences take a `CalendarSpec` (zone identifier and Foundation-numbered first weekday). As with Swift's synthesized `Codable`, every non-optional key is required when decoding and nil optionals are left out when encoding.

## Build

```sh
scripts/rust-core alarm test       # Linux: Swift-parity suite + golden file
scripts/rust-core alarm bindings   # Kotlin + Swift sources into generated/
scripts/rust-core alarm android    # .so for four ABIs into generated/jniLibs
scripts/rust-core alarm apple      # alarm_coreFFI.xcframework, built in the macOS VM
```

**Where iOS picks it up.** The script puts the bindings in `Kit/Sources/AlarmCore/Generated/` and the library in `Kit/Generated/alarm_coreFFI.xcframework`; `AlarmCore` adds a thin façade (`Facade.swift`) that keeps the Swift names AlarmUI uses and turns the core's `LoopError` into the app's `LoopFailure` sentence. The archive file lives in `AlarmStore`, plain Swift that also builds on Linux: `LoopRepository` reads, calls `store_encode`, writes atomically and publishes only after the write.

Needs Rust (stable), `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`, `cargo install cargo-ndk`, an Android NDK, and in the VM `rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios`. `generated/` is a build output and is not committed.

## Tests

- `tests/swift_parity.rs`: every AlarmCore test from the iOS app, one to one, under the Swift names. `failedWriteDoesNotPublish` is store-only and stays with the native store; `corruptAndFutureFilesArePreserved` keeps its codec half here.
- `tests/delivery.rs`: the delivery rules against what iOS's `AlarmModel` does with the same inputs, including the next ring across daylight-saving gaps and folds.
- `tests/golden.rs`: an archive in the Swift encoder's exact layout (pretty-printed, sorted keys, escaped slashes, whole and fractional dates, every optional) must come back byte for byte; missing optional keys read, missing required keys don't.

Platform tests keep covering what stays native (store atomicity, corrupt-file preservation, alarm reconciliation, UI).
