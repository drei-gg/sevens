//! The pure rules inside CleanerPlatform: revision strings, photo tags and fingerprints, email
//! keys and display names, largest-first ordering, PSNR arithmetic, the similarity window, the
//! compression profile's gates and the contacts export package. Everything that touches Photos,
//! Contacts, Files, ImageIO, Vision, CryptoKit or PhoneNumberKit stays native.

use crate::codec::{data_map, distant_past, interval};
use crate::library::{CleanupItem, MatchGroup, MatchKind};
use crate::preservation::{CompressionAssessment, ContactRecord, assessment_can_offer_copy};
use crate::text::{canonical_set, swift_cmp, swift_double, trimmed};
use crate::{CleanerError, blocked};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::SystemTime;

// MARK: Revisions (FilesService, PhotosService)

/// FilesService's revision: `"\(modified?.timeIntervalSince1970 ?? 0):\(fileSize ?? -1)"`.
/// Takes `timeIntervalSince1970` itself so the digits match what Swift stored, to the last bit.
#[uniffi::export]
pub fn file_revision(modified_since_1970: Option<f64>, file_size: Option<i64>) -> String {
    format!(
        "{}:{}",
        swift_double(modified_since_1970.unwrap_or(0.0)),
        file_size.unwrap_or(-1)
    )
}

/// PhotosService's revision: `"\(modificationDate?.timeIntervalSince1970 ?? 0):\(w)x\(h):\(resources)"`.
#[uniffi::export]
pub fn photo_revision(
    modified_since_1970: Option<f64>,
    pixel_width: i32,
    pixel_height: i32,
    resource_count: i32,
) -> String {
    format!(
        "{}:{}x{}:{}",
        swift_double(modified_since_1970.unwrap_or(0.0)),
        pixel_width,
        pixel_height,
        resource_count
    )
}

// MARK: Photos

/// The asset traits PhotosService turns into collection tags.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Record)]
pub struct PhotoTraits {
    pub screenshot: bool,
    pub live: bool,
    pub screen_recording: bool,
    pub high_frame_rate: bool,
    pub hdr: bool,
    pub burst: bool,
}

/// PhotosService's tags for an asset, sorted (a Swift `Set`).
#[uniffi::export]
pub fn photo_tags(traits: PhotoTraits) -> Vec<String> {
    let pairs = [
        (traits.screenshot, "Screenshots"),
        (traits.live, "Live Photos"),
        (traits.screen_recording, "Screen recordings"),
        (traits.high_frame_rate, "High frame rate"),
        (traits.hdr, "HDR"),
        (traits.burst, "Bursts"),
    ];
    canonical_set(
        pairs
            .iter()
            .filter(|(on, _)| *on)
            .map(|(_, tag)| tag.to_string())
            .collect(),
    )
}

/// One resource's part of a photo fingerprint: `"\(type.rawValue):\(uti):\(sha256)"`.
#[uniffi::export]
pub fn photo_resource_part(
    resource_type: i64,
    uniform_type_identifier: String,
    sha256: String,
) -> String {
    format!("{resource_type}:{uniform_type_identifier}:{sha256}")
}

/// A photo's fingerprint: its resource parts sorted and joined with `|`.
#[uniffi::export]
pub fn photo_fingerprint(parts: Vec<String>) -> String {
    let mut parts = parts;
    parts.sort_by(|a, b| swift_cmp(a, b));
    parts.join("|")
}

/// FilesService's ordering: largest first, unknown sizes last, otherwise as given.
#[uniffi::export]
pub fn sort_largest_first(items: Vec<CleanupItem>) -> Vec<CleanupItem> {
    let mut items = items;
    items.sort_by(|a, b| b.bytes.unwrap_or(-1).cmp(&a.bytes.unwrap_or(-1)));
    items
}

/// `addToAlbum`'s guard: every requested item is accessible and the title is not blank.
#[uniffi::export]
pub fn validate_album_request(
    requested: u32,
    accessible: u32,
    title: String,
) -> Result<(), CleanerError> {
    if accessible != requested || trimmed(&title).is_empty() {
        return Err(blocked("Choose accessible media and an album name."));
    }
    Ok(())
}

/// An Android album is a folder: the title as a single folder name under Pictures, or a refusal.
/// Trimmed; no path separators, control characters, leading dot or `.`/`..`; at most 100 characters.
#[uniffi::export]
pub fn album_folder_name(title: String) -> Result<String, CleanerError> {
    let name = trimmed(&title).to_string();
    let bad = name.is_empty()
        || name == "."
        || name == ".."
        || name.starts_with('.')
        || name.chars().count() > 100
        || name.chars().any(|c| {
            c == '/'
                || c == '\\'
                || c.is_control()
                || matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*')
        });
    if bad {
        return Err(blocked(
            "Choose an album name without slashes or special characters, up to 100 characters.",
        ));
    }
    Ok(name)
}

/// Where an Android folder album lives: `Pictures/<name>/`, for photos and videos alike.
#[uniffi::export]
pub fn album_relative_path(title: String) -> Result<String, CleanerError> {
    Ok(format!("Pictures/{}/", album_folder_name(title)?))
}

