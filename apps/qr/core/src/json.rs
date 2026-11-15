//! JSON the way the iOS app's `JSONEncoder` writes it with `.sortedKeys`: compact, keys sorted,
//! `/` escaped as `\/`, control characters as `\u00xx`, everything else as UTF-8. Reading goes
//! through `serde_json::Value` so the lossy, field-by-field decoding of the Swift files can be
//! reproduced exactly.

use serde_json::Value;

pub fn write_string(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '/' => out.push_str("\\/"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// A value with sorted keys (serde_json's map is ordered by key).
pub fn write(out: &mut String, v: &Value) {
    match v {
        Value::Null => out.push_str("null"),
        Value::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Value::Number(n) => out.push_str(&n.to_string()),
        Value::String(s) => write_string(out, s),
        Value::Array(a) => {
            out.push('[');
            for (i, x) in a.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write(out, x);
            }
            out.push(']');
        }
        Value::Object(m) => {
            let mut keys: Vec<&String> = m.keys().collect();
            keys.sort();
            out.push('{');
            for (i, k) in keys.into_iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                write_string(out, k);
                out.push(':');
                write(out, &m[k]);
            }
            out.push('}');
        }
    }
}

pub fn to_bytes(v: &Value) -> Vec<u8> {
    let mut s = String::new();
    write(&mut s, v);
    s.into_bytes()
}

/// An integer as `JSONDecoder` reads `Int`: an integral number (1, 1.0, 1e0) in range.
pub fn int(v: &Value) -> Option<i64> {
    let n = v.as_number()?;
    if let Some(i) = n.as_i64() {
        return Some(i);
    }
    let f = n.as_f64()?;
    (f.fract() == 0.0 && f >= i64::MIN as f64 && f < i64::MAX as f64).then_some(f as i64)
}

pub fn string(v: &Value) -> Option<String> {
    v.as_str().map(String::from)
}

/// A required field; a missing key or the wrong type fails the whole value.
pub fn field<'a>(v: &'a Value, key: &str) -> Option<&'a Value> {
    v.as_object()?.get(key)
}

/// An optional field: missing or null is None; the wrong type fails the whole value (Err).
pub fn optional<T>(v: &Value, key: &str, read: impl Fn(&Value) -> Option<T>) -> Result<Option<T>, ()> {
    match v.as_object().and_then(|o| o.get(key)) {
        None | Some(Value::Null) => Ok(None),
        Some(x) => read(x).map(Some).ok_or(()),
    }
}
