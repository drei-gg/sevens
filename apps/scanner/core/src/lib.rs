//! Document Scanner's shared logic. The iOS ScannerCore (Swift) was the reference; this crate is
//! the one implementation both apps call, through UniFFI bindings (`ScannerCoreFFI` in Swift,
//! `gg.seven.scanner.core` in Kotlin). File storage, images, PDF rendering, scanning and OCR stay
//! native; the store asks the core for every rule it applies.
//!
//! `ScanDocument.created` is a Foundation reference-date interval (seconds since 2001-01-01 UTC),
//! which is what Swift's `Date` holds and what its default `JSONEncoder` writes. Keeping that
//! double, rather than converting through a timestamp type, is what makes manifests byte-identical.

uniffi::setup_scaffolding!();

mod backup;
mod documents;
mod export;
mod json;
mod settings;
mod text;

pub use backup::*;
pub use documents::*;
pub use export::*;
pub use settings::*;

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