// MARK: Similarity (PhotosService.similar)

/// Vision feature-print distances below this are suggested as similar.
pub const SIMILAR_DISTANCE: f32 = 0.2;
const SIMILAR_WINDOW_SECONDS: f64 = 60.0;
const SIMILAR_WINDOW_COUNT: usize = 20;

/// Photos in the order the similarity pass visits them: oldest first, undated first (and then
/// skipped), otherwise as given.
#[uniffi::export]
pub fn similarity_order(items: Vec<CleanupItem>) -> Vec<CleanupItem> {
    let past = distant_past();
    let mut photos: Vec<CleanupItem> = items
        .into_iter()
        .filter(|i| i.kind == crate::MediaKind::Photo)
        .collect();
    photos.sort_by(|a, b| a.date.unwrap_or(past).cmp(&b.date.unwrap_or(past)));
    photos
}

/// The suggestion for a pair, or `None` at or above the threshold. Review only; never preselected.
#[uniffi::export]
pub fn similar_group(earlier_id: String, later_id: String, distance: f32) -> Option<MatchGroup> {
    if !(distance < SIMILAR_DISTANCE) {
        return None;
    }
    Some(MatchGroup {
        id: format!("similar:{earlier_id}:{later_id}"),
        kind: MatchKind::Similar,
        members: vec![earlier_id, later_id],
        reason: format!(
            "Nearby capture times and Vision distance {:.3}. Inspect both; different moments may look alike.",
            distance as f64
        ),
    })
}

/// The sliding window of recent photos: at most 20, at most 60 seconds older than the newest.
/// For each dated photo, in [`similarity_order`]: `prune(date)` gives the ids to compare against;
/// if a feature print was made, `admit(id, date)` adds the photo after the comparisons.
#[derive(Debug, Default, uniffi::Object)]
pub struct SimilarityWindow {
    recent: Mutex<Vec<(String, SystemTime)>>,
}

#[uniffi::export]
impl SimilarityWindow {
    #[uniffi::constructor]
    pub fn new() -> Self {
        Self::default()
    }

    pub fn prune(&self, date: SystemTime) -> Vec<String> {
        let mut recent = self.recent.lock().unwrap();
        recent.retain(|(_, seen)| interval(date, *seen) <= SIMILAR_WINDOW_SECONDS);
        if recent.len() > SIMILAR_WINDOW_COUNT {
            let excess = recent.len() - SIMILAR_WINDOW_COUNT;
            recent.drain(..excess);
        }
        recent.iter().map(|(id, _)| id.clone()).collect()
    }

    pub fn admit(&self, id: String, date: SystemTime) {
        self.recent.lock().unwrap().push((id, date));
    }
}

// MARK: Compression (ImageCompression)

/// PSNR's peak, 255².
const PEAK: f64 = 65025.0;

/// One 128-pixel tile's error: mean squared difference over its RGB samples.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct TileError {
    pub mean_squared_error: f64,
    pub samples: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PsnrScores {
    pub full_resolution: f64,
    pub worst_tile: f64,
}

/// A tile's error from two RGBX buffers of the same size (every fourth byte is skipped).
#[uniffi::export]
pub fn tile_error(original_rgbx: &[u8], output_rgbx: &[u8]) -> Result<TileError, CleanerError> {
    if original_rgbx.len() != output_rgbx.len() || original_rgbx.len() % 4 != 0 {
        return Err(blocked("Could not compare full-resolution detail."));
    }
    let mut sum = 0.0;
    let mut samples = 0u64;
    for (i, (a, b)) in original_rgbx.iter().zip(output_rgbx).enumerate() {
        if i % 4 == 3 {
            continue;
        }
        let d = *a as f64 - *b as f64;
        sum += d * d;
        samples += 1;
    }
    let mean_squared_error = if samples == 0 {
        f64::NAN
    } else {
        sum / samples as f64
    };
    Ok(TileError {
        mean_squared_error,
        samples,
    })
}

fn psnr(mse: f64) -> f64 {
    if mse == 0.0 {
        120.0
    } else {
        10.0 * (PEAK / mse).log10()
    }
}

/// Whole-image PSNR (sample-weighted) and the worst tile's, which catches local damage a global
/// average hides. 120 stands for identical. A diagnostic, never perceptual approval.
#[uniffi::export]
pub fn psnr_scores(tiles: &[TileError]) -> PsnrScores {
    let mut total = 0.0;
    let mut count = 0u64;
    let mut worst = f64::INFINITY;
    for tile in tiles {
        total += tile.mean_squared_error * tile.samples as f64;
        count += tile.samples;
        worst = worst.min(psnr(tile.mean_squared_error));
    }
    PsnrScores {
        full_resolution: psnr(total / count as f64),
        worst_tile: worst,
    }
}

const PROFILE_SOURCE: &str = "This profile accepts JPEG or PNG stills up to 16 megapixels. Other profiles still need validation.";
const PROFILE_PIXELS: &str = "This profile preserves 8-bit sRGB images without transparency. This image needs a different profile.";

