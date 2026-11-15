//! Text rules that must match Swift's `String` and Foundation: character counts are grapheme
//! clusters, blanks are `whitespacesAndNewlines`, search is `localizedStandardContains` and the
//! name order is `localizedStandardCompare`.

use std::cmp::Ordering;
use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};
use unicode_segmentation::UnicodeSegmentation;

/// Swift `String.count`: user-perceived characters, so an emoji or "é" counts once.
pub(crate) fn characters(text: &str) -> usize {
    text.graphemes(true).count()
}

/// Foundation's `whitespacesAndNewlines` (CoreFoundation's set, which includes U+200B).
fn is_space(c: char) -> bool {
    matches!(c, '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r' | ' ' | '\u{85}' | '\u{A0}' | '\u{1680}' | '\u{2000}'..='\u{200B}'
        | '\u{2028}' | '\u{2029}' | '\u{202F}' | '\u{205F}' | '\u{3000}')
}

/// `text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`.
pub(crate) fn is_blank(text: &str) -> bool {
    text.chars().all(is_space)
}

/// Swift `Character.isHexDigit`: ASCII hex digits and their fullwidth forms, one scalar each.
pub(crate) fn is_hex_character(grapheme: &str) -> bool {
    let mut chars = grapheme.chars();
    let (Some(c), None) = (chars.next(), chars.next()) else { return false };
    matches!(c, '0'..='9' | 'a'..='f' | 'A'..='F' | '\u{FF10}'..='\u{FF19}' | '\u{FF21}'..='\u{FF26}' | '\u{FF41}'..='\u{FF46}')
}

/// Case-folded (full folding: "ß" is "ss", "ﬁ" is "fi"), then diacritics removed. Width is kept,
/// as Foundation's search keeps it.
fn search_fold(text: &str) -> String {
    caseless::default_case_fold_str(text).nfd().filter(|c| !is_combining_mark(*c)).collect()
}

/// Foundation's `localizedStandardContains`: ignores case and diacritics.
pub(crate) fn standard_contains(text: &str, query: &str) -> bool {
    search_fold(text).contains(&search_fold(query))
}

// MARK: localizedStandardCompare

/// The ICU root order of ASCII punctuation and symbols (all sort before digits and letters).
const PUNCTUATION: &str = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$";

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum Primary {
    Space,
    Punctuation(usize),
    Symbol(u32),
    /// Digits compare by value: length without leading zeros, then the digits.
    Number(usize, String),
    Letter(u32),
}

#[derive(Debug, Default)]
struct Key {
    primary: Vec<Primary>,
    secondary: Vec<Vec<char>>,
    tertiary: Vec<u8>,
    zeros: Vec<usize>,
}

fn weight(c: char) -> Option<Primary> {
    if is_space(c) {
        Some(Primary::Space)
    } else if let Some(i) = PUNCTUATION.find(c) {
        Some(Primary::Punctuation(i))
    } else if c.is_alphanumeric() {
        Some(Primary::Letter(c as u32))
    } else if c.is_control() || c == '\u{200D}' || c == '\u{FE0F}' {
        None
    } else {
        Some(Primary::Symbol(c as u32))
    }
}

fn key(text: &str) -> Key {
    let mut key = Key::default();
    let mut digits: Option<(String, Vec<u8>)> = None;
    let flush = |key: &mut Key, digits: &mut Option<(String, Vec<u8>)>| {
        if let Some((run, tertiary)) = digits.take() {
            let value = run.trim_start_matches('0');
            let value = if value.is_empty() { "0" } else { value };
            key.zeros.push(run.len() - value.len());
            key.primary.push(Primary::Number(value.len(), value.to_string()));
            key.secondary.push(vec![]);
            key.tertiary.push(*tertiary.iter().max().unwrap_or(&0));
        }
    };
    for c in text.nfd() {
        if is_combining_mark(c) {
            flush(&mut key, &mut digits);
            if let Some(last) = key.secondary.last_mut() {
                last.push(c);
            }
            continue;
        }
        let compat: Vec<char> = c.to_string().nfkd().collect();
        let tertiary = (compat != [c]) as u8 * 2 + c.is_uppercase() as u8;
        let folded = caseless::default_case_fold_str(&compat.iter().collect::<String>());
        let mut marks = vec![];
        for f in folded.nfd() {
            if is_combining_mark(f) {
                marks.push(f);
            } else if f.is_ascii_digit() {
                let run = digits.get_or_insert_with(|| (String::new(), vec![]));
                run.0.push(f);
                run.1.push(tertiary);
            } else {
                flush(&mut key, &mut digits);
                if let Some(primary) = weight(f) {
                    key.primary.push(primary);
                    key.secondary.push(vec![]);
                    key.tertiary.push(tertiary);
                }
            }
        }
        if !marks.is_empty() {
            flush(&mut key, &mut digits);
            if let Some(last) = key.secondary.last_mut() {
                last.extend(marks);
            }
        }
    }
    flush(&mut key, &mut digits);
    key
}

/// Foundation's `localizedStandardCompare` (Finder order), modelled on its ICU root collation:
/// numbers by value, then base letters, then accents, then case and width (lower before upper,
/// plain before compatibility forms), then fewer leading zeros, then code units (`forcedOrdering`). Checked against
/// swift-foundation's output in `tests/fixtures/behaviour-swift.json`; uncommon scripts and symbols
/// may order differently from Apple's tables.
pub(crate) fn standard_order(a: &str, b: &str) -> Ordering {
    let (x, y) = (key(a), key(b));
    x.primary
        .cmp(&y.primary)
        .then_with(|| x.secondary.cmp(&y.secondary))
        .then_with(|| x.tertiary.cmp(&y.tertiary))
        .then_with(|| x.zeros.cmp(&y.zeros))
        .then_with(|| a.encode_utf16().cmp(b.encode_utf16()))
}

/// [`standard_order`] for the platforms: -1, 0 or 1, like `ComparisonResult.rawValue`.
#[uniffi::export]
pub fn standard_compare(a: String, b: String) -> i32 {
    standard_order(&a, &b) as i32
}
