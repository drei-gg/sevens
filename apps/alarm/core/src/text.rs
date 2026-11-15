//! Text rules that must match Swift's `String`: character counts are grapheme clusters and
//! equality is canonical equivalence.

use unicode_normalization::UnicodeNormalization;
use unicode_segmentation::UnicodeSegmentation;

/// Swift `String.count`: user-perceived characters, so an emoji or "é" counts once.
pub(crate) fn characters(text: &str) -> usize {
    text.graphemes(true).count()
}

/// Swift `trimmingCharacters(in: .whitespacesAndNewlines)`.
pub(crate) fn trimmed(text: &str) -> &str {
    text.trim()
}

/// Swift `precomposedStringWithCanonicalMapping` (NFC).
pub(crate) fn nfc(text: &str) -> String {
    text.nfc().collect()
}

/// Swift `String.first`: the first grapheme cluster.
pub(crate) fn first_character(text: &str) -> Option<&str> {
    text.graphemes(true).next()
}
