//! Rules that must match Swift's `String` and `Double`: equality and ordering under canonical
//! equivalence, Foundation's whitespace trim, and `Double.description`.

use std::cmp::Ordering;
use unicode_normalization::UnicodeNormalization;

pub(crate) fn nfc(text: &str) -> String {
    text.nfc().collect()
}

/// Swift `String` `<`: canonically equivalent strings are equal, the rest order by NFC scalars.
pub(crate) fn swift_cmp(a: &str, b: &str) -> Ordering {
    if a == b {
        return Ordering::Equal;
    }
    if a.is_ascii() && b.is_ascii() {
        return a.cmp(b);
    }
    nfc(a).cmp(&nfc(b))
}

pub(crate) fn swift_eq(a: &str, b: &str) -> bool {
    swift_cmp(a, b) == Ordering::Equal
}

/// A Swift `Set<String>` as a list: sorted, one entry per canonically equal string.
pub(crate) fn canonical_set(mut values: Vec<String>) -> Vec<String> {
    values.sort_by(|a, b| swift_cmp(a, b));
    values.dedup_by(|a, b| swift_eq(a, b));
    values
}

/// Swift `trimmingCharacters(in: .whitespacesAndNewlines)`.
pub(crate) fn trimmed(text: &str) -> &str {
    text.trim()
}

/// Swift `Double.description`, as string interpolation writes it: shortest round-trip digits,
/// fixed notation from 1e-4 up to 2^53, otherwise exponential with a signed two-digit exponent.
/// Verified against Swift 6.3 (tests/fixtures/swift-revisions.tsv).
pub(crate) fn swift_double(value: f64) -> String {
    if value.is_nan() {
        return "nan".into();
    }
    if value.is_infinite() {
        return if value < 0.0 {
            "-inf".into()
        } else {
            "inf".into()
        };
    }
    let magnitude = value.abs();
    if magnitude == 0.0 || (1e-4..=9_007_199_254_740_992.0).contains(&magnitude) {
        let fixed = format!("{value}");
        return if fixed.contains('.') {
            fixed
        } else {
            fixed + ".0"
        };
    }
    let exponential = format!("{value:e}");
    let (mantissa, exponent) = exponential.split_once('e').expect("exponential format");
    let (sign, digits) = match exponent.strip_prefix('-') {
        Some(digits) => ('-', digits),
        None => ('+', exponent),
    };
    format!("{mantissa}e{sign}{digits:0>2}")
}
