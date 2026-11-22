//! Library.swift: items, review decisions, exact-match groups, keeper and default-selection
//! rules, and storage totals that never count an unknown size as zero.

use crate::codec::{date_opt, distant_future, set};
use crate::text::{canonical_set, swift_cmp};
use serde::{Deserialize, Serialize};
use std::cmp::Ordering;
use std::collections::{BTreeMap, HashMap};
use std::time::SystemTime;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum MediaKind {
    Photo,
    Video,
    File,
}

impl MediaKind {
    pub(crate) fn raw(self) -> &'static str {
        match self {
            MediaKind::Photo => "photo",
            MediaKind::Video => "video",
            MediaKind::File => "file",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum ReviewDecision {
    Undecided,
    Keep,
    Later,
    Remove,
}

/// `ReviewDecision.allCases`, in declaration order.
#[uniffi::export]
pub fn review_decision_all_cases() -> Vec<ReviewDecision> {
    vec![
        ReviewDecision::Undecided,
        ReviewDecision::Keep,
        ReviewDecision::Later,
        ReviewDecision::Remove,
    ]
}

/// A photo, video or file. `bytes` is `None` when the size is unknown, never zero.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct CleanupItem {
    pub id: String,
    pub name: String,
    pub kind: MediaKind,
    pub revision: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bytes: Option<i64>,
    #[serde(with = "date_opt", default, skip_serializing_if = "Option::is_none")]
    pub date: Option<SystemTime>,
    pub width: i32,
    pub height: i32,
    pub duration: f64,
    pub favorite: bool,
    /// A Swift `Set<String>`.
    #[serde(with = "set")]
    pub tags: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub fingerprint: Option<String>,
}

/// `CleanupItem(id:name:kind:)` with Swift's defaults for everything else.
#[uniffi::export]
pub fn new_cleanup_item(id: String, name: String, kind: MediaKind) -> CleanupItem {
    CleanupItem {
        id,
        name,
        kind,
        revision: String::new(),
        bytes: None,
        date: None,
        width: 0,
        height: 0,
        duration: 0.0,
        favorite: false,
        tags: vec![],
        fingerprint: None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum MatchKind {
    IdenticalResources,
    Similar,
}

/// `MatchGroup` (its nested `Kind` is [`MatchKind`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MatchGroup {
    pub id: String,
    pub kind: MatchKind,
    pub members: Vec<String>,
    pub reason: String,
}

pub(crate) const IDENTICAL_REASON: &str =
    "All readable resource bytes match. Dates, favorites and album membership can still differ.";

/// `Matching.exact`: items whose readable resource bytes match, per kind, sorted by group id.
#[uniffi::export]
pub fn matching_exact(items: &[CleanupItem]) -> Vec<MatchGroup> {
    let mut groups: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for item in items {
        if let Some(fingerprint) = &item.fingerprint {
            groups
                .entry(format!("{}:{}", item.kind.raw(), fingerprint))
                .or_default()
                .push(item.id.clone());
        }
    }
    let mut result: Vec<MatchGroup> = groups
        .into_iter()
        .filter(|(_, members)| members.len() > 1)
        .map(|(id, mut members)| {
            members.sort_by(|a, b| swift_cmp(a, b));
            MatchGroup {
                id,
                kind: MatchKind::IdenticalResources,
                members,
                reason: IDENTICAL_REASON.to_string(),
            }
        })
        .collect();
    result.sort_by(|a, b| swift_cmp(&a.id, &b.id));
    result
}

fn keeper_order(
    a: &CleanupItem,
    b: &CleanupItem,
    decisions: &HashMap<String, ReviewDecision>,
) -> Ordering {
    let protected = |item: &CleanupItem| {
        decisions.get(&item.id) == Some(&ReviewDecision::Keep) || item.favorite
    };
    let (pa, pb) = (protected(a), protected(b));
    if pa != pb {
        return if pa {
            Ordering::Less
        } else {
            Ordering::Greater
        };
    }
    let area = |item: &CleanupItem| item.width as i64 * item.height as i64;
    if area(a) != area(b) {
        return area(b).cmp(&area(a));
    }
    if a.date != b.date {
        let far = distant_future();
        return a.date.unwrap_or(far).cmp(&b.date.unwrap_or(far));
    }
    swift_cmp(&a.id, &b.id)
}

/// `Matching.keeper`: among items not marked for removal, prefer kept or favorite, then the
/// most pixels, then the earliest date (unknown last), then the id.
#[uniffi::export]
pub fn matching_keeper(
    items: &[CleanupItem],
    decisions: &HashMap<String, ReviewDecision>,
) -> Option<String> {
    let mut candidates: Vec<&CleanupItem> = items
        .iter()
        .filter(|item| decisions.get(&item.id) != Some(&ReviewDecision::Remove))
        .collect();
    candidates.sort_by(|a, b| keeper_order(a, b, decisions));
    candidates.first().map(|item| item.id.clone())
}

/// `Matching.proposedRemoval`: only byte-equal groups get default selections, never a favorite,
/// never the keeper and never an item with a deliberate decision. Sorted, as a Swift `Set`.
#[uniffi::export]
pub fn matching_proposed_removal(
    groups: &[MatchGroup],
    items: &[CleanupItem],
    decisions: &HashMap<String, ReviewDecision>,
) -> Vec<String> {
    // Swift builds this with `uniqueKeysWithValues`, which traps on a repeated id; the last wins here.
    let by_id: HashMap<&str, &CleanupItem> =
        items.iter().map(|item| (item.id.as_str(), item)).collect();
    let mut result = Vec::new();
    for group in groups
        .iter()
        .filter(|g| g.kind == MatchKind::IdenticalResources)
    {
        let members: Vec<CleanupItem> = group
            .members
            .iter()
            .filter_map(|id| by_id.get(id.as_str()).map(|i| (*i).clone()))
            .collect();
        if members.len() <= 1 {
            continue;
        }
        let Some(keeper) = matching_keeper(&members, decisions) else {
            continue;
        };
        for item in &members {
            let undecided = decisions
                .get(&item.id)
                .copied()
                .unwrap_or(ReviewDecision::Undecided)
                == ReviewDecision::Undecided;
            if item.id != keeper && !item.favorite && undecided {
                result.push(item.id.clone());
            }
        }
    }
    canonical_set(result)
}

/// `StorageSummary`: known bytes and how many sizes are unknown.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct StorageSummary {
    pub known_bytes: i64,
    pub unknown_count: i32,
}

#[uniffi::export]
pub fn storage_summary(items: &[CleanupItem]) -> StorageSummary {
    StorageSummary {
        known_bytes: items
            .iter()
            .filter_map(|i| i.bytes)
            .fold(0i64, |a, b| a.saturating_add(b)),
        unknown_count: items.iter().filter(|i| i.bytes.is_none()).count() as i32,
    }
}

/// The keeper [`matching_keeper`] suggests for a group and the rule that chose it, in words.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct KeeperSuggestion {
    pub id: String,
    pub reason: String,
}

