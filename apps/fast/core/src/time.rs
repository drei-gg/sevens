//! Instants, JSON milliseconds and calendar days in a named time zone.

use jiff::{Timestamp, ToSpan, Zoned, civil::Weekday, tz::TimeZone};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The calendar a view uses: an IANA time-zone identifier (`TimeZone.current.identifier`,
/// `ZoneId.systemDefault().id`) and the first weekday, 1 Sunday … 7 Saturday (Foundation's
/// `Calendar.firstWeekday` numbering).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CalendarSpec {
    pub time_zone_id: String,
    pub first_weekday: i32,
}

/// Milliseconds since 1970 as JSON stores them. Fractions are kept.
pub fn millis(t: SystemTime) -> f64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_secs_f64() * 1_000.0,
        Err(e) => -e.duration().as_secs_f64() * 1_000.0,
    }
}

pub fn from_millis(ms: f64) -> SystemTime {
    let nanos = (ms * 1_000_000.0).round();
    if nanos >= 0.0 {
        UNIX_EPOCH + Duration::from_nanos(nanos as u64)
    } else {
        UNIX_EPOCH - Duration::from_nanos((-nanos) as u64)
    }
}

pub fn seconds_between(later: SystemTime, earlier: SystemTime) -> f64 {
    (millis(later) - millis(earlier)) / 1_000.0
}

pub fn add_seconds(t: SystemTime, seconds: f64) -> SystemTime {
    from_millis(millis(t) + seconds * 1_000.0)
}

/// The bundled IANA database, the same on every platform, so a day's boundaries never depend
/// on which tzdata a phone happens to ship.
fn database() -> &'static jiff::tz::TimeZoneDatabase {
    static DB: std::sync::OnceLock<jiff::tz::TimeZoneDatabase> = std::sync::OnceLock::new();
    DB.get_or_init(jiff::tz::TimeZoneDatabase::bundled)
}

pub(crate) fn zone(spec: &CalendarSpec) -> TimeZone {
    database().get(&spec.time_zone_id).unwrap_or(TimeZone::UTC)
}

pub(crate) fn zoned(t: SystemTime, tz: &TimeZone) -> Option<Zoned> {
    let ts = Timestamp::from_nanosecond((millis(t) * 1_000_000.0).round() as i128).ok()?;
    Some(ts.to_zoned(tz.clone()))
}

pub(crate) fn instant(z: &Zoned) -> SystemTime {
    from_millis(z.timestamp().as_nanosecond() as f64 / 1_000_000.0)
}

/// The calendar day containing `t`: its first instant and the next day's first instant. Days are
/// 23 or 25 hours long across daylight-saving changes.
pub(crate) fn day_interval(t: SystemTime, tz: &TimeZone) -> Option<(SystemTime, SystemTime)> {
    let z = zoned(t, tz)?;
    let start = z.start_of_day().ok()?;
    let next = start.date().tomorrow().ok()?.to_zoned(tz.clone()).ok()?.start_of_day().ok()?;
    Some((instant(&start), instant(&next)))
}

pub(crate) fn start_of_day(t: SystemTime, tz: &TimeZone) -> Option<SystemTime> {
    day_interval(t, tz).map(|d| d.0)
}

/// `t` moved by whole calendar days, keeping its wall-clock time where it exists.
pub(crate) fn add_days(t: SystemTime, days: i64, tz: &TimeZone) -> Option<SystemTime> {
    let z = zoned(t, tz)?;
    let moved = z.checked_add(days.days()).ok()?;
    Some(instant(&moved))
}

pub(crate) fn same_day(a: SystemTime, b: SystemTime, tz: &TimeZone) -> bool {
    match (zoned(a, tz), zoned(b, tz)) {
        (Some(x), Some(y)) => x.date() == y.date(),
        _ => false,
    }
}

/// Foundation weekday numbering: 1 Sunday … 7 Saturday.
pub(crate) fn foundation_weekday(day: Weekday) -> i32 {
    day.to_sunday_one_offset() as i32
}

pub(crate) fn valid_zone(identifier: &str) -> bool {
    database().get(identifier).is_ok()
}

/// Whether the bundled database knows `identifier`, as stored in `FastRecord.time_zone_id`.
#[uniffi::export]
pub fn time_zone_known(identifier: String) -> bool {
    valid_zone(&identifier)
}

/// Serde: `SystemTime` as JSON milliseconds.
pub(crate) mod ms {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &SystemTime, s: S) -> Result<S::Ok, S::Error> {
        super::write_ms(super::millis(*t), s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<SystemTime, D::Error> {
        let ms = f64::deserialize(d)?;
        if !ms.is_finite() {
            return Err(serde::de::Error::custom("date is not finite"));
        }
        Ok(super::from_millis(ms))
    }
}

pub(crate) mod ms_opt {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &Option<SystemTime>, s: S) -> Result<S::Ok, S::Error> {
        match t {
            Some(t) => super::write_ms(super::millis(*t), s),
            None => s.serialize_none(),
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<SystemTime>, D::Error> {
        match Option::<f64>::deserialize(d)? {
            Some(ms) if !ms.is_finite() => Err(serde::de::Error::custom("date is not finite")),
            Some(ms) => Ok(Some(super::from_millis(ms))),
            None => Ok(None),
        }
    }
}

/// Whole milliseconds are written as integers, as Foundation's JSONEncoder does.
fn write_ms<S: serde::Serializer>(ms: f64, s: S) -> Result<S::Ok, S::Error> {
    if ms.fract() == 0.0 && ms.abs() < 9.0e15 { s.serialize_i64(ms as i64) } else { s.serialize_f64(ms) }
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
