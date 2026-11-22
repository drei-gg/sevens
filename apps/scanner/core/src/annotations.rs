//! Portable rectangular marks in the upright original. Native canvases draw the shared geometry.
use crate::{PageCrop, PageSettings, ScanDocument, ScanError, ScanPage, page_crop_rotated};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum AnnotationStyle {
    Highlight,
    RedOutline,
    BlueOutline,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PageAnnotation {
    pub bounds: PageCrop,
    pub style: AnnotationStyle,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct AnnotationRect {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

#[uniffi::export]
pub fn annotations_validate(annotations: &[PageAnnotation]) -> Result<(), ScanError> {
    if annotations.len() > 100 {
        return Err(ScanError::TooLarge);
    }
    for mark in annotations {
        mark.bounds.validate()?;
    }
    Ok(())
}

/// Output may extend beyond the visible page: native canvases clip it, without deleting marks.
#[uniffi::export]
pub fn annotation_display_bounds(
    bounds: PageCrop,
    settings: PageSettings,
) -> Result<AnnotationRect, ScanError> {
    settings.validate()?;
    let turns = settings.quarter_turns as u32;
    let mark = page_crop_rotated(bounds, turns)?;
    let crop = page_crop_rotated(
        settings.crop.unwrap_or(PageCrop {
            left: 0.0,
            top: 0.0,
            right: 1.0,
            bottom: 1.0,
        }),
        turns,
    )?;
    Ok(AnnotationRect {
        x: (mark.left - crop.left) / (crop.right - crop.left),
        y: (mark.top - crop.top) / (crop.bottom - crop.top),
        width: (mark.right - mark.left) / (crop.right - crop.left),
        height: (mark.bottom - mark.top) / (crop.bottom - crop.top),
    })
}

/// A rectangle entered/drawn against the displayed crop, back into upright-original coordinates.
#[uniffi::export]
pub fn annotation_from_display(
    bounds: PageCrop,
    settings: PageSettings,
    style: AnnotationStyle,
) -> Result<PageAnnotation, ScanError> {
    let bounds = snapped(bounds);
    bounds.validate()?;
    settings.validate()?;
    let crop = page_crop_rotated(
        settings.crop.unwrap_or(PageCrop {
            left: 0.0,
            top: 0.0,
            right: 1.0,
            bottom: 1.0,
        }),
        settings.quarter_turns as u32,
    )?;
    let width = crop.right - crop.left;
    let height = crop.bottom - crop.top;
    let rotated = PageCrop {
        left: crop.left + bounds.left * width,
        top: crop.top + bounds.top * height,
        right: crop.left + bounds.right * width,
        bottom: crop.top + bounds.bottom * height,
    };
    Ok(PageAnnotation {
        bounds: page_crop_rotated(snapped(rotated), (4 - settings.quarter_turns) as u32)?,
        style,
    })
}

/// Native drags and percentage entry reach a crop's edge through float arithmetic, which can land a
/// rounding error past 0 or 1. Snap those to the edge; anything further out stays invalid.
fn snapped(bounds: PageCrop) -> PageCrop {
    const EDGE: f64 = 1e-9;
    let snap = |v: f64| {
        if v < 0.0 && v > -EDGE {
            0.0
        } else if v > 1.0 && v < 1.0 + EDGE {
            1.0
        } else {
            v
        }
    };
    PageCrop {
        left: snap(bounds.left),
        top: snap(bounds.top),
        right: snap(bounds.right),
        bottom: snap(bounds.bottom),
    }
}

/// Merge only marks; preserve text/metadata changes, but reject changed geometry or annotations.
#[uniffi::export]
pub fn document_with_annotations(
    document: &ScanDocument,
    expected: ScanPage,
    annotations: Vec<PageAnnotation>,
) -> Result<ScanDocument, ScanError> {
    let ink = expected.ink.clone();
    document_with_page_marks(document, expected, annotations, ink)
}

#[uniffi::export]
pub fn document_with_page_marks(document: &ScanDocument, expected: ScanPage, annotations: Vec<PageAnnotation>, ink: Vec<crate::PageInk>) -> Result<ScanDocument, ScanError> {
    annotations_validate(&annotations)?;
    crate::ink_validate(&ink)?;
    let mut next = document.clone();
    let page = next
        .pages
        .iter_mut()
        .find(|p| p.id == expected.id)
        .ok_or(ScanError::MissingPage)?;
    if page.settings() != expected.settings() || page.annotations != expected.annotations || page.ink != expected.ink {
        return Err(ScanError::InvalidDocument);
    }
    page.annotations = annotations;
    page.ink = ink;
    if !page.ink.is_empty() { next.version = next.version.max(4); }
    if !page.annotations.is_empty() {
        next.version = next.version.max(3);
    }
    next.validate()?;
    Ok(next)
}

/// A bounded portable draft for native saved-state restoration, not a second platform format.
#[uniffi::export]
pub fn annotations_encode(annotations: Vec<PageAnnotation>) -> Result<String, ScanError> {
    annotations_validate(&annotations)?;
    serde_json::to_string(&annotations).map_err(|_| ScanError::InvalidDocument)
}
#[uniffi::export]
pub fn annotations_decode(data: String) -> Result<Vec<PageAnnotation>, ScanError> {
    if data.len() > 100_000 {
        return Err(ScanError::TooLarge);
    }
    let marks: Vec<PageAnnotation> =
        serde_json::from_str(&data).map_err(|_| ScanError::Unreadable)?;
    annotations_validate(&marks)?;
    Ok(marks)
}
