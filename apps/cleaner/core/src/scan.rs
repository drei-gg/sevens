//! Persistent measurement checkpoints. Native SQLite owns transactions; this module owns what may
//! be saved/reused. An accessible current inventory is always authoritative, never this cache.
use crate::{CleanerError, CleanupItem, blocked};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
struct Measurement {
    version: u32,
    id: String,
    revision: String,
    fingerprint: String,
    bytes: i64,
}

/// Include the provider's database epoch and per-asset generation. Providers without a
/// trustworthy generation remain comparable, but cannot retain reusable measurements.
#[uniffi::export]
pub fn scan_provider_revision(base: String, epoch: String, generation: i64) -> String {
    if generation < 0 || epoch.is_empty() {
        return format!("volatile:{base}");
    }
    format!("provider:{}:{}:{}:{}", epoch.len(), epoch, generation, base)
}

/// PhotoKit without a modification date cannot safely retain a measurement across launches.
#[uniffi::export]
pub fn scan_has_measurement(item: &CleanupItem) -> bool {
    !item.id.is_empty()
        && !item.revision.is_empty()
        && !item.revision.starts_with("0.0:")
        && !item.revision.starts_with("volatile:")
        && item.fingerprint.as_ref().is_some_and(|f| !f.is_empty())
        && item.bytes.is_some_and(|b| b >= 0)
}

#[uniffi::export]
pub fn scan_checkpoint(item: &CleanupItem) -> Result<Option<String>, CleanerError> {
    if !scan_has_measurement(item) {
        return Ok(None);
    }
    let row = Measurement {
        version: 1,
        id: item.id.clone(),
        revision: item.revision.clone(),
        fingerprint: item.fingerprint.clone().unwrap(),
        bytes: item.bytes.unwrap(),
    };
    let text = serde_json::to_string(&row).map_err(|_| CleanerError::Unreadable)?;
    if text.len() > 32_768 {
        return Err(blocked("This measurement is too large to checkpoint."));
    }
    Ok(Some(text))
}

#[uniffi::export]
pub fn scan_restore(item: &CleanupItem, checkpoint: String) -> Result<CleanupItem, CleanerError> {
    if checkpoint.len() > 32_768 {
        return Err(CleanerError::Unreadable);
    }
    let row: Measurement =
        serde_json::from_str(&checkpoint).map_err(|_| CleanerError::Unreadable)?;
    if row.version != 1 {
        return Err(blocked(
            "Saved scan progress needs a newer app. Clear scan progress to compare again.",
        ));
    }
    if row.id.is_empty() || row.revision.is_empty() || row.fingerprint.is_empty() || row.bytes < 0 {
        return Err(CleanerError::Unreadable);
    }
    let mut result = item.clone();
    if row.id == item.id
        && row.revision == item.revision
        && !row.revision.starts_with("0.0:")
        && !row.revision.starts_with("volatile:")
    {
        result.fingerprint = Some(row.fingerprint);
        result.bytes = Some(row.bytes);
    }
    Ok(result)
}
