//! ReviewStore.swift: durable user decisions, their history, the review file format and the
//! removal guard. The atomic file write stays native; the state changes and the codec are here.
//! Scans never write over decisions, and a corrupt file fails closed instead of resetting.

use crate::codec::{date, set, sorted_map, upper};
use crate::library::{CleanupItem, ReviewDecision};
use crate::text::canonical_set;
use crate::{CleanerError, blocked};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::time::SystemTime;
use uuid::Uuid;

/// Swift's `ReviewState`. Every key is required when reading, as Swift's synthesized decoder
/// requires them; the defaults apply only to a new state.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct ReviewState {
    pub version: i32,
    #[serde(serialize_with = "sorted_map::serialize")]
    pub decisions: HashMap<String, ReviewDecision>,
    #[serde(serialize_with = "sorted_map::serialize")]
    pub revisions: HashMap<String, String>,
    /// Newest first.
    pub history: Vec<HistoryEntry>,
    /// A Swift `Set<String>`.
    #[serde(with = "set")]
    pub processed_sources: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct HistoryEntry {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "date")]
    pub date: SystemTime,
    pub message: String,
}

/// `ReviewState()`: version 1, nothing decided.
#[uniffi::export]
pub fn new_review_state() -> ReviewState {
    ReviewState {
        version: 1,
        decisions: HashMap::new(),
        revisions: HashMap::new(),
        history: vec![],
        processed_sources: vec![],
    }
}

/// `HistoryEntry(message)`: a fresh id, dated now.
#[uniffi::export]
pub fn new_history_entry(message: String) -> HistoryEntry {
    HistoryEntry {
        id: Uuid::new_v4(),
        date: SystemTime::now(),
        message,
    }
}

/// `ReviewStore.read` after the file exists: unreadable JSON is an error, never an empty state,
/// and a newer version is refused. A missing file is the platform's case: `new_review_state()`.
#[uniffi::export]
pub fn review_state_decode(data: &[u8]) -> Result<ReviewState, CleanerError> {
    let state: ReviewState = serde_json::from_slice(data).map_err(|_| CleanerError::Unreadable)?;
    if state.version != 1 {
        return Err(blocked("This review database needs a newer app."));
    }
    Ok(state)
}

/// The bytes `ReviewStore.write` stores (sorted keys; Swift's own writes are in hash order).
#[uniffi::export]
pub fn review_state_encode(state: &ReviewState) -> Result<Vec<u8>, CleanerError> {
    serde_json::to_vec(state).map_err(|_| CleanerError::Unreadable)
}

/// `ReviewStore.decide`: each decision replaces the previous one, and its revision is set to the
/// given one, or cleared when none is given (Swift assigns `revisions[id]`, which may be nil).
#[uniffi::export]
pub fn review_state_decide(
    state: &ReviewState,
    decisions: HashMap<String, ReviewDecision>,
    revisions: HashMap<String, String>,
) -> ReviewState {
    let mut state = state.clone();
    for (id, value) in decisions {
        match revisions.get(&id) {
            Some(revision) => state.revisions.insert(id.clone(), revision.clone()),
            None => state.revisions.remove(&id),
        };
        state.decisions.insert(id, value);
    }
    state
}

/// `ReviewStore.record`: the entry goes first; an optional processed source is remembered so a
/// compressed copy is never compressed again.
#[uniffi::export]
pub fn review_state_record(
    state: &ReviewState,
    entry: HistoryEntry,
    processed_source: Option<String>,
) -> ReviewState {
    let mut state = state.clone();
    state.history.insert(0, entry);
    if let Some(source) = processed_source {
        state.processed_sources.push(source);
        state.processed_sources = canonical_set(std::mem::take(&mut state.processed_sources));
    }
    state
}

const STALE: &str = "Selection changed, is protected, or needs review again.";

