//! Foundation `JSONEncoder` defaults, as the Swift app writes them: dates as seconds since
//! 2001-01-01 (a double), UUIDs as uppercase strings, `Data` as padded base64 and `Set`s as
//! arrays. Swift writes keys in hash order; Rust writes struct fields in declaration order and
//! map keys sorted, which Swift reads the same.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Foundation's reference date, 2001-01-01T00:00:00Z.
pub(crate) const REFERENCE_DATE_SECONDS: u64 = 978_307_200;

pub(crate) fn reference_date() -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(REFERENCE_DATE_SECONDS)
}

/// `Date.distantFuture`.
pub(crate) fn distant_future() -> SystemTime {
    reference_date() + Duration::from_secs(63_113_904_000)
}

/// `Date.distantPast`.
pub(crate) fn distant_past() -> SystemTime {
    reference_date() - Duration::from_secs(63_114_076_800)
}

/// `Date.timeIntervalSinceReferenceDate`.
pub(crate) fn since_reference(date: SystemTime) -> f64 {
    match date.duration_since(reference_date()) {
        Ok(d) => d.as_secs() as f64 + d.subsec_nanos() as f64 * 1e-9,
        Err(e) => {
            let d = e.duration();
            -(d.as_secs() as f64 + d.subsec_nanos() as f64 * 1e-9)
        }
    }
}

/// `Date(timeIntervalSinceReferenceDate:)`, to the nanosecond.
pub(crate) fn from_reference(seconds: f64) -> Option<SystemTime> {
    if !seconds.is_finite() {
        return None;
    }
    let magnitude = Duration::try_from_secs_f64(seconds.abs()).ok()?;
    if seconds >= 0.0 { reference_date().checked_add(magnitude) } else { reference_date().checked_sub(magnitude) }
}

/// Seconds between two dates, as `Date.timeIntervalSince(_:)`.
pub(crate) fn interval(later: SystemTime, earlier: SystemTime) -> f64 {
    since_reference(later) - since_reference(earlier)
}

pub(crate) mod date {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(date: &SystemTime, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_f64(super::since_reference(*date))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<SystemTime, D::Error> {
        let seconds = f64::deserialize(d)?;
        super::from_reference(seconds).ok_or_else(|| serde::de::Error::custom("date out of range"))
    }
}

pub(crate) mod date_opt {
    use serde::{Deserialize, Deserializer, Serializer};
    use std::time::SystemTime;
    pub fn serialize<S: Serializer>(date: &Option<SystemTime>, s: S) -> Result<S::Ok, S::Error> {
        match date {
            Some(date) => super::date::serialize(date, s),
            None => s.serialize_none(),
        }
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<SystemTime>, D::Error> {
        match Option::<f64>::deserialize(d)? {
            Some(seconds) => super::from_reference(seconds).map(Some).ok_or_else(|| serde::de::Error::custom("date out of range")),
            None => Ok(None),
        }
    }
}

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

/// A Swift `Set<String>`: any order in, sorted and deduplicated out.
pub(crate) mod set {
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    pub fn serialize<S: Serializer>(values: &[String], s: S) -> Result<S::Ok, S::Error> {
        crate::text::canonical_set(values.to_vec()).serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<String>, D::Error> {
        Ok(crate::text::canonical_set(Vec::<String>::deserialize(d)?))
    }
}

/// A Swift `[String: V]`: a JSON object, written with sorted keys.
pub(crate) mod sorted_map {
    use serde::{Serialize, Serializer};
    use std::collections::{BTreeMap, HashMap};
    pub fn serialize<S: Serializer, V: Serialize>(map: &HashMap<String, V>, s: S) -> Result<S::Ok, S::Error> {
        map.iter().collect::<BTreeMap<_, _>>().serialize(s)
    }
}

/// A Swift `[String: Data]`: base64 values, sorted keys.
pub(crate) mod data_map {
    use base64::{Engine, engine::general_purpose::STANDARD};
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    use std::collections::{BTreeMap, HashMap};
    pub fn serialize<S: Serializer>(map: &HashMap<String, Vec<u8>>, s: S) -> Result<S::Ok, S::Error> {
        map.iter().map(|(k, v)| (k, STANDARD.encode(v))).collect::<BTreeMap<_, _>>().serialize(s)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<HashMap<String, Vec<u8>>, D::Error> {
        HashMap::<String, String>::deserialize(d)?
            .into_iter()
            .map(|(k, v)| STANDARD.decode(v).map(|bytes| (k, bytes)).map_err(serde::de::Error::custom))
            .collect()
    }
}
