//! Dates as Swift `Date`s: seconds since 1970 as a double, crossing the boundary as `SystemTime`.

use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Seconds since 1970 (negative before), like `Date.timeIntervalSince1970`.
pub fn seconds(t: SystemTime) -> f64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_secs_f64(),
        Err(e) => -e.duration().as_secs_f64(),
    }
}

/// `Date(timeIntervalSince1970:)`. Non-finite values give the epoch.
pub fn from_seconds(s: f64) -> SystemTime {
    if !s.is_finite() {
        return UNIX_EPOCH;
    }
    let clamped = s.clamp(-1e15, 1e15);
    if clamped >= 0.0 {
        UNIX_EPOCH + Duration::from_secs_f64(clamped)
    } else {
        UNIX_EPOCH - Duration::from_secs_f64(-clamped)
    }
}

/// Whole seconds since 1970, rounded down.
pub fn floor_seconds(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_secs() as i64,
        Err(e) => {
            let d = e.duration();
            let s = d.as_secs() as i64;
            if d.subsec_nanos() > 0 { -s - 1 } else { -s }
        }
    }
}

/// Days since 1970-01-01 (Howard Hinnant's civil algorithm).
pub fn days_from_civil(y0: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y0 - 1 } else { y0 };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

pub fn civil_from_days(z0: i64) -> (i64, i64, i64) {
    let z = z0 + 719468;
    let era = (if z >= 0 { z } else { z - 146096 }) / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + if m <= 2 { 1 } else { 0 }, m, d)
}

pub fn days_in(y: i64, m: i64) -> i64 {
    match m {
        2 => {
            if (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 {
                29
            } else {
                28
            }
        }
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// GMT calendar fields of whole seconds since 1970.
pub fn gmt_fields(t: i64) -> (i64, i64, i64, i64, i64, i64) {
    let days = t.div_euclid(86400);
    let rem = t.rem_euclid(86400);
    let (y, m, d) = civil_from_days(days);
    (y, m, d, rem / 3600, rem % 3600 / 60, rem % 60)
}

pub fn pad(n: i64, w: usize) -> String {
    let s = n.to_string();
    if s.len() >= w {
        s
    } else {
        "0".repeat(w - s.len()) + &s
    }
}

/// `2026-11-10T12:00:00Z`: ISO 8601 in GMT, whole seconds (rounded down), as
/// `ISO8601DateFormatter` with `.withInternetDateTime` writes it.
pub fn iso8601(t: SystemTime) -> String {
    let (y, mo, d, h, mi, s) = gmt_fields(floor_seconds(t));
    format!(
        "{}-{}-{}T{}:{}:{}Z",
        pad(y, 4),
        pad(mo, 2),
        pad(d, 2),
        pad(h, 2),
        pad(mi, 2),
        pad(s, 2)
    )
}

/// Reads what `ISO8601DateFormatter` reads with `.withInternetDateTime` (and, when
/// `fractional`, `.withFractionalSeconds`): `YYYY-MM-DDTHH:MM:SS[.f](Z|±HH:MM|±HHMM)`.
pub fn parse_internet_date(text: &str, fractional: bool) -> Option<SystemTime> {
    let b = text.as_bytes();
    if b.len() < 20 {
        return None;
    }
    let num = |from: usize, len: usize| -> Option<i64> {
        if from + len > b.len() {
            return None;
        }
        let mut v = 0i64;
        for &c in &b[from..from + len] {
            if !c.is_ascii_digit() {
                return None;
            }
            v = v * 10 + (c - b'0') as i64;
        }
        Some(v)
    };
    if b[4] != b'-'
        || b[7] != b'-'
        || !(b[10] == b'T' || b[10] == b't')
        || b[13] != b':'
        || b[16] != b':'
    {
        return None;
    }
    let (y, mo, d) = (num(0, 4)?, num(5, 2)?, num(8, 2)?);
    let (h, mi, s) = (num(11, 2)?, num(14, 2)?, num(17, 2)?);
    if !(1..=12).contains(&mo) || d < 1 || d > days_in(y, mo) || h > 23 || mi > 59 || s > 60 {
        return None;
    }
    let mut i = 19;
    let mut frac = 0.0;
    if fractional {
        if i >= b.len() || b[i] != b'.' {
            return None;
        }
        i += 1;
        let start = i;
        let mut scale = 0.1;
        while i < b.len() && b[i].is_ascii_digit() {
            frac += (b[i] - b'0') as f64 * scale;
            scale /= 10.0;
            i += 1;
        }
        if i == start {
            return None;
        }
    }
    if i >= b.len() {
        return None;
    }
    let offset = match b[i] {
        b'Z' | b'z' => {
            i += 1;
            0
        }
        b'+' | b'-' => {
            let sign = if b[i] == b'+' { 1 } else { -1 };
            let oh = num(i + 1, 2)?;
            i += 3;
            if i < b.len() && b[i] == b':' {
                i += 1;
            }
            let om = num(i, 2)?;
            i += 2;
            if oh > 23 || om > 59 {
                return None;
            }
            sign * (oh * 3600 + om * 60)
        }
        _ => return None,
    };
    if i != b.len() {
        return None;
    }
    let secs = days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s - offset;
    Some(from_seconds(secs as f64 + frac))
}
