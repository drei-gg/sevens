//! JSON as Foundation's default `JSONEncoder` writes it and as `JSONDecoder` reads it.
//!
//! The Swift app uses `JSONEncoder()` with no options: compact, keys in declaration order (the
//! synthesized `Codable` order), `nil` optionals left out, `/` escaped as `\/`, UUIDs upper-case,
//! `Date` as a reference-date double, and doubles in Swift's shortest form without a trailing
//! `.0`. Serde already writes struct fields in declaration order; this formatter does the rest.

use serde::Serialize;
use std::io::{Result, Write};

pub(crate) fn encode<T: Serialize>(value: &T) -> serde_json::Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut serializer = serde_json::Serializer::with_formatter(&mut out, Foundation);
    value.serialize(&mut serializer)?;
    Ok(out)
}

struct Foundation;

impl serde_json::ser::Formatter for Foundation {
    fn write_f64<W: ?Sized + Write>(&mut self, w: &mut W, value: f64) -> Result<()> {
        w.write_all(swift_double(value).as_bytes())
    }
    fn write_string_fragment<W: ?Sized + Write>(&mut self, w: &mut W, fragment: &str) -> Result<()> {
        w.write_all(fragment.replace('/', "\\/").as_bytes())
    }
}

/// Swift's `Double.description` with a trailing `.0` removed, which is what `JSONEncoder` writes:
/// decimal between 1e-4 and 2^53, exponential (`1e-05`, `1.5e+20`) outside it.
pub(crate) fn swift_double(value: f64) -> String {
    if value == 0.0 {
        return if value.is_sign_negative() { "-0".into() } else { "0".into() };
    }
    let scientific = format!("{value:e}");
    let (mantissa, exponent) = scientific.split_once('e').unwrap_or((&scientific, "0"));
    let exponent: i32 = exponent.parse().unwrap_or(0);
    if exponent < -4 || value.abs() > 2f64.powi(53) {
        let sign = if exponent < 0 { '-' } else { '+' };
        return format!("{mantissa}e{sign}{:02}", exponent.abs());
    }
    let text = format!("{value}");
    text.strip_suffix(".0").map(str::to_string).unwrap_or(text)
}

/// Serde for `UUID`: written as `uuidString` (upper case); read as `UUID(uuidString:)` does, the
/// 36-character hyphenated form in either case and nothing else.
pub(crate) mod uuid_string {
    use serde::{Deserialize, Deserializer, Serializer, de::Error};
    use uuid::Uuid;
    pub fn serialize<S: Serializer>(id: &Uuid, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&id.hyphenated().to_string().to_uppercase())
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Uuid, D::Error> {
        let text = String::deserialize(d)?;
        super::parse_uuid(&text).ok_or_else(|| D::Error::custom("not a UUID string"))
    }
}

/// `UUID(uuidString:)`.
pub(crate) fn parse_uuid(text: &str) -> Option<uuid::Uuid> {
    let hyphens = text.len() == 36 && text.char_indices().all(|(i, c)| matches!(i, 8 | 13 | 18 | 23) == (c == '-'));
    if hyphens { uuid::Uuid::parse_str(text).ok() } else { None }
}

/// Serde for Swift `Int`: `JSONDecoder` also accepts a number with an exact integral value
/// written as a double (`1.0`, `2e1`).
pub(crate) mod swift_int {
    use serde::{Deserialize, Deserializer, Serializer, de::Error};
    pub fn serialize<S: Serializer>(value: &i64, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_i64(*value)
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<i64, D::Error> {
        super::int(serde_json::Number::deserialize(d)?).ok_or_else(|| D::Error::custom("not an Int"))
    }
    pub mod vec {
        use serde::{Deserialize, Deserializer, Serializer, de::Error, ser::SerializeSeq};
        pub fn serialize<S: Serializer>(values: &[i64], s: S) -> Result<S::Ok, S::Error> {
            let mut seq = s.serialize_seq(Some(values.len()))?;
            for v in values {
                seq.serialize_element(v)?;
            }
            seq.end()
        }
        pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<i64>, D::Error> {
            Vec::<serde_json::Number>::deserialize(d)?
                .into_iter()
                .map(|n| super::super::int(n).ok_or_else(|| D::Error::custom("not an Int")))
                .collect()
        }
    }
}

fn int(number: serde_json::Number) -> Option<i64> {
    if let Some(i) = number.as_i64() {
        return Some(i);
    }
    let f = number.as_f64()?;
    (f.fract() == 0.0 && f >= i64::MIN as f64 && f < i64::MAX as f64).then_some(f as i64)
}
