//! PDF export options (Swift `ExportOptions.swift`). Rendering the PDF stays native.

use crate::documents::{ScanDocument, ScanError, ScanPage};
use crate::text::{is_control, is_whitespace_or_newline, prefix_characters};
use std::collections::HashSet;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PdfQuality {
    Standard,
    Compact,
}

#[uniffi::export]
pub fn pdf_quality_raw_value(quality: PdfQuality) -> String {
    match quality {
        PdfQuality::Standard => "standard",
        PdfQuality::Compact => "compact",
    }
    .into()
}

#[uniffi::export]
pub fn pdf_quality_from_raw_value(raw: String) -> Option<PdfQuality> {
    match raw.as_str() {
        "standard" => Some(PdfQuality::Standard),
        "compact" => Some(PdfQuality::Compact),
        _ => None,
    }
}

/// The pages to export, in document order, and a file name that is safe on every file system.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PdfExportPlan {
    pub pages: Vec<ScanPage>,
    pub filename: String,
}

/// At least one page must be selected and every selected ID must be a page of the document. The
/// name is the title with control characters and `/\:?*<>|"` replaced by spaces, trimmed of
/// whitespace and dots, at most 100 characters ("Scan" when nothing is left), with
/// " - selected pages" when not every page is exported.
#[uniffi::export]
pub fn pdf_export_plan(
    document: &ScanDocument,
    selected: Vec<Uuid>,
) -> Result<PdfExportPlan, ScanError> {
    let selected: HashSet<Uuid> = selected.into_iter().collect();
    let pages: Vec<ScanPage> = document
        .pages
        .iter()
        .filter(|p| selected.contains(&p.id))
        .cloned()
        .collect();
    let ids: HashSet<Uuid> = document.pages.iter().map(|p| p.id).collect();
    if pages.is_empty() || !selected.is_subset(&ids) {
        return Err(ScanError::InvalidDocument);
    }
    let disallowed = |c: char| is_control(c) || "/\\:?*<>|\"".contains(c);
    let joined = document
        .title
        .split(disallowed)
        .collect::<Vec<_>>()
        .join(" ");
    let cleaned = joined.trim_matches(|c: char| is_whitespace_or_newline(c) || c == '.');
    let base = if cleaned.is_empty() {
        "Scan"
    } else {
        prefix_characters(cleaned, 100)
    };
    let suffix = if pages.len() == document.pages.len() {
        ""
    } else {
        " - selected pages"
    };
    Ok(PdfExportPlan {
        filename: format!("{base}{suffix}.pdf"),
        pages,
    })
}
