//! Text rules that must match Swift's `String`: character counts are grapheme clusters, and
//! search is case- and diacritic-insensitive like `localizedStandardContains`.

use unicode_normalization::{UnicodeNormalization, char::is_combining_mark};
use unicode_segmentation::UnicodeSegmentation;

/// Swift `String.count`: user-perceived characters, so an emoji or "é" counts once.
pub(crate) fn characters(text: &str) -> usize {
    text.graphemes(true).count()
}

/// Swift `trimmingCharacters(in: .whitespacesAndNewlines)`.
pub(crate) fn trimmed(text: &str) -> &str {
    text.trim()
}

fn fold(text: &str) -> String {
    text.nfd().filter(|c| !is_combining_mark(*c)).collect::<String>().to_lowercase()
}

/// Foundation's `localizedStandardContains`: ignores case and diacritics.
pub(crate) fn standard_contains(text: &str, query: &str) -> bool {
    fold(text).contains(&fold(query))
}
