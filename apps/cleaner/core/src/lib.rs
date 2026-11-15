//! Cleaner's shared logic. The iOS CleanerCore (Swift) is the reference; this crate is the one
//! implementation both apps can call, through UniFFI bindings (`CleanerCoreFFI` in Swift,
//! `gg.seven.cleaner.core` in Kotlin). Photos, Contacts, Files, hashing, image coding and every
//! pixel stay native.
//!
//! Dates cross the boundary as `SystemTime` (Swift `Date`, Kotlin `java.time.Instant`) and are
//! stored in JSON as Foundation's default: seconds since 2001-01-01 as a double. Swift `Set`s
//! cross as lists; the core keeps them sorted and free of duplicates.

uniffi::setup_scaffolding!();

mod codec;
mod library;
mod platform;
mod preservation;
mod review;
mod text;

pub use library::*;
pub use platform::*;
pub use preservation::*;
pub use review::*;

pub use uuid::Uuid;

uniffi::custom_type!(Uuid, String, {
    remote,
    lower: |id| id.hyphenated().to_string().to_uppercase(),
    try_lift: |text| Ok(Uuid::parse_str(&text)?),
});

/// Swift's `CleanerError`, plus the decoding failure Swift reports as a `DecodingError`.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CleanerError {
    /// `CleanerError.blocked(reason)`: the reason is the user-facing message.
    #[error("{reason}")]
    Blocked { reason: String },
    /// The file is not valid JSON of the expected shape. Never treated as empty.
    #[error("This file cannot be read.")]
    Unreadable,
}

pub(crate) fn blocked(reason: &str) -> CleanerError {
    CleanerError::Blocked { reason: reason.to_string() }
}

/// The crate's version, so an app can log which core it runs.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
