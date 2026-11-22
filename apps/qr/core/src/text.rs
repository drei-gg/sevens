//! Swift's string semantics, so a check sees the same pieces on both platforms.
//!
//! Swift splits, searches and compares `String`s by `Character` (an extended grapheme cluster),
//! with canonical equivalence: `"@\u{301}"` is one character that is not `"@"`, and
//! `"\u{37E}" == ";"`. The QRCore code relies on that in its URL and payload parsing, so the
//! helpers here reproduce it: grapheme iteration, canonical equality, Foundation's whitespace sets,
//! Swift's context-free case mapping and its integer parsing. Code that Swift wrote over
//! `unicodeScalars` or `utf8` uses plain `char`s and bytes instead.

use unicode_general_category::{GeneralCategory, get_general_category};
use unicode_normalization::UnicodeNormalization;
use unicode_segmentation::UnicodeSegmentation;

/// Extended grapheme clusters (Swift `Character`s).
pub fn graphemes(s: &str) -> unicode_segmentation::Graphemes<'_> {
    s.graphemes(true)
}

/// Swift's `count`.
pub fn count(s: &str) -> usize {
    if s.is_ascii() && !s.contains('\r') {
        return s.len();
    }
    graphemes(s).count()
}

/// `Character._firstScalarOrCRLF`.
pub fn first_scalar(g: &str) -> char {
    if g == "\r\n" {
        return '\n';
    }
    g.chars().next().unwrap_or('\0')
}

fn canonical(s: &str) -> String {
    s.nfd().collect()
}

/// Swift `Character == Character` against one scalar (canonical equivalence).
pub fn ch_eq(g: &str, c: char) -> bool {
    let mut buf = [0u8; 4];
    let cs = c.encode_utf8(&mut buf);
    if g == cs {
        return true;
    }
    if g.is_ascii() && c.is_ascii() {
        return false;
    }
    canonical(g) == canonical(cs)
}

/// Swift `String == String` (canonical equivalence).
pub fn str_eq(a: &str, b: &str) -> bool {
    if a == b {
        return true;
    }
    if a.is_ascii() && b.is_ascii() {
        return false;
    }
    canonical(a) == canonical(b)
}

fn graphemes_eq(a: &str, b: &str) -> bool {
    a == b || ((!a.is_ascii() || !b.is_ascii()) && canonical(a) == canonical(b))
}

/// Swift `hasPrefix`: bytes when both are ASCII, otherwise character by character.
pub fn has_prefix(s: &str, p: &str) -> bool {
    if s.is_ascii() && p.is_ascii() {
        return s.as_bytes().starts_with(p.as_bytes());
    }
    let mut a = graphemes(s);
    for pg in graphemes(p) {
        match a.next() {
            Some(g) if graphemes_eq(g, pg) => {}
            _ => return false,
        }
    }
    true
}

/// Swift `hasSuffix`.
pub fn has_suffix(s: &str, p: &str) -> bool {
    if s.is_ascii() && p.is_ascii() {
        return s.as_bytes().ends_with(p.as_bytes());
    }
    let mut a = graphemes(s).rev();
    for pg in graphemes(p).rev() {
        match a.next() {
            Some(g) if graphemes_eq(g, pg) => {}
            _ => return false,
        }
    }
    true
}

/// Foundation's `contains` (canonical, never inside a character).
pub fn contains(s: &str, needle: &str) -> bool {
    if needle.is_empty() {
        return false;
    }
    if s.is_ascii() && needle.is_ascii() && !s.contains('\r') && !needle.contains('\r') {
        return s.contains(needle);
    }
    let hay: Vec<&str> = graphemes(s).collect();
    let pat: Vec<&str> = graphemes(needle).collect();
    if pat.len() > hay.len() {
        return false;
    }
    (0..=hay.len() - pat.len()).any(|i| {
        pat.iter()
            .enumerate()
            .all(|(k, p)| graphemes_eq(hay[i + k], p))
    })
}

/// Byte offset of the first character matching `pred`.
pub fn find(s: &str, mut pred: impl FnMut(&str) -> bool) -> Option<usize> {
    s.grapheme_indices(true)
        .find(|(_, g)| pred(g))
        .map(|(i, _)| i)
}

/// Byte offset of the last character matching `pred`.
pub fn rfind(s: &str, mut pred: impl FnMut(&str) -> bool) -> Option<usize> {
    s.grapheme_indices(true)
        .rev()
        .find(|(_, g)| pred(g))
        .map(|(i, _)| i)
}

/// Byte offset of the first character equal to `c`.
pub fn find_ch(s: &str, c: char) -> Option<usize> {
    find(s, |g| ch_eq(g, c))
}

