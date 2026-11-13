# Fast Android

Follow the shared contract in `../../../android/AGENTS.md` (repository path `android/AGENTS.md`). Fast's logic is the Rust crate `apps/fast/core` (`scripts/rust-core fast test`); the Swift FastUI implementation is the reference for screens. The Android port uses `android/core` (the crate's generated Kotlin bindings and a thin façade), `android/sevenkit`, and the optional store-only `android/billing` module. Build through `scripts/android`; run pure tests through `:core:test` and native tests through `:fast:connectedGenericDebugAndroidTest`.
