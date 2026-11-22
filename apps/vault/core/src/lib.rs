//! Vault's shared logic. The iOS VaultCore (Swift) and the pure parts of VaultStorage were the
//! reference; this crate is the one implementation both apps can call, through UniFFI bindings
//! (`VaultCoreFFI` in Swift, `gg.seven.vault.core` in Kotlin). Format version 1's encryption
//! stays native on iOS; format version 2 ([`v2`]) is encrypted here, with age. Keychain/Keystore
//! custody, file protection and every pixel stay native.
//!
//! Dates cross the boundary as `SystemTime` (Swift `Date`, Kotlin `java.time.Instant`) and are
//! stored in JSON as seconds since 2001-01-01 (Foundation's default `Date` coding), as the Swift
//! app writes them. UUIDs are written upper-case, as `uuidString`.

uniffi::setup_scaffolding!();

mod container;
mod credential;
mod error;
mod format;
mod library;
mod mutation;
mod recognition;
mod search;
mod text;
mod time;
mod v2;

pub use container::*;
pub use credential::*;
pub use error::*;
pub use format::*;
pub use library::*;
pub use mutation::*;
pub use recognition::*;
pub use search::*;
pub use text::standard_compare;
pub use v2::*;

pub use uuid::Uuid;

uniffi::custom_type!(Uuid, String, {
    remote,
    lower: |id| id.hyphenated().to_string().to_uppercase(),
    try_lift: |text| Ok(Uuid::parse_str(&text)?),
});

/// The crate's version, so an app can log which core it runs.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