/// The initial profile's source gate, in Swift's order: quality 0.9...1, one frame, JPEG or PNG,
/// positive dimensions, at most 16 MP.
#[uniffi::export]
pub fn check_compression_source(
    quality: f64,
    frame_count: u32,
    type_identifier: String,
    width: i64,
    height: i64,
) -> Result<(), CleanerError> {
    let accepted = (0.9..=1.0).contains(&quality)
        && frame_count == 1
        && (type_identifier == "public.jpeg" || type_identifier == "public.png")
        && width > 0
        && height > 0
        && width <= 16_000_000 / height;
    if accepted {
        Ok(())
    } else {
        Err(blocked(PROFILE_SOURCE))
    }
}

/// The initial profile's pixel gate: 8 bits per component, sRGB, no alpha.
#[uniffi::export]
pub fn check_compression_pixels(
    bits_per_component: i32,
    is_srgb: bool,
    has_alpha: bool,
) -> Result<(), CleanerError> {
    if bits_per_component == 8 && is_srgb && !has_alpha {
        Ok(())
    } else {
        Err(blocked(PROFILE_PIXELS))
    }
}

/// The refusal when an image carries depth, matte or HDR gain-map data.
#[uniffi::export]
pub fn auxiliary_data_refusal() -> CleanerError {
    blocked(
        "This image contains auxiliary or HDR data. Keep the original; this profile cannot preserve it yet.",
    )
}

/// `saveCopy`'s first guard: only a candidate that passed the profile is saved.
#[uniffi::export]
pub fn check_copy_offer(assessment: &CompressionAssessment) -> Result<(), CleanerError> {
    if assessment_can_offer_copy(assessment) {
        Ok(())
    } else {
        Err(blocked("This candidate did not pass the current profile."))
    }
}

// MARK: Contacts (ContactsService)

/// An email's match key: trimmed, exactly one `@` with text on both sides, domain lowercased.
/// The local part keeps its case: provider-specific equivalence is not asserted.
#[uniffi::export]
pub fn email_key(raw: String) -> Option<String> {
    let parts: Vec<&str> = trimmed(&raw).split('@').collect();
    if parts.len() != 2 || parts[0].is_empty() || parts[1].is_empty() {
        return None;
    }
    Some(format!("{}@{}", parts[0], parts[1].to_lowercase()))
}

/// A contact's display name: given, middle and family names joined with spaces, or the
/// organization when all three are empty.
#[uniffi::export]
pub fn contact_display_name(
    given: String,
    middle: String,
    family: String,
    organization: String,
) -> String {
    let name = [given, middle, family]
        .into_iter()
        .filter(|p| !p.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    if name.is_empty() { organization } else { name }
}

/// ContactsService's order for a record's fields: by key only, otherwise as given.
#[uniffi::export]
pub fn sort_contact_fields(fields: Vec<crate::ContactField>) -> Vec<crate::ContactField> {
    let mut fields = fields;
    fields.sort_by(|a, b| swift_cmp(&a.key, &b.key));
    fields
}

/// The contacts export: explicitly a partial archive, not a complete address-book backup.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ContactPackage {
    pub version: i32,
    pub unavailable: Vec<String>,
    pub records: Vec<ContactRecord>,
    /// Each contact's verified `NSKeyedArchiver` bytes, by contact identifier.
    #[serde(with = "data_map")]
    pub archives: HashMap<String, Vec<u8>>,
}

/// A package with Swift's version and list of what it cannot contain.
#[uniffi::export]
pub fn new_contact_package(
    records: Vec<ContactRecord>,
    archives: HashMap<String, Vec<u8>>,
) -> ContactPackage {
    ContactPackage {
        version: 1,
        unavailable: vec![
            "notes".into(),
            "account credentials".into(),
            "group membership and linked-contact relationships".into(),
        ],
        records,
        archives,
    }
}

#[uniffi::export]
pub fn contact_package_encode(package: &ContactPackage) -> Result<Vec<u8>, CleanerError> {
    serde_json::to_vec(package).map_err(|_| CleanerError::Unreadable)
}

#[uniffi::export]
pub fn contact_package_decode(data: &[u8]) -> Result<ContactPackage, CleanerError> {
    serde_json::from_slice(data).map_err(|_| CleanerError::Unreadable)
}

fn canonical_records(records: &[ContactRecord]) -> Vec<ContactRecord> {
    records
        .iter()
        .map(|r| ContactRecord {
            phone_keys: canonical_set(r.phone_keys.clone()),
            email_keys: canonical_set(r.email_keys.clone()),
            inaccessible_fields: canonical_set(r.inaccessible_fields.clone()),
            ..r.clone()
        })
        .collect()
}

/// `export`'s read-back check: the bytes read from disk must hold the same records and archives.
#[uniffi::export]
pub fn contact_package_verify(
    written: &ContactPackage,
    read_back: &[u8],
) -> Result<(), CleanerError> {
    let read = contact_package_decode(read_back)?;
    if canonical_records(&read.records) != canonical_records(&written.records)
        || read.archives != written.archives
    {
        return Err(blocked("Archive read-back did not match."));
    }
    Ok(())
}
