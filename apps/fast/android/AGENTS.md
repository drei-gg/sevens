# Fast Android

Follow the shared contract in `../../../android/AGENTS.md` (repository path `android/AGENTS.md`). The Swift FastCore/FastUI implementation is authoritative for product behavior. The Android port uses `android/core`, `android/sevenkit`, and the optional store-only `android/billing` module. Build through `scripts/android`; run pure tests through `:core:test` and native tests through `:fast:connectedGenericDebugAndroidTest`.
