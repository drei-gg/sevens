//! Page settings, paper sizes and library order (Swift `PageSettings.swift`).

use crate::documents::{ScanDocument, ScanError};
use crate::json::swift_int;
use crate::text::{canonically_equal, standard_compare};
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;

/// Original JPEGs are immutable. These settings are applied to previews, OCR and exports.
/// Original adjustment keys remain required. Crop is optional for legacy manifests.
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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub crop: Option<PageCrop>,
}

impl Default for PageSettings {
    fn default() -> Self {
        Self {
            quarter_turns: 0,
            brightness: 0.0,
            contrast: 1.0,
            grayscale: false,
            crop: None,
        }
    }
}

impl PageSettings {
    pub fn validate(&self) -> Result<(), ScanError> {
        let ok = (0..=3).contains(&self.quarter_turns)
            && self.brightness.is_finite()
            && (-0.5..=0.5).contains(&self.brightness)
            && self.contrast.is_finite()
            && (0.5..=2.0).contains(&self.contrast);
        if let Some(crop) = self.crop { crop.validate()?; }
        if ok {
            Ok(())
        } else {
            Err(ScanError::InvalidDocument)
        }
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
                LibraryOrder::Newest => {
                    if a.created == b.created {
                        id(a) < id(b)
                    } else {
                        a.created > b.created
                    }
                }
                LibraryOrder::Oldest => {
                    if a.created == b.created {
                        id(a) < id(b)
                    } else {
                        a.created < b.created
                    }
                }
                LibraryOrder::Name => {
                    if canonically_equal(&a.title, &b.title) {
                        id(a) < id(b)
                    } else {
                        standard_compare(&a.title, &b.title) == Ordering::Less
                    }
                }
            }
        };
        let mut sorted = documents.to_vec();
        sorted.sort_by(|a, b| {
            if less(a, b) {
                Ordering::Less
            } else if less(b, a) {
                Ordering::Greater
            } else {
                Ordering::Equal
            }
        });
        sorted
    }
}

#[uniffi::export]
pub fn library_sorted(order: LibraryOrder, documents: Vec<ScanDocument>) -> Vec<ScanDocument> {
    order.sorted(&documents)
}

/// Normalized edges in the upright original, before page quarter turns. Originals never change.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PageCrop {
    pub left: f64,
    pub top: f64,
    pub right: f64,
    pub bottom: f64,
}
impl PageCrop {
    pub(crate) fn validate(self) -> Result<(), ScanError> {
        if [self.left, self.top, self.right, self.bottom].iter().all(|v| v.is_finite() && (0.0..=1.0).contains(v))
            && self.right > self.left && self.bottom > self.top {
            Ok(())
        } else { Err(ScanError::InvalidDocument) }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct CropPixels {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

/// The same inclusive coverage at every native render size: floor start, ceil end, minimum one pixel.
#[uniffi::export]
pub fn page_crop_pixels(crop: PageCrop, width: u32, height: u32) -> Result<CropPixels, ScanError> {
    crop.validate()?;
    if width == 0 || height == 0 { return Err(ScanError::InvalidDocument); }
    let x = ((crop.left * width as f64).floor() as u32).min(width - 1);
    let y = ((crop.top * height as f64).floor() as u32).min(height - 1);
    let right = ((crop.right * width as f64).ceil() as u32).clamp(x + 1, width);
    let bottom = ((crop.bottom * height as f64).ceil() as u32).clamp(y + 1, height);
    Ok(CropPixels { x, y, width: right - x, height: bottom - y })
}

/// Map normalized edges into the displayed orientation. Invert with 4 - quarter_turns.
#[uniffi::export]
pub fn page_crop_rotated(mut crop: PageCrop, quarter_turns: u32) -> Result<PageCrop, ScanError> {
    crop.validate()?;
    if quarter_turns > 4 { return Err(ScanError::InvalidDocument); }
    for _ in 0..quarter_turns {
        crop = PageCrop { left: 1.0 - crop.bottom, top: crop.left, right: 1.0 - crop.top, bottom: crop.right };
    }
    Ok(crop)
}