/// Byte offset of the last character equal to `c`.
pub fn rfind_ch(s: &str, c: char) -> Option<usize> {
    rfind(s, |g| ch_eq(g, c))
}

/// The byte length of the character starting at `i`.
pub fn char_len_at(s: &str, i: usize) -> usize {
    graphemes(&s[i..]).next().map_or(0, str::len)
}

/// Swift `split(whereSeparator:)` / `split(separator:omittingEmptySubsequences:)` over characters.
pub fn split(s: &str, mut is_sep: impl FnMut(&str) -> bool, omit_empty: bool) -> Vec<&str> {
    let mut out = Vec::new();
    let mut start = 0;
    if s.is_ascii() && !s.contains('\r') {
        for i in 0..s.len() {
            if is_sep(&s[i..i + 1]) {
                if !(omit_empty && i == start) {
                    out.push(&s[start..i]);
                }
                start = i + 1;
            }
        }
        if !(omit_empty && start == s.len()) {
            out.push(&s[start..]);
        }
        return out;
    }
    for (i, g) in s.grapheme_indices(true) {
        if is_sep(g) {
            if !(omit_empty && i == start) {
                out.push(&s[start..i]);
            }
            start = i + g.len();
        }
    }
    if !(omit_empty && start == s.len()) {
        out.push(&s[start..]);
    }
    out
}

/// Split on one character.
pub fn split_ch(s: &str, c: char, omit_empty: bool) -> Vec<&str> {
    split(s, |g| ch_eq(g, c), omit_empty)
}

/// Replaces every character equal to `from` (Foundation `replacingOccurrences`, one character).
pub fn replace_ch(s: &str, from: char, to: &str) -> String {
    if s.is_ascii() && from.is_ascii() && !s.contains('\r') {
        return s.replace(from, to);
    }
    graphemes(s)
        .map(|g| if ch_eq(g, from) { to } else { g })
        .collect()
}

/// The first `n` characters (Swift `prefix(n)`).
pub fn prefix(s: &str, n: usize) -> &str {
    match s.grapheme_indices(true).nth(n) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}

/// Everything after the first `n` characters (Swift `dropFirst(n)`).
pub fn drop_first(s: &str, n: usize) -> &str {
    &s[prefix(s, n).len()..]
}

/// Everything but the last `n` characters (Swift `dropLast(n)`).
pub fn drop_last(s: &str, n: usize) -> &str {
    if n == 0 {
        return s;
    }
    match s.grapheme_indices(true).rev().nth(n - 1) {
        Some((i, _)) => &s[..i],
        None => "",
    }
}

/// The leading characters that match `pred` (Swift `prefix(while:)`).
pub fn prefix_while(s: &str, mut pred: impl FnMut(&str) -> bool) -> &str {
    match find(s, |g| !pred(g)) {
        Some(i) => &s[..i],
        None => s,
    }
}

/// Swift `drop(while:)`.
pub fn drop_while(s: &str, pred: impl FnMut(&str) -> bool) -> &str {
    &s[prefix_while(s, pred).len()..]
}

/// The first character.
pub fn first(s: &str) -> Option<&str> {
    graphemes(s).next()
}

/// The last character.
pub fn last(s: &str) -> Option<&str> {
    graphemes(s).next_back()
}

/// Swift's context-free `lowercased()` (no final-sigma rule).
pub fn lower(s: &str) -> String {
    if s.is_ascii() {
        return s.to_ascii_lowercase();
    }
    s.chars().flat_map(char::to_lowercase).collect()
}

/// Swift's `uppercased()`.
pub fn upper(s: &str) -> String {
    if s.is_ascii() {
        return s.to_ascii_uppercase();
    }
    s.chars().flat_map(char::to_uppercase).collect()
}

/// Unicode general category Zs.
pub fn is_space_separator(c: char) -> bool {
    get_general_category(c) == GeneralCategory::SpaceSeparator
}

/// Foundation `CharacterSet.whitespaces`: Zs and tab.
pub fn is_ws(c: char) -> bool {
    c == '\t' || is_space_separator(c)
}

/// Foundation `CharacterSet.whitespacesAndNewlines`.
pub fn is_ws_nl(c: char) -> bool {
    is_ws(c)
        || matches!(
            c,
            '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}'
        )
}

/// `trimmingCharacters(in: .whitespaces)`.
pub fn trim_ws(s: &str) -> &str {
    s.trim_matches(is_ws)
}

/// `trimmingCharacters(in: .whitespacesAndNewlines)`.
pub fn trim_ws_nl(s: &str) -> &str {
    s.trim_matches(is_ws_nl)
}

