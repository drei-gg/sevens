//! Instants, Foundation's JSON dates and wall-clock times in a named time zone.

use jiff::{Timestamp, tz::TimeZone};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The calendar a schedule is read in: an IANA time-zone identifier
/// (`TimeZone.current.identifier`, `ZoneId.systemDefault().id`) and the first weekday, 1 Sunday …
/// 7 Saturday (Foundation's `Calendar.firstWeekday` numbering). Occurrences don't depend on the
/// first weekday; it is carried so every Seven's core takes the same calendar.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarSpec {
    pub time_zone_id: String,
    pub first_weekday: i32,
}

/// Seconds from 1970 to Foundation's reference date, 2001-01-01T00:00:00Z.
const REFERENCE_OFFSET: i64 = 978_307_200;

/// `Date.timeIntervalSinceReferenceDate`: the `Double` a Swift `Date` is, and what a default
/// `JSONEncoder` writes.
pub(crate) fn reference_seconds(t: SystemTime) -> f64 {
    let (secs, nanos) = match t.duration_since(UNIX_EPOCH) {
        Ok(d) => (d.as_secs() as i64, d.subsec_nanos()),
        Err(e) => {
            let d = e.duration();
            if d.subsec_nanos() == 0 { (-(d.as_secs() as i64), 0) } else { (-(d.as_secs() as i64) - 1, 1_000_000_000 - d.subsec_nanos()) }
        }
    };
    // Whole seconds are exact; the fraction is added once, so the nearest double comes back.
    (secs - REFERENCE_OFFSET) as f64 + nanos as f64 / 1e9
}

pub(crate) fn from_reference_seconds(seconds: f64) -> SystemTime {
    let whole = seconds.floor();
    let mut secs = whole as i64 + REFERENCE_OFFSET;
    let mut nanos = ((seconds - whole) * 1e9).round() as u32;
    if nanos >= 1_000_000_000 {
        nanos -= 1_000_000_000;
        secs += 1;
    }
    if secs >= 0 {
        UNIX_EPOCH + Duration::new(secs as u64, nanos)
    } else {
        UNIX_EPOCH - Duration::from_secs((-secs) as u64) + Duration::from_nanos(nanos as u64)
    }
}

/// `t` as a Swift `Date` holds it: a `Double` of seconds since 2001. Values made in Rust go
/// through this so a JSON round trip gives back an equal record, as it does in Swift.
pub fn as_swift_date(t: SystemTime) -> SystemTime {
    from_reference_seconds(reference_seconds(t))
}

/// The zone from the bundled IANA database (`seven-schedule`), the same on every platform, so a
/// wall-clock time never depends on which tzdata a phone happens to ship.
pub(crate) fn zone(spec: &CalendarSpec) -> TimeZone {
    seven_schedule::zone(&spec.time_zone_id)
}

pub(crate) fn timestamp(t: SystemTime) -> Option<Timestamp> {
    Timestamp::try_from(t).ok()
}

pub(crate) fn system_time(ts: Timestamp) -> SystemTime {
    SystemTime::from(ts)
}

/// Whether the bundled database knows `identifier`.
#[uniffi::export]
pub fn time_zone_known(identifier: String) -> bool {
    seven_schedule::zone_known(&identifier)
}

/// Serde: `SystemTime` as Foundation's default `Date` encoding, seconds since 2001.
pub(crate) mod refdate {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &SystemTime, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_f64(super::reference_seconds(*t))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<SystemTime, D::Error> {
        let seconds = f64::deserialize(d)?;
        if !seconds.is_finite() {
            return Err(serde::de::Error::custom("date is not finite"));
        }
        Ok(super::from_reference_seconds(seconds))
    }
}

pub(crate) mod refdate_opt {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &Option<SystemTime>, s: S) -> Result<S::Ok, S::Error> {
        match t {
            Some(t) => super::refdate::serialize(t, s),
            None => s.serialize_none(),
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<SystemTime>, D::Error> {
        match Option::<f64>::deserialize(d)? {
            Some(seconds) if !seconds.is_finite() => Err(serde::de::Error::custom("date is not finite")),
            Some(seconds) => Ok(Some(super::from_reference_seconds(seconds))),
            None => Ok(None),
        }
    }
}

/// Serde: UUIDs upper-case, as Foundation writes `uuidString`. Either case is read.
pub(crate) mod upper {
    use serde::{Deserialize, Deserializer, Serializer};
    use uuid::Uuid;
    pub fn serialize<S: Serializer>(id: &Uuid, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&id.hyphenated().to_string().to_uppercase())
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Uuid, D::Error> {
        let text = String::deserialize(d)?;
        Uuid::parse_str(&text).map_err(serde::de::Error::custom)
    }
}

pub(crate) mod upper_opt {
    use serde::{Deserialize, Deserializer, Serializer};
    use uuid::Uuid;
    pub fn serialize<S: Serializer>(id: &Option<Uuid>, s: S) -> Result<S::Ok, S::Error> {
        match id {
            Some(id) => super::upper::serialize(id, s),
            None => s.serialize_none(),
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Uuid>, D::Error> {
        match Option::<String>::deserialize(d)? {
            Some(text) => Uuid::parse_str(&text).map(Some).map_err(serde::de::Error::custom),
            None => Ok(None),
        }
    }
}

pub(crate) fn uuid_string(id: &uuid::Uuid) -> String {
    seven_schedule::uuid_string(id)
}
