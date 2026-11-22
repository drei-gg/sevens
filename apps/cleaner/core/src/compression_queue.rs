//! Bounded, explicit per-photo review. Native providers still own every original and saved copy.
use crate::text::canonical_set;
use crate::{CleanerError, CleanupItem, HistoryEntry, MediaKind, ReviewState, blocked};
use std::collections::{HashMap, HashSet};

#[uniffi::export]
pub fn compression_batch_limit() -> u32 {
    10
}

#[uniffi::export]
pub fn compression_source_key(item: &CleanupItem) -> String {
    format!("{}|{}", item.id, item.revision)
}

/// Validate the full selection before opening a queue; preserve its explicit order and snapshots.
#[uniffi::export]
pub fn compression_review_plan(
    ids: Vec<String>,
    items: &[CleanupItem],
    state: &ReviewState,
) -> Result<Vec<CleanupItem>, CleanerError> {
    if ids.is_empty() || ids.len() > compression_batch_limit() as usize {
        return Err(blocked(
            "Choose between 1 and 10 photos for one review queue.",
        ));
    }
    let mut seen = HashSet::new();
    if !ids.iter().all(|id| seen.insert(id)) {
        return Err(blocked("The selection contains a repeated photo."));
    }
    let by_id: HashMap<_, _> = items.iter().map(|item| (&item.id, item)).collect();
    if by_id.len() != items.len() {
        return Err(blocked(
            "The media inventory contains repeated identifiers. Read the library again.",
        ));
    }
    ids.iter().map(|id| {
        let item = by_id.get(id).ok_or_else(|| blocked("A selected photo is no longer available. Read the library again."))?;
        if item.kind != MediaKind::Photo { return Err(blocked("Choose photos only. Videos need a separate preservation profile.")); }
        if state.processed_sources.contains(&compression_source_key(item)) || state.processed_sources.contains(&format!("output:{}", item.id)) {
            return Err(blocked("A selected source revision or saved copy was already processed. Remove it from this selection."));
        }
        Ok((*item).clone())
    }).collect()
}

/// Publish source/output guards and history together. Repeating a successful receipt is a no-op.
#[uniffi::export]
pub fn review_state_record_copy(
    state: &ReviewState,
    source: &CleanupItem,
    output_id: String,
    entry: HistoryEntry,
) -> Result<ReviewState, CleanerError> {
    if source.id.is_empty() || output_id.is_empty() || source.id == output_id {
        return Err(blocked("The saved copy could not be identified."));
    }
    let source_key = compression_source_key(source);
    let output_key = format!("output:{output_id}");
    if state.processed_sources.contains(&source_key)
        && state.processed_sources.contains(&output_key)
    {
        return Ok(state.clone());
    }
    let mut next = state.clone();
    next.history.insert(0, entry);
    next.processed_sources.extend([source_key, output_key]);
    next.processed_sources = canonical_set(next.processed_sources);
    Ok(next)
}