/// `Unicode.Scalar.Properties.numericType != nil` (Nd, Nl, No, plus CJK numerals).
pub fn has_numeric_type(c: char) -> bool {
    c.is_numeric() || is_cjk_numeral(c)
}

fn is_cjk_numeral(c: char) -> bool {
    // Unihan numeric values (kPrimaryNumeric and friends) that ICU reports as Numeric_Type=Numeric.
    matches!(
        c,
        '〇' | '一'
            | '二'
            | '三'
            | '四'
            | '五'
            | '六'
            | '七'
            | '八'
            | '九'
            | '十'
            | '百'
            | '千'
            | '万'
            | '億'
            | '兆'
            | '零'
            | '壹'
            | '貳'
            | '參'
            | '肆'
            | '伍'
            | '陸'
            | '柒'
            | '捌'
            | '玖'
            | '拾'
            | '佰'
            | '仟'
            | '萬'
            | '弐'
            | '参'
            | '廿'
            | '卅'
            | '卌'
            | '两'
            | '兩'
            | '亿'
    )
}

/// `numericType == .decimal` (general category Nd).
pub fn is_decimal(c: char) -> bool {
    get_general_category(c) == GeneralCategory::DecimalNumber
}

pub fn general_category(c: char) -> GeneralCategory {
    get_general_category(c)
}

/// `Character.isLetter`.
pub fn ch_is_letter(g: &str) -> bool {
    first_scalar(g).is_alphabetic()
}

/// `Character.isNumber`.
pub fn ch_is_number(g: &str) -> bool {
    has_numeric_type(first_scalar(g))
}

/// `Character.isASCII`.
pub fn ch_is_ascii(g: &str) -> bool {
    g == "\r\n" || (g.len() == 1 && g.is_ascii())
}

/// `Character.isWhitespace`.
pub fn ch_is_whitespace(g: &str) -> bool {
    first_scalar(g).is_whitespace()
}

/// `Character.isNewline`.
pub fn ch_is_newline(g: &str) -> bool {
    matches!(
        first_scalar(g),
        '\n' | '\u{0B}' | '\u{0C}' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}'
    )
}

/// Swift `FixedWidthInteger(_:radix:)` for unsigned values: an optional `+` or `-` (only `-0`
/// survives), then at least one digit of the radix. nil on overflow.
pub fn parse_uint(s: &str, radix: u32) -> Option<u64> {
    let (neg, digits) = match s.as_bytes().first() {
        Some(b'+') => (false, &s[1..]),
        Some(b'-') => (true, &s[1..]),
        _ => (false, s),
    };
    if digits.is_empty() {
        return None;
    }
    let mut v: u64 = 0;
    for c in digits.chars() {
        let d = c.to_digit(radix)? as u64;
        v = v.checked_mul(radix as u64)?.checked_add(d)?;
    }
    if neg && v != 0 {
        return None;
    }
    Some(v)
}

/// Swift `Int(_:)`: optional sign, then decimal digits; nil on overflow.
pub fn parse_int(s: &str) -> Option<i64> {
    let (neg, digits) = match s.as_bytes().first() {
        Some(b'+') => (false, &s[1..]),
        Some(b'-') => (true, &s[1..]),
        _ => (false, s),
    };
    if digits.is_empty() || !digits.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let mut v: i64 = 0;
    for b in digits.bytes() {
        let d = (b - b'0') as i64;
        v = v.checked_mul(10)?;
        v = if neg {
            v.checked_sub(d)?
        } else {
            v.checked_add(d)?
        };
    }
    Some(v)
}

/// NFKC (`precomposedStringWithCompatibilityMapping`).
pub fn nfkc(s: &str) -> String {
    s.nfkc().collect()
}

/// UTF-8 bytes decoded with replacement (Swift `String(decoding:as: UTF8.self)`).
pub fn utf8_lossy(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

/// The first `max` bytes of UTF-8, cut at a scalar boundary.
pub fn cut_utf8(s: &str, max: usize) -> &str {
    if s.len() <= max {
        return s;
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    &s[..end]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn swift_character_semantics() {
        assert!(!ch_eq("@\u{301}", '@'));
        assert!(ch_eq("\u{37E}", ';'));
        assert_eq!(
            split_ch("a.\u{301}b.c", '.', false),
            vec!["a.\u{301}b", "c"]
        );
        assert!(has_prefix("e\u{301}x", "\u{e9}"));
        assert!(!has_prefix("e\u{301}x", "e"));
        assert_eq!(count("a\r\nb"), 3);
        assert_eq!(parse_uint("+5", 10), Some(5));
        assert_eq!(parse_uint("-0", 10), Some(0));
        assert_eq!(parse_uint("-1", 10), None);
        assert_eq!(lower("ΣΑΣ"), "σασ");
    }
}