/// `matching_keeper` with its reason: the first rule in keeper order that set this item apart.
/// A suggestion only; similar groups are never preselected and nothing is removed by it.
#[uniffi::export]
pub fn keeper_suggestion(
    items: &[CleanupItem],
    decisions: &HashMap<String, ReviewDecision>,
) -> Option<KeeperSuggestion> {
    let id = matching_keeper(items, decisions)?;
    let keeper = items.iter().find(|item| item.id == id)?;
    let others: Vec<&CleanupItem> = items
        .iter()
        .filter(|item| item.id != id && decisions.get(&item.id) != Some(&ReviewDecision::Remove))
        .collect();
    let kept = |item: &CleanupItem| decisions.get(&item.id) == Some(&ReviewDecision::Keep);
    let area = |item: &CleanupItem| item.width as i64 * item.height as i64;
    let reason = if kept(keeper) {
        "Suggested keeper: you chose to keep it."
    } else if keeper.favorite {
        "Suggested keeper: it’s a favorite."
    } else if others.is_empty() {
        "Suggested keeper: the others are queued for removal."
    } else if others.iter().all(|other| area(other) < area(keeper)) {
        "Suggested keeper: the most pixels."
    } else if keeper.date.is_some()
        && others
            .iter()
            .filter(|other| area(other) == area(keeper))
            .all(|other| other.date.is_none_or(|date| Some(date) > keeper.date))
    {
        "Suggested keeper: the earliest capture date."
    } else {
        "Suggested keeper: the same size and date as another, chosen by name."
    };
    Some(KeeperSuggestion {
        id,
        reason: reason.to_string(),
    })
}

/// The Large collection's default minimum: 10 MB for a photo, 100 MB for a video (decimal MB).
#[uniffi::export]
pub fn large_minimum_default(kind: MediaKind) -> i64 {
    match kind {
        MediaKind::Video => 100_000_000,
        _ => 10_000_000,
    }
}

