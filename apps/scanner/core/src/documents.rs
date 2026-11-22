//! Documents and pages (Swift `Documents.swift`): the models, their validation and search, and
//! the pure rules `DocumentStore` applies around its file I/O. The store itself (folders, trash,
//! atomic writes, file sizes on disk) stays native and calls these.

use crate::json::{self, parse_uuid, swift_int, uuid_string};
use crate::settings::{LibraryOrder, PageSettings};
use crate::text::{characters, standard_contains, trimmed};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

/// Swift's `ScanError`, plus `Unreadable` where Swift threw a `DecodingError` (JSON that does not
/// decode as the expected type).
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum ScanError {
    #[error("invalid document")]
    InvalidDocument,
    #[error("too large")]
    TooLarge,
    #[error("missing page")]
    MissingPage,
    #[error("unreadable")]
    Unreadable,
}

/// Limits the store and backup enforce, in one place.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ScanLimits {
    pub max_pages: i64,
    pub max_title_characters: i64,
    pub max_page_text_characters: i64,
    pub max_note_characters: i64,
    /// One page image.
    pub max_page_bytes: i64,
    /// All of one document's page images together.
    pub max_document_image_bytes: i64,
    /// `document.json`.
    pub max_manifest_bytes: i64,
    pub max_backup_bytes: i64,
    pub max_backup_index_bytes: i64,
    pub max_backup_documents: i64,
}

pub const MAX_PAGES: usize = 50;
pub const MAX_PAGE_BYTES: u64 = 20_000_000;
pub const MAX_DOCUMENT_IMAGE_BYTES: u64 = 100_000_000;
pub const MAX_MANIFEST_BYTES: u64 = 6_000_000;

