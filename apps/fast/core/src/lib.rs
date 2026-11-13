//! Fast's shared logic. The iOS FastCore (Swift) was the reference; this crate is now the one
//! implementation both apps call, through UniFFI bindings (`FastCoreFFI` in Swift,
//! `gg.seven.fast.core` in Kotlin). File storage, notifications and every pixel stay native.
//!
//! Dates cross the boundary as `SystemTime` (Swift `Date`, Kotlin `java.time.Instant`) and are
//! stored in JSON as milliseconds since 1970, as the Swift app always wrote them. Calendar maths
//! take a [`CalendarSpec`]: the device's time-zone identifier and first weekday.

uniffi::setup_scaffolding!();

mod archive;
mod arcs;
mod codec;
mod companion;
mod history;
mod liquid;
mod model;
mod reminders;
mod text;
mod time;
mod water;

pub use archive::*;
pub use arcs::*;
pub use codec::*;
pub use companion::*;
pub use history::*;
pub use liquid::*;
pub use model::*;
pub use reminders::*;
pub use time::*;
pub use water::*;

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
