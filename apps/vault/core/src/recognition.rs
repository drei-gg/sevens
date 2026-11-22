//! Bounded PDF recognition policy. Parsing, rendering and OCR remain native and on-device.
use crate::format::NOTE_MAX_BYTES;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RecognitionPlan {
    /// Zero-based page indices, empty for an invalid request.
    pub pages: Vec<u32>,
    pub issue: Option<String>,
    pub maximum_pages: u32,
    pub maximum_pixel_edge: u32,
    pub maximum_text_bytes: u64,
}
#[uniffi::export]
pub fn recognition_plan(page_count: u32, first_page: u32, last_page: u32) -> RecognitionPlan {
    let maximum_pages = 20;
    let issue = if page_count == 0 { Some("This PDF has no readable pages.".into()) }
        else if first_page == 0 || last_page < first_page || last_page > page_count { Some(format!("Choose a page range from 1 to {page_count}.")) }
        else if last_page - first_page + 1 > maximum_pages { Some("Choose up to 20 pages at a time.".into()) }
        else { None };
    let pages = if issue.is_none() { (first_page - 1..last_page).collect() } else { vec![] };
    RecognitionPlan { pages, issue, maximum_pages, maximum_pixel_edge: 2400, maximum_text_bytes: NOTE_MAX_BYTES }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum RecognitionFailure {
    #[error("The recognized text is too large for one note. Choose fewer pages.")]
    TooMuchText,
    #[error("This PDF page cannot be rendered.")]
    InvalidPage,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct RecognitionPageSize { pub width: u32, pub height: u32 }
#[uniffi::export]
pub fn recognition_page_size(width: f64, height: f64) -> Result<RecognitionPageSize, RecognitionFailure> {
    if !width.is_finite() || !height.is_finite() || width <= 0.0 || height <= 0.0 { return Err(RecognitionFailure::InvalidPage); }
    let longest = width.max(height);
    Ok(RecognitionPageSize { width: (width / longest * 2400.0).round().clamp(1.0, 2400.0) as u32, height: (height / longest * 2400.0).round().clamp(1.0, 2400.0) as u32 })
}
#[uniffi::export]
pub fn recognition_append(current: String, page_number: u32, text: String) -> Result<String, RecognitionFailure> {
    let text = text.trim();
    if current.len() as u64 > NOTE_MAX_BYTES { return Err(RecognitionFailure::TooMuchText); }
    if text.is_empty() { return Ok(current); }
    if page_number == 0 { return Err(RecognitionFailure::InvalidPage); }
    let header = format!("Page {page_number}\n");
    let separator = if current.is_empty() { 0 } else { 2 };
    if current.len().saturating_add(text.len()).saturating_add(header.len()).saturating_add(separator) as u64 > NOTE_MAX_BYTES { return Err(RecognitionFailure::TooMuchText); }
    let result = if current.is_empty() { format!("Page {page_number}\n{text}") } else { format!("{current}\n\nPage {page_number}\n{text}") };
    if result.len() as u64 > NOTE_MAX_BYTES { return Err(RecognitionFailure::TooMuchText); }
    Ok(result)
}

#[uniffi::export]
pub fn recognition_failure_description(error: RecognitionFailure) -> String { error.to_string() }

/// A session-only review queue. Native type registries identify renderable images/PDFs;
/// selection bounds and stale/trash checks are identical on both platforms.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RecognitionBatchPlan {
    pub items: Vec<crate::VaultItem>,
    pub issue: Option<String>,
    pub maximum_items: u32,
    pub maximum_pdf_pages: u32,
}
#[uniffi::export]
pub fn recognition_batch_plan(items: Vec<crate::VaultItem>, selected: Vec<uuid::Uuid>, supported: Vec<uuid::Uuid>) -> RecognitionBatchPlan {
    let ids: std::collections::HashSet<_> = selected.into_iter().collect();
    let supported: std::collections::HashSet<_> = supported.into_iter().collect();
    let chosen: Vec<_> = items.into_iter().filter(|item| ids.contains(&item.id)).collect();
    let issue = if ids.is_empty() { Some("Select images or PDFs to recognize.".into()) }
        else if ids.len() > 10 { Some("Select up to 10 files per review queue.".into()) }
        else if chosen.len() != ids.len() || chosen.iter().any(|item| item.deleted.is_some()) { Some("The selection changed. Select the files again.".into()) }
        else if chosen.iter().any(|item| !supported.contains(&item.id)) { Some("Select only images and PDFs. Notes and other files cannot be recognized.".into()) }
        else { None };
    RecognitionBatchPlan { items: if issue.is_none() { chosen } else { vec![] }, issue, maximum_items: 10, maximum_pdf_pages: recognition_plan(1,1,1).maximum_pages }
}
#[uniffi::export]
pub fn recognition_review_text(text: String) -> Result<String, RecognitionFailure> {
    if text.len() as u64 > NOTE_MAX_BYTES { return Err(RecognitionFailure::TooMuchText); }
    Ok(text)
}
