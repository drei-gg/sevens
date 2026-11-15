//! Page settings, paper sizes and library order (Swift `PageSettings.swift`).

use crate::documents::{ScanDocument, ScanError};
use crate::json::swift_int;
use crate::text::{canonically_equal, standard_compare};
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;

/// Original JPEGs are immutable. These settings are applied to previews, OCR and exports.
/// Every key is required when read, as Swift's synthesized `Decodable` requires them.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PageSettings {
    #[serde(with = "swift_int")]
    #[uniffi(default = 0)]
    pub quarter_turns: i64,
    #[uniffi(default = 0.0)]
    pub brightness: f64,
    #[uniffi(default = 1.0)]
    pub contrast: f64,
    #[uniffi(default = false)]
    pub grayscale: bool,
}

impl Default for PageSettings {
    fn default() -> Self {
        Self { quarter_turns: 0, brightness: 0.0, contrast: 1.0, grayscale: false }
    }
}

impl PageSettings {
    pub fn validate(&self) -> Result<(), ScanError> {
        let ok = (0..=3).contains(&self.quarter_turns)
            && self.brightness.is_finite()
            && (-0.5..=0.5).contains(&self.brightness)
            && self.contrast.is_finite()
            && (0.5..=2.0).contains(&self.contrast);
        if ok { Ok(()) } else { Err(ScanError::InvalidDocument) }
    }
}

/// `PageSettings()`: no turn, brightness 0, contrast 1, colour.
#[uniffi::export]
pub fn page_settings_default() -> PageSettings {
    PageSettings::default()
}

#[uniffi::export]
pub fn page_settings_validate(settings: PageSettings) -> Result<(), ScanError> {
    settings.validate()
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum PaperSize {
    A4,
    Letter,
}

/// The raw value Swift stores (`"a4"`, `"letter"`), for preferences.
#[uniffi::export]
pub fn paper_size_raw_value(size: PaperSize) -> String {
    match size {
        PaperSize::A4 => "a4",
        PaperSize::Letter => "letter",
    }
    .into()
}

#[uniffi::export]
pub fn paper_size_from_raw_value(raw: String) -> Option<PaperSize> {
    match raw.as_str() {
        "a4" => Some(PaperSize::A4),
        "letter" => Some(PaperSize::Letter),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum LibraryOrder {
    Newest,
    Oldest,
    Name,
}

#[uniffi::export]
pub fn library_order_raw_value(order: LibraryOrder) -> String {
    match order {
        LibraryOrder::Newest => "newest",
        LibraryOrder::Oldest => "oldest",
        LibraryOrder::Name => "name",
    }
    .into()
}

#[uniffi::export]
pub fn library_order_from_raw_value(raw: String) -> Option<LibraryOrder> {
    match raw.as_str() {
        "newest" => Some(LibraryOrder::Newest),
        "oldest" => Some(LibraryOrder::Oldest),
        "name" => Some(LibraryOrder::Name),
        _ => None,
    }
}

impl LibraryOrder {
    /// Ties (same date, or same title) fall back to the ID's `uuidString`, so the order is total.
    pub fn sorted(self, documents: &[ScanDocument]) -> Vec<ScanDocument> {
        let id = |d: &ScanDocument| d.id.hyphenated().to_string().to_uppercase();
        let less = |a: &ScanDocument, b: &ScanDocument| -> bool {
            match self {
                LibraryOrder::Newest => if a.created == b.created { id(a) < id(b) } else { a.created > b.created },
                LibraryOrder::Oldest => if a.created == b.created { id(a) < id(b) } else { a.created < b.created },
                LibraryOrder::Name => {
                    if canonically_equal(&a.title, &b.title) { id(a) < id(b) } else { standard_compare(&a.title, &b.title) == Ordering::Less }
                }
            }
        };
        let mut sorted = documents.to_vec();
        sorted.sort_by(|a, b| if less(a, b) { Ordering::Less } else if less(b, a) { Ordering::Greater } else { Ordering::Equal });
        sorted
    }
}

#[uniffi::export]
pub fn library_sorted(order: LibraryOrder, documents: Vec<ScanDocument>) -> Vec<ScanDocument> {
    order.sorted(&documents)
}
