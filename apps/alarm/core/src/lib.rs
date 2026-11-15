//! Alarm & Loops' shared logic. The iOS AlarmCore (Swift) is the reference; this crate is the one
//! implementation both apps call, through UniFFI bindings (`AlarmCoreFFI` in Swift,
//! `gg.seven.alarm.core` in Kotlin). File storage, AlarmKit, notifications and every pixel stay
//! native.
//!
//! Dates cross the boundary as `SystemTime` (Swift `Date`, Kotlin `java.time.Instant`) and are
//! stored in JSON as seconds since 2001-01-01, Foundation's default `Date` encoding, which the
//! Swift app's plain `JSONEncoder` always wrote. Calendar maths take a [`CalendarSpec`]: the
//! device's time-zone identifier and first weekday.

uniffi::setup_scaffolding!();

mod challenges;
mod codec;
mod model;
mod schedule;
mod text;
mod time;

pub use challenges::*;
pub use codec::*;
pub use model::*;
pub use schedule::*;
pub use time::*;

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
