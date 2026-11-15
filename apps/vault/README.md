# Vault development

Native iOS 17+ private library. Read [SPEC.md](SPEC.md) for the product contract and [SECURITY.md](SECURITY.md) for storage and threat boundaries. This is an unreleased development app, not independently audited security software.

Open `Vault.xcodeproj` from this checkout. Its local package references require the repository's `SevenKit` directory. Public identifiers and artwork are deliberately generic; configure signing through ignored `Config/Local.xcconfig`. The shared scheme includes `Vault.storekit` for local purchase testing. Production products, branding, signing and export-compliance answers require release configuration.

Portable model tests on Linux:

```sh
./scripts/test-linux apps/vault/Kit
```

On macOS, run all model/storage tests (AppleArchive and CryptoKit are unavailable in the Linux test run):

```sh
swift test --package-path apps/vault/Kit -Xswiftc -warnings-as-errors
```

Build or run the shared `Vault` scheme with a specific simulator/device identifier. `VaultSmokeUITests` exercises creation, sample import, a note, lock, incorrect credentials, recovery, process restart, export after trial expiry, automatic backup/update/restore, PIN/pattern routing, and background locking. Its DEBUG-only `-vault.ui` switch uses isolated test storage and a local purchase source; adding `-vault.reset` deletes only that test library. Neither switch is compiled into Release.

Before release, exercise physical-device camera and biometric enrollment changes, large videos/libraries, interruptions and low storage, Files/cloud destinations, actual StoreKit transactions, accessibility and iPad layouts. Validate recovery from a separately saved backup. See the spec for capabilities explicitly outside this build.

Shared backup tests use the iOS Simulator SevenKit-Package scheme with `-only-testing:SevenBackupTests`. The encrypted queue is separate from plaintext preview staging. Select a backup folder per vault in Settings; see the automatic-backup section of the spec for timing, retention and provider limits.
