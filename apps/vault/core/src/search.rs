//! Bounded on-demand note search. Native repositories decrypt one verified note at a time;
//! only IDs and coverage counts leave the search, never a plaintext index or snippets.
use crate::{
    VaultError, VaultIssue, VaultItem,
    text::{characters, is_blank, standard_contains},
};
use uuid::Uuid;

/// The longest note-text search, in characters.
pub const MAXIMUM_QUERY_CHARACTERS: u32 = 200;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NoteSearchPlan {
    pub ids: Vec<Uuid>,
    pub omitted: u32,
    pub issue: Option<VaultIssue>,
}
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct NoteSearchResult {
    pub ids: Vec<Uuid>,
    pub searched: u32,
    pub unavailable: u32,
    pub omitted: u32,
    pub issue: Option<VaultIssue>,
}
#[uniffi::export]
pub fn note_search_plan(
    items: Vec<VaultItem>,
    query: String,
    trash: bool,
    favorites: bool,
) -> NoteSearchPlan {
    let mut result = NoteSearchPlan {
        ids: vec![],
        omitted: 0,
        issue: None,
    };
    if characters(&query) > MAXIMUM_QUERY_CHARACTERS as usize {
        result.issue = Some(VaultIssue::QueryTooLong { maximum: MAXIMUM_QUERY_CHARACTERS });
        return result;
    }
    if is_blank(&query) {
        return result;
    }
    let mut bytes = 0_u64;
    for item in items.iter().filter(|i| {
        i.content_type == "public.plain-text"
            && i.deleted.is_some() == trash
            && (!favorites || i.favorite)
    }) {
        if item.bytes < 0
            || item.bytes as u64 > crate::vault_format().note_max_bytes
            || result.ids.len() >= 500
            || bytes.saturating_add(item.bytes as u64) > 32 * 1024 * 1024
        {
            result.omitted += 1;
        } else {
            result.ids.push(item.id);
            bytes += item.bytes as u64;
        }
    }
    result
}
#[uniffi::export]
pub fn note_search_matches(data: Vec<u8>, query: String) -> Result<bool, VaultError> {
    let data = zeroize::Zeroizing::new(data);
    if data.len() as u64 > crate::vault_format().note_max_bytes || characters(&query) > MAXIMUM_QUERY_CHARACTERS as usize {
        return Err(VaultError::Unsupported);
    }
    if is_blank(&query) {
        return Ok(false);
    }
    let text = std::str::from_utf8(&data).map_err(|_| VaultError::Damaged)?;
    Ok(standard_contains(text, &query))
}