/// The minimums the filter editor offers for Large, smallest first; the default is among them.
#[uniffi::export]
pub fn large_minimum_choices(kind: MediaKind) -> Vec<i64> {
    match kind {
        MediaKind::Video => vec![
            50_000_000,
            100_000_000,
            250_000_000,
            500_000_000,
            1_000_000_000,
        ],
        _ => vec![2_000_000, 5_000_000, 10_000_000, 20_000_000, 50_000_000],
    }
}

/// At least `minimum_bytes` by a known size. Unknown is never large; a minimum below one byte
/// counts as one, so a known empty file is not large either.
#[uniffi::export]
pub fn media_is_large_from(item: &CleanupItem, minimum_bytes: i64) -> bool {
    item.bytes
        .is_some_and(|bytes| bytes >= minimum_bytes.max(1))
}

/// A photo of at least 10 MB or a video of at least 100 MB, by a known size. Unknown is never large.
#[uniffi::export]
pub fn media_is_large(item: &CleanupItem) -> bool {
    media_is_large_from(item, large_minimum_default(item.kind))
}

/// The Large collection: items of at least `minimum_bytes`, largest first. Equal sizes keep the
/// order given; unknown sizes are never included.
#[uniffi::export]
pub fn large_items(items: Vec<CleanupItem>, minimum_bytes: i64) -> Vec<CleanupItem> {
    let mut large: Vec<CleanupItem> = items
        .into_iter()
        .filter(|item| media_is_large_from(item, minimum_bytes))
        .collect();
    large.sort_by(|a, b| b.bytes.cmp(&a.bytes));
    large
}

/// Dimensions by the shorter side, so portrait and landscape compare alike. Zero is unknown.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum MediaResolutionFilter {
    Any,
    /// Shorter side at least 2160 px (4K).
    AtLeast2160,
    AtLeast1080,
    AtLeast720,
    /// Known, with a shorter side under 720 px.
    Below720,
    Unknown,
}

#[uniffi::export]
pub fn media_resolution_matches(width: i32, height: i32, filter: MediaResolutionFilter) -> bool {
    let known = width > 0 && height > 0;
    let short = width.min(height);
    match filter {
        MediaResolutionFilter::Any => true,
        MediaResolutionFilter::Unknown => !known,
        MediaResolutionFilter::AtLeast2160 => known && short >= 2160,
        MediaResolutionFilter::AtLeast1080 => known && short >= 1080,
        MediaResolutionFilter::AtLeast720 => known && short >= 720,
        MediaResolutionFilter::Below720 => known && short < 720,
    }
}

/// A video's length. Zero, negative or non-finite durations are unknown, never short.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum MediaDurationFilter {
    Any,
    Under1Minute,
    AtLeast1Minute,
    AtLeast5Minutes,
    AtLeast30Minutes,
    Unknown,
}

#[uniffi::export]
pub fn media_duration_matches(seconds: f64, filter: MediaDurationFilter) -> bool {
    let known = seconds.is_finite() && seconds > 0.0;
    match filter {
        MediaDurationFilter::Any => true,
        MediaDurationFilter::Unknown => !known,
        MediaDurationFilter::Under1Minute => known && seconds < 60.0,
        MediaDurationFilter::AtLeast1Minute => known && seconds >= 60.0,
        MediaDurationFilter::AtLeast5Minutes => known && seconds >= 300.0,
        MediaDurationFilter::AtLeast30Minutes => known && seconds >= 1800.0,
    }
}

/// The media filters a library applies besides date, search and collection. Duration applies to
/// videos only; a photo matches any duration filter.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct MediaFilters {
    pub size: crate::FileSizeFilter,
    pub resolution: MediaResolutionFilter,
    pub duration: MediaDurationFilter,
}

#[uniffi::export]
pub fn media_filters_any() -> MediaFilters {
    MediaFilters {
        size: crate::FileSizeFilter::Any,
        resolution: MediaResolutionFilter::Any,
        duration: MediaDurationFilter::Any,
    }
}

#[uniffi::export]
pub fn media_filters_match(item: &CleanupItem, filters: MediaFilters) -> bool {
    crate::file_size_matches(item.bytes, filters.size)
        && media_resolution_matches(item.width, item.height, filters.resolution)
        && (item.kind != MediaKind::Video
            || media_duration_matches(item.duration, filters.duration))
}
