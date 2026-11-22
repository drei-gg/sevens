//! QR's shared logic. The iOS QRCore (Swift) was the reference; this crate is the one
//! implementation both apps call, through UniFFI bindings (`QRCoreFFI` in Swift,
//! `gg.seven.qr.core` in Kotlin). Network fetches, code detection and generation, file storage,
//! purchases and every pixel stay native.
//!
//! Text is split, searched and compared the way Swift does it (by grapheme cluster, with canonical
//! equivalence; see [`text`]), so a check sees the same pieces of an attacker's string on both
//! platforms. Dates cross the boundary as `SystemTime` (Swift `Date`, Kotlin `Instant`).

uniffi::setup_scaffolding!();

pub mod analyzer;
pub mod batch;
pub mod checks;
pub mod contract;
pub mod data;
pub mod fields;
pub mod history;
pub mod json;
pub mod known;
pub mod make;
pub mod payments;
pub mod peek;
pub mod render;
pub mod structured;
pub mod text;
pub mod time;
pub mod url;

pub use analyzer::*;
pub use contract::*;
pub use data::*;
pub use fields::*;
pub use history::*;
pub use known::*;
pub use peek::{HtmlInput, HtmlSniff, PeekAnalyzer, peek_age_link, peek_available};
pub use render::*;
pub use url::{HostKind, PublicSuffixList, PublicSuffixMatch, WebAddress};

use std::sync::Arc;
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

/// An absolute http/https address, parsed as a browser would.
#[uniffi::export]
pub fn web_address_parse(text: String, data: Arc<BundledData>) -> Option<WebAddress> {
    WebAddress::parse(&text, &data.public_suffixes)
}

/// Text with no scheme whose host ends in a listed suffix, with https assumed.
#[uniffi::export]
pub fn web_address_parse_schemeless(text: String, data: Arc<BundledData>) -> Option<WebAddress> {
    WebAddress::parse_schemeless(&text, &data.public_suffixes)
}

/// A redirect `Location` or form `action` resolved against the page it came from.
#[uniffi::export]
pub fn web_address_resolve(
    reference: String,
    base: LinkInfo,
    data: Arc<BundledData>,
) -> Option<WebAddress> {
    WebAddress::resolve(&reference, &base, &data.public_suffixes)
}