/// `MutationGuard.validateRemoval`: every selected item must still exist, be marked for removal
/// at its current revision, and not be a favorite.
#[uniffi::export]
pub fn validate_removal(
    ids: Vec<String>,
    items: &[CleanupItem],
    state: &ReviewState,
) -> Result<(), CleanerError> {
    if ids.is_empty() {
        return Err(blocked("Nothing selected."));
    }
    // Swift traps on a repeated item id (`uniqueKeysWithValues`); here it blocks the removal.
    let mut seen = HashSet::new();
    if !items.iter().all(|item| seen.insert(item.id.as_str())) {
        return Err(blocked(STALE));
    }
    let by_id: HashMap<&str, &CleanupItem> =
        items.iter().map(|item| (item.id.as_str(), item)).collect();
    for id in &ids {
        let Some(item) = by_id.get(id.as_str()) else {
            return Err(blocked(STALE));
        };
        let marked = state.decisions.get(id) == Some(&ReviewDecision::Remove);
        let current = state.revisions.get(id) == Some(&item.revision);
        if !marked || !current || item.favorite {
            return Err(blocked(STALE));
        }
    }
    Ok(())
}

/// Why one item queued for removal can or cannot be removed now.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum RemovalStatus {
    /// Marked for removal at its current revision and not a favorite.
    Ready,
    /// No longer in the accessible library, or listed twice by the provider.
    Missing,
    /// No removal decision is recorded for it.
    NotMarked,
    /// The item changed after it was queued; the decision is for an older revision.
    Changed,
    /// A favorite. Favorites are never removed in a batch.
    Favorite,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct RemovalOutcome {
    pub id: String,
    pub status: RemovalStatus,
}

/// Each selected item's outcome, in the order given (duplicates once). The same rules as
/// [`validate_removal`], item by item, so one changed item no longer hides which one it is.
/// Only `Ready` items may be removed; the others stay until the person reviews them again.
#[uniffi::export]
pub fn removal_outcomes(
    ids: Vec<String>,
    items: &[CleanupItem],
    state: &ReviewState,
) -> Vec<RemovalOutcome> {
    let mut counts: HashMap<&str, usize> = HashMap::new();
    for item in items {
        *counts.entry(item.id.as_str()).or_default() += 1;
    }
    let by_id: HashMap<&str, &CleanupItem> =
        items.iter().map(|item| (item.id.as_str(), item)).collect();
    let mut seen = HashSet::new();
    ids.into_iter()
        .filter(|id| seen.insert(id.clone()))
        .map(|id| {
            let status = match by_id.get(id.as_str()) {
                None => RemovalStatus::Missing,
                Some(_) if counts.get(id.as_str()).copied().unwrap_or(0) > 1 => {
                    RemovalStatus::Missing
                }
                Some(item) if item.favorite => RemovalStatus::Favorite,
                Some(item) => {
                    if state.decisions.get(&id) != Some(&ReviewDecision::Remove) {
                        RemovalStatus::NotMarked
                    } else if state.revisions.get(&id) != Some(&item.revision) {
                        RemovalStatus::Changed
                    } else {
                        RemovalStatus::Ready
                    }
                }
            };
            RemovalOutcome { id, status }
        })
        .collect()
}

/// The ids among `outcomes` that may be removed.
#[uniffi::export]
pub fn removal_ready_ids(outcomes: &[RemovalOutcome]) -> Vec<String> {
    outcomes
        .iter()
        .filter(|o| o.status == RemovalStatus::Ready)
        .map(|o| o.id.clone())
        .collect()
}

/// What to tell the person when some queued items were held back. `None` when every item is ready.
#[uniffi::export]
pub fn removal_held_message(outcomes: &[RemovalOutcome]) -> Option<String> {
    let held = outcomes
        .iter()
        .filter(|o| o.status != RemovalStatus::Ready)
        .count();
    if held == 0 {
        return None;
    }
    let ready = outcomes.len() - held;
    let subject = if held == 1 {
        "1 item changed, became a favorite or is no longer available".to_string()
    } else {
        format!("{held} items changed, became favorites or are no longer available")
    };
    Some(if ready == 0 {
        format!("{subject}. Nothing was deleted. Review them again in Pending removal.")
    } else {
        format!(
            "{subject}, so {} kept. Review them again in Pending removal.",
            if held == 1 { "it was" } else { "they were" }
        )
    })
}
