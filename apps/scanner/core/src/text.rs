//! Text rules that must match Swift's `String` and Foundation: character counts are grapheme
//! clusters, search ignores case and diacritics like `localizedStandardContains`, and names order
//! like `localizedStandardCompare` (Finder order: numbers by value, case and accents secondary).

use std::cmp::Ordering;
use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};
use unicode_segmentation::UnicodeSegmentation;

/// Swift `String.count`: user-perceived characters, so an emoji or "é" counts once.
pub(crate) fn characters(text: &str) -> usize {
    text.graphemes(true).count()
}

/// `CharacterSet.whitespacesAndNewlines`: Unicode `Z*`, tab and the newline characters, which is
/// exactly Unicode `White_Space`, so `char::is_whitespace`.
pub(crate) fn is_whitespace_or_newline(c: char) -> bool {
    c.is_whitespace()
}

/// Swift `trimmingCharacters(in: .whitespacesAndNewlines)`.
pub(crate) fn trimmed(text: &str) -> &str {
    text.trim_matches(is_whitespace_or_newline)
}

fn strip_marks(text: &str) -> String {
    text.nfd().filter(|c| !is_combining_mark(*c)).collect()
}

fn fold(text: &str) -> String {
    strip_marks(text).to_lowercase()
}

/// Foundation's `localizedStandardContains`: ignores case and diacritics.
pub(crate) fn standard_contains(text: &str, query: &str) -> bool {
    fold(text).contains(&fold(query))
}

/// Swift `String ==`: canonical equivalence, so a precomposed "é" equals "e" + accent.
pub(crate) fn canonically_equal(a: &str, b: &str) -> bool {
    a == b || a.nfc().eq(b.nfc())
}

enum Piece {
    Number(String),
    Char(char),
}

fn pieces(text: &str) -> Vec<Piece> {
    let mut out = Vec::new();
    let mut digits = String::new();
    for c in text.chars() {
        if c.is_ascii_digit() {
            digits.push(c);
        } else {
            if !digits.is_empty() {
                out.push(Piece::Number(std::mem::take(&mut digits)));
            }
            out.push(Piece::Char(c));
        }
    }
    if !digits.is_empty() {
        out.push(Piece::Number(digits));
    }
    out
}

/// Whitespace and punctuation sort before digits, and digits before letters, as in ICU's root
/// collation; within a class, by character.
const NUMBER: u8 = 2;

fn char_class(c: char) -> u8 {
    if c.is_whitespace() {
        0
    } else if c.is_alphanumeric() {
        3
    } else {
        1
    }
}

fn compare_level(a: &str, b: &str) -> Ordering {
    let (a, b) = (pieces(a), pieces(b));
    for (x, y) in a.iter().zip(b.iter()) {
        let order = match (x, y) {
            (Piece::Number(x), Piece::Number(y)) => {
                let (tx, ty) = (x.trim_start_matches('0'), y.trim_start_matches('0'));
                tx.len().cmp(&ty.len()).then_with(|| tx.cmp(ty))
            }
            (Piece::Number(_), Piece::Char(c)) => NUMBER.cmp(&char_class(*c)),
            (Piece::Char(c), Piece::Number(_)) => char_class(*c).cmp(&NUMBER),
            (Piece::Char(x), Piece::Char(y)) => char_class(*x).cmp(&char_class(*y)).then_with(|| x.cmp(y)),
        };
        if order != Ordering::Equal {
            return order;
        }
    }
    a.len().cmp(&b.len())
}

/// Foundation's `localizedStandardCompare`, approximated without ICU: base letters with numbers
/// by value first, then accents, then case (lower before upper), then leading zeros.
pub(crate) fn standard_compare(a: &str, b: &str) -> Ordering {
    compare_level(&fold(a), &fold(b))
        .then_with(|| compare_level(&a.nfd().collect::<String>().to_lowercase(), &b.nfd().collect::<String>().to_lowercase()))
        .then_with(|| {
            // Tertiary: case, lower first.
            let swap = |s: &str| -> String {
                s.nfd().map(|c| if c.is_lowercase() { c.to_uppercase().next().unwrap_or(c) } else { c.to_lowercase().next().unwrap_or(c) }).collect()
            };
            compare_level(&swap(a), &swap(b))
        })
        .then_with(|| a.nfd().cmp(b.nfd()))
}

/// `CharacterSet.controlCharacters`: Unicode general categories `Cc` and `Cf`.
pub(crate) fn is_control(c: char) -> bool {
    if c.is_control() {
        return true;
    }
    matches!(c as u32,
        0x00AD | 0x0600..=0x0605 | 0x061C | 0x06DD | 0x070F | 0x0890..=0x0891 | 0x08E2 | 0x180E
        | 0x200B..=0x200F | 0x202A..=0x202E | 0x2060..=0x2064 | 0x2066..=0x206F | 0xFEFF
        | 0xFFF9..=0xFFFB | 0x110BD | 0x110CD | 0x13430..=0x1343F | 0x1BCA0..=0x1BCA3
        | 0x1D173..=0x1D17A | 0xE0001 | 0xE0020..=0xE007F)
}

/// Swift `String.prefix(n)`: the first `n` characters (grapheme clusters).
pub(crate) fn prefix_characters(text: &str, n: usize) -> &str {
    match text.grapheme_indices(true).nth(n) {
        Some((index, _)) => &text[..index],
        None => text,
    }
}
