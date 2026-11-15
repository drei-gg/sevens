//! `Date` as Foundation's default `Codable` writes it: a Double of seconds since 2001-01-01.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Seconds between 1970-01-01 and 2001-01-01 (`Date.timeIntervalBetween1970AndReferenceDate`).
const REFERENCE: i64 = 978_307_200;

/// `timeIntervalSinceReferenceDate`. Whole seconds and nanoseconds are added once, so a value
/// read from JSON comes back as the same Double.
pub(crate) fn since_reference(t: SystemTime) -> f64 {
    let (secs, nanos) = match t.duration_since(UNIX_EPOCH) {
        Ok(d) => (d.as_secs() as i64, d.subsec_nanos()),
        Err(e) => {
            let d = e.duration();
            if d.subsec_nanos() == 0 { (-(d.as_secs() as i64), 0) } else { (-(d.as_secs() as i64) - 1, 1_000_000_000 - d.subsec_nanos()) }
        }
    };
    (secs - REFERENCE) as f64 + nanos as f64 / 1e9
}

pub(crate) fn from_reference(value: f64) -> SystemTime {
    let whole = value.floor();
    let mut nanos = ((value - whole) * 1e9).round() as u32;
    let mut secs = whole as i64 + REFERENCE;
    if nanos >= 1_000_000_000 {
        secs += 1;
        nanos = 0;
    }
    let base = if secs >= 0 { UNIX_EPOCH + Duration::from_secs(secs as u64) } else { UNIX_EPOCH - Duration::from_secs(secs.unsigned_abs()) };
    base + Duration::from_nanos(nanos as u64)
}

/// `t` as a Foundation `Date` holds it (a Double of seconds), so a value in memory equals the
/// same value read back from JSON.
pub(crate) fn as_date(t: SystemTime) -> SystemTime {
    from_reference(since_reference(t))
}

/// Whole seconds are written as integers, as Foundation's JSONEncoder does.
fn write<S: serde::Serializer>(value: f64, s: S) -> Result<S::Ok, S::Error> {
    if value.fract() == 0.0 && value.abs() < 9.0e15 { s.serialize_i64(value as i64) } else { s.serialize_f64(value) }
}

pub(crate) mod date {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &SystemTime, s: S) -> Result<S::Ok, S::Error> {
        super::write(super::since_reference(*t), s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<SystemTime, D::Error> {
        let value = f64::deserialize(d)?;
        if !value.is_finite() {
            return Err(serde::de::Error::custom("date is not finite"));
        }
        Ok(super::from_reference(value))
    }
}

pub(crate) mod date_opt {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(t: &Option<SystemTime>, s: S) -> Result<S::Ok, S::Error> {
        match t {
            Some(t) => super::write(super::since_reference(*t), s),
            None => s.serialize_none(),
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<SystemTime>, D::Error> {
        match Option::<f64>::deserialize(d)? {
            Some(v) if !v.is_finite() => Err(serde::de::Error::custom("date is not finite")),
            Some(v) => Ok(Some(super::from_reference(v))),
            None => Ok(None),
        }
    }
}

/// Foundation's `uuidString`: upper-case, hyphenated.
pub(crate) fn uuid_string(id: &uuid::Uuid) -> String {
    id.hyphenated().to_string().to_uppercase()
}

/// `UUID(uuidString:)`: only the 36-character hyphenated form, either case.
pub(crate) fn parse_uuid(text: &str) -> Option<uuid::Uuid> {
    if text.len() != 36 {
        return None;
    }
    uuid::Uuid::parse_str(text).ok()
}

/// Serde: UUIDs as `uuidString`; read as `UUID(uuidString:)` reads them.
pub(crate) mod upper {
    use serde::{Deserialize, Deserializer, Serializer};
    use uuid::Uuid;
    pub fn serialize<S: Serializer>(id: &Uuid, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&super::uuid_string(id))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Uuid, D::Error> {
        let text = String::deserialize(d)?;
        super::parse_uuid(&text).ok_or_else(|| serde::de::Error::custom("invalid UUID"))
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
            Some(text) => super::parse_uuid(&text).map(Some).ok_or_else(|| serde::de::Error::custom("invalid UUID")),
            None => Ok(None),
        }
    }
}

/// Serde: `Data` as Foundation's default coding writes it, standard Base64 with padding.
pub(crate) mod base64_data {
    use base64::{Engine, engine::general_purpose::STANDARD};
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(data: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&STANDARD.encode(data))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let text = String::deserialize(d)?;
        STANDARD.decode(text).map_err(serde::de::Error::custom)
    }
}