#[uniffi::export]
pub fn scan_limits() -> ScanLimits {
    ScanLimits {
        max_pages: MAX_PAGES as i64,
        max_title_characters: 200,
        max_page_text_characters: 100_000,
        max_note_characters: 10_000,
        max_page_bytes: MAX_PAGE_BYTES as i64,
        max_document_image_bytes: MAX_DOCUMENT_IMAGE_BYTES as i64,
        max_manifest_bytes: MAX_MANIFEST_BYTES as i64,
        max_backup_bytes: crate::backup::MAX_BACKUP_BYTES as i64,
        max_backup_index_bytes: crate::backup::MAX_INDEX_BYTES as i64,
        max_backup_documents: crate::backup::MAX_BACKUP_DOCUMENTS as i64,
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ScanPage {
    #[serde(with = "uuid_string")]
    pub id: Uuid,
    pub text: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub adjustments: Option<PageSettings>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub annotations: Vec<crate::PageAnnotation>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub ink: Vec<crate::PageInk>,
}

impl ScanPage {
    /// Swift `settings`: the adjustments, or the defaults when there are none.
    pub fn settings(&self) -> PageSettings {
        self.adjustments.unwrap_or_default()
    }
}

/// `ScanPage(text:)`: a new ID, no adjustments.
#[uniffi::export]
pub fn new_scan_page(text: String) -> ScanPage {
    ScanPage {
        id: Uuid::new_v4(),
        text,
        adjustments: None,
        annotations: vec![],
        ink: vec![],
    }
}

#[uniffi::export]
pub fn scan_page_settings(page: &ScanPage) -> PageSettings {
    page.settings()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ScanDocument {
    #[serde(with = "swift_int")]
    pub version: i64,
    #[serde(with = "uuid_string")]
    pub id: Uuid,
    pub title: String,
    /// Seconds since 2001-01-01 00:00:00 UTC (Swift `Date.timeIntervalSinceReferenceDate`).
    pub created: f64,
    pub pages: Vec<ScanPage>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub favorite: Option<bool>,
}

/// `ScanDocument(title:pages:created:)`: version 1, a new ID, no note or favourite.
#[uniffi::export]
pub fn new_scan_document(title: String, pages: Vec<ScanPage>, created: f64) -> ScanDocument {
    ScanDocument {
        version: 1,
        id: Uuid::new_v4(),
        title,
        created,
        pages,
        note: None,
        favorite: None,
    }
}

impl ScanDocument {
    pub fn validate(&self) -> Result<(), ScanError> {
        let ids: HashSet<Uuid> = self.pages.iter().map(|p| p.id).collect();
        let ok = (1..=4).contains(&self.version)
            && (self.version >= 2 || self.pages.iter().all(|p| p.settings().crop.is_none()))
            && (self.version >= 3 || self.pages.iter().all(|p| p.annotations.is_empty()))
            && (self.version >= 4 || self.pages.iter().all(|p| p.ink.is_empty()))
            && self.pages.iter().flat_map(|p| &p.ink).map(|s| s.points.len()).sum::<usize>() <= 100_000
            && !trimmed(&self.title).is_empty()
            && characters(&self.title) <= 200
            && !self.pages.is_empty()
            && self.pages.len() <= MAX_PAGES
            && ids.len() == self.pages.len()
            && self.pages.iter().all(|p| characters(&p.text) <= 100_000)
            && self.note.as_deref().map(characters).unwrap_or(0) <= 10_000
            && (self.created + 978_307_200.0).is_finite();
        if !ok {
            return Err(ScanError::InvalidDocument);
        }
        for page in &self.pages {
            page.settings().validate()?;
            crate::annotations_validate(&page.annotations)?;
            crate::ink_validate(&page.ink)?;
        }
        Ok(())
    }

    /// Title, note and every page's text, case- and diacritic-insensitive. An empty query matches.
    pub fn matches(&self, query: &str) -> bool {
        if query.is_empty() {
            return true;
        }
        let mut parts = vec![self.title.as_str(), self.note.as_deref().unwrap_or("")];
        parts.extend(self.pages.iter().map(|p| p.text.as_str()));
        standard_contains(&parts.join("\n"), query)
    }
}

#[uniffi::export]
pub fn document_validate(document: &ScanDocument) -> Result<(), ScanError> {
    document.validate()
}

#[uniffi::export]
pub fn document_matches(document: &ScanDocument, query: String) -> bool {
    document.matches(&query)
}

/// The bytes Swift's `JSONEncoder().encode(document)` writes.
#[uniffi::export]
pub fn document_encode(document: &ScanDocument) -> Result<Vec<u8>, ScanError> {
    json::encode(document).map_err(|_| ScanError::InvalidDocument)
}

/// `JSONDecoder().decode(ScanDocument.self, from:)`, without validation.
#[uniffi::export]
pub fn document_decode(data: &[u8]) -> Result<ScanDocument, ScanError> {
    serde_json::from_slice(data).map_err(|_| ScanError::Unreadable)
}

// MARK: Store rules

/// A library folder is named by a document's UUID (either case); anything else is ignored.
#[uniffi::export]
pub fn is_document_folder(name: String) -> bool {
    parse_uuid(&name).is_some()
}

/// Staging left by an interrupted create (`.pending-`) or restore (`.restore-`).
#[uniffi::export]
pub fn is_staging_folder(name: String) -> bool {
    name.starts_with(".pending-") || name.starts_with(".restore-")
}

/// A page's image file inside its document folder: `<PAGE-UUID>.jpg`.
#[uniffi::export]
pub fn page_file_name(page_id: Uuid) -> String {
    format!("{}.jpg", page_id.hyphenated().to_string().to_uppercase())
}

/// The folder name for a document: its `uuidString`.
#[uniffi::export]
pub fn document_folder_name(document_id: Uuid) -> String {
    document_id.hyphenated().to_string().to_uppercase()
}

/// Reads a library folder's `document.json`: no larger than 6 MB, decodes, validates, and its ID
/// is the folder's name exactly (`uuidString`). Any failure makes the document unreadable.
#[uniffi::export]
pub fn manifest_read(data: &[u8], folder_name: String) -> Result<ScanDocument, ScanError> {
    if data.len() as u64 > MAX_MANIFEST_BYTES {
        return Err(ScanError::TooLarge);
    }
    let document = document_decode(data)?;
    document.validate()?;
    if document_folder_name(document.id) != folder_name {
        return Err(ScanError::InvalidDocument);
    }
    Ok(document)
}

/// The manifest bytes to write: the Swift encoding, refused above 6 MB. Validate first (and check
/// that every page's image exists, which only the store can), as `save` does.
#[uniffi::export]
pub fn manifest_encode(document: &ScanDocument) -> Result<Vec<u8>, ScanError> {
    let data = document_encode(document)?;
    if data.len() as u64 > MAX_MANIFEST_BYTES {
        return Err(ScanError::TooLarge);
    }
    Ok(data)
}

/// File names in a document folder that are page images the manifest no longer lists. Removed
/// only after the manifest that dropped them is committed.
#[uniffi::export]
pub fn unlisted_page_files(document: &ScanDocument, file_names: Vec<String>) -> Vec<String> {
    let keep: HashSet<String> = document
        .pages
        .iter()
        .map(|p| page_file_name(p.id))
        .collect();
    file_names
        .into_iter()
        .filter(|name| path_extension(name) == "jpg" && !keep.contains(name))
        .collect()
}

/// `URL.pathExtension` for a plain file name: after the last dot, none for a dot file.
fn path_extension(name: &str) -> &str {
    match name.rfind('.') {
        Some(0) | None => "",
        Some(i) => &name[i + 1..],
    }
}

fn check_images(sizes: &[u64]) -> bool {
    sizes.iter().all(|&s| s > 0 && s <= MAX_PAGE_BYTES)
}

/// `create`'s limits on the images: 1 to 50, one text per image, each 1 byte to 20 MB, 100 MB in
/// all. Checked before the document itself is validated.
#[uniffi::export]
pub fn check_new_document_images(image_sizes: Vec<u64>, text_count: u64) -> Result<(), ScanError> {
    let ok = !image_sizes.is_empty()
        && image_sizes.len() <= MAX_PAGES
        && image_sizes.len() as u64 == text_count
        && check_images(&image_sizes)
        && image_sizes.iter().sum::<u64>() <= MAX_DOCUMENT_IMAGE_BYTES;
    if ok { Ok(()) } else { Err(ScanError::TooLarge) }
}

/// A new document from scanned pages: `create` checks the images, then the document.
#[uniffi::export]
pub fn new_document_from_scan(
    title: String,
    image_sizes: Vec<u64>,
    text: Vec<String>,
    created: f64,
) -> Result<ScanDocument, ScanError> {
    check_new_document_images(image_sizes, text.len() as u64)?;
    let document = new_scan_document(
        title,
        text.into_iter().map(new_scan_page).collect(),
        created,
    );
    document.validate()?;
    Ok(document)
}

/// `append`'s limits: at least one image, 50 pages at most, each image 1 byte to 20 MB (all
/// checked first), then the document's existing image bytes plus the new ones within 100 MB.
#[uniffi::export]
pub fn check_append_images(
    existing_page_count: u64,
    image_sizes: Vec<u64>,
    existing_bytes: u64,
) -> Result<(), ScanError> {
    let ok = !image_sizes.is_empty()
        && existing_page_count.saturating_add(image_sizes.len() as u64) <= MAX_PAGES as u64
        && check_images(&image_sizes);
    if !ok {
        return Err(ScanError::TooLarge);
    }
    if image_sizes
        .iter()
        .fold(existing_bytes, |a, b| a.saturating_add(*b))
        > MAX_DOCUMENT_IMAGE_BYTES
    {
        return Err(ScanError::TooLarge);
    }
    Ok(())
}

/// `updatePage`: settings are checked first; the page's OCR text is cleared, because it was read
/// from the image as it looked before.
#[uniffi::export]
pub fn document_with_page_settings(
    document: &ScanDocument,
    page_id: Uuid,
    settings: PageSettings,
) -> Result<ScanDocument, ScanError> {
    settings.validate()?;
    let mut next = document.clone();
    let page = next
        .pages
        .iter_mut()
        .find(|p| p.id == page_id)
        .ok_or(ScanError::MissingPage)?;
    if settings.crop.is_some() { next.version = next.version.max(2); }
    page.adjustments = Some(settings);
    page.text = String::new();
    Ok(next)
}

/// Correct saved text without changing the original image or newer edits to this page.
#[uniffi::export]
pub fn document_with_page_text(
    document: &ScanDocument,
    expected: ScanPage,
    text: String,
) -> Result<ScanDocument, ScanError> {
    let mut next = document.clone();
    let page = next
        .pages
        .iter_mut()
        .find(|page| page.id == expected.id)
        .ok_or(ScanError::MissingPage)?;
    if page.text != expected.text || page.settings() != expected.settings() {
        return Err(ScanError::InvalidDocument);
    }
    page.text = text;
    next.validate()?;
    Ok(next)
}

/// Native recognition jobs supply their starting pages. Even explicit retry must preserve a
/// correction saved while that job ran; settings alone cannot detect a concurrent text edit.
#[uniffi::export]
pub fn document_apply_recognition(
    document: &ScanDocument,
    expected_pages: Vec<ScanPage>,
    by_page: HashMap<Uuid, String>,
    overwrite: bool,
) -> ScanDocument {
    let expected: HashMap<_, _> = expected_pages
        .into_iter()
        .map(|page| (page.id, page))
        .collect();
    let allowed = document
        .pages
        .iter()
        .filter_map(|page| {
            let old = expected.get(&page.id)?;
            if page.text != old.text || page.settings() != old.settings() {
                return None;
            }
            by_page.get(&page.id).map(|text| (page.id, text.clone()))
        })
        .collect();
    document_fill_recognized_text(document, allowed, None, overwrite)
}

/// `setPageOrder`: the listed pages in that order; unlisted pages are removed. When
/// `expected_page_ids` is given it must be the document's current set of pages, so a reorder
/// made on a stale copy is refused. The result still needs validating, as `save` does.
#[uniffi::export]
pub fn document_with_page_order(
    document: &ScanDocument,
    page_ids: Vec<Uuid>,
    expected_page_ids: Option<Vec<Uuid>>,
) -> Result<ScanDocument, ScanError> {
    let current: HashSet<Uuid> = document.pages.iter().map(|p| p.id).collect();
    let listed: HashSet<Uuid> = page_ids.iter().copied().collect();
    let expected_ok = expected_page_ids
        .map(|e| e.into_iter().collect::<HashSet<_>>() == current)
        .unwrap_or(true);
    if !expected_ok
        || page_ids.is_empty()
        || listed.len() != page_ids.len()
        || !listed.is_subset(&current)
    {
        return Err(ScanError::InvalidDocument);
    }
    let by_id: HashMap<Uuid, &ScanPage> = document.pages.iter().map(|p| (p.id, p)).collect();
    let mut next = document.clone();
    next.pages = page_ids
        .iter()
        .filter_map(|id| by_id.get(id).map(|p| (*p).clone()))
        .collect();
    Ok(next)
}

/// `updateMetadata`: title, note and favourite as given (an empty note is kept as `""`).
#[uniffi::export]
pub fn document_with_metadata(
    document: &ScanDocument,
    title: String,
    note: String,
    favorite: bool,
) -> ScanDocument {
    ScanDocument {
        title,
        note: Some(note),
        favorite: Some(favorite),
        ..document.clone()
    }
}

/// `fillRecognizedText`: OCR completion fills only empty pages (all pages with `overwrite`), and
/// only where the page still has the settings recognition ran with, so it never overwrites a
/// rename, reorder or later image edit made while recognition ran. Native jobs use
/// `document_apply_recognition` to additionally protect concurrent text corrections.
#[uniffi::export]
pub fn document_fill_recognized_text(
    document: &ScanDocument,
    by_page: HashMap<Uuid, String>,
    expected_settings: Option<HashMap<Uuid, PageSettings>>,
    overwrite: bool,
) -> ScanDocument {
    let mut next = document.clone();
    for page in next
        .pages
        .iter_mut()
        .filter(|p| p.text.is_empty() || overwrite)
    {
        if let Some(text) = by_page.get(&page.id) {
            let current = page.settings();
            if expected_settings
                .as_ref()
                .map(|e| e.get(&page.id) == Some(&current))
                .unwrap_or(true)
            {
                page.text = text.clone();
            }
        }
    }
    next
}

/// `readLibrary`: the readable documents in order, and how many folders could not be read.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct LibraryListing {
    pub documents: Vec<ScanDocument>,
    pub unreadable_count: i64,
}

#[uniffi::export]
pub fn library_listing(documents: Vec<ScanDocument>, unreadable_count: i64) -> LibraryListing {
    LibraryListing {
        documents: LibraryOrder::Newest.sorted(&documents),
        unreadable_count,
    }
}
