//! The library edits `VaultRepository` makes, without its storage: each takes the current library
//! and returns the changed one, with the repository's checks in its order. The platform then seals
//! the result with [`crate::library_encode`] (which validates it again) and commits it.

use crate::error::VaultError;
use crate::format::{NOTE_MAX_BYTES, NOTE_TYPE};
use crate::time::as_date;
use crate::library::{VaultFolder, VaultItem, VaultLibrary, VaultOrder};
use std::collections::HashSet;
use std::time::SystemTime;
use uuid::Uuid;

/// Before reading an import's source: its name, then that the target folder exists.
#[uniffi::export]
pub fn library_check_import(library: &VaultLibrary, name: String, folder: Option<Uuid>) -> Result<(), VaultError> {
    VaultLibrary::validate_name(&name)?;
    if let Some(parent) = folder
        && !library.folders.iter().any(|f| f.id == parent)
    {
        return Err(VaultError::Damaged);
    }
    Ok(())
}

/// The new item for an import whose original measured `bytes` with SHA-256 `digest`, ordered
/// after every existing item. Its id names the encrypted blob and salts its key.
#[uniffi::export]
pub fn library_import_item(library: &VaultLibrary, name: String, content_type: String, bytes: i64, digest: String, folder: Option<Uuid>) -> VaultItem {
    let order = library.items.iter().map(|i| i.order).max().unwrap_or(-1) + 1;
    VaultItem::new(name, content_type, bytes, digest, folder, order)
}

/// The library with a verified import appended.
#[uniffi::export]
pub fn library_append_item(library: &VaultLibrary, item: VaultItem) -> VaultLibrary {
    let mut changed = library.clone();
    changed.items.push(item);
    changed
}

/// A new note's checks (text size, then name) and its stored name, with `.txt` added if missing.
/// Notes import as `public.plain-text`.
#[uniffi::export]
pub fn note_name(name: String, text: String) -> Result<String, VaultError> {
    if text.len() as u64 > NOTE_MAX_BYTES {
        return Err(VaultError::NoSpace);
    }
    VaultLibrary::validate_name(&name)?;
    Ok(if name.ends_with(".txt") { name } else { format!("{name}.txt") })
}

/// The note an edit replaces: it must exist, be plain text, and the new text fit.
#[uniffi::export]
pub fn library_check_note_edit(library: &VaultLibrary, id: Uuid, text: String) -> Result<VaultItem, VaultError> {
    match library.items.iter().find(|i| i.id == id) {
        Some(old) if old.content_type == NOTE_TYPE && text.len() as u64 <= NOTE_MAX_BYTES => Ok(old.clone()),
        _ => Err(VaultError::Unsupported),
    }
}

/// A note that can be read back: it exists, is plain text and at most 5 MB.
#[uniffi::export]
pub fn library_check_note_read(library: &VaultLibrary, id: Uuid) -> Result<VaultItem, VaultError> {
    match library.items.iter().find(|i| i.id == id) {
        Some(item) if item.content_type == NOTE_TYPE && item.bytes as u64 <= NOTE_MAX_BYTES => Ok(item.clone()),
        _ => Err(VaultError::Unsupported),
    }
}

/// An edited note's replacement: a new identity (and blob) keeping the old one's name, type,
/// folder, order, creation date, favorite and trash state.
#[uniffi::export]
pub fn note_replacement(old: &VaultItem, bytes: i64, digest: String) -> VaultItem {
    let mut replacement = VaultItem::new(old.name.clone(), old.content_type.clone(), bytes, digest, old.folder, old.order);
    replacement.created = old.created;
    replacement.favorite = old.favorite;
    replacement.deleted = old.deleted;
    replacement
}

/// The library with item `id` replaced in place. The old blob is removed after the commit.
#[uniffi::export]
pub fn library_replace_item(library: &VaultLibrary, id: Uuid, replacement: VaultItem) -> VaultLibrary {
    let mut changed = library.clone();
    for item in &mut changed.items {
        if item.id == id {
            *item = replacement.clone();
        }
    }
    changed
}

/// Rename, favorite or trash an item. Trashing stamps `now` (at `Date` precision); restoring
/// clears it.
#[uniffi::export]
pub fn library_update_item(library: &VaultLibrary, id: Uuid, name: Option<String>, favorite: Option<bool>, trashed: Option<bool>, now: SystemTime) -> Result<VaultLibrary, VaultError> {
    let mut changed = library.clone();
    let Some(index) = changed.items.iter().position(|i| i.id == id) else { return Err(VaultError::MissingFile) };
    if let Some(name) = name {
        VaultLibrary::validate_name(&name)?;
        changed.items[index].name = name;
    }
    if let Some(favorite) = favorite {
        changed.items[index].favorite = favorite;
    }
    if let Some(trashed) = trashed {
        changed.items[index].deleted = trashed.then_some(as_date(now));
    }
    Ok(changed)
}

#[uniffi::export]
pub fn library_move(library: &VaultLibrary, ids: Vec<Uuid>, parent: Option<Uuid>) -> Result<VaultLibrary, VaultError> {
    let mut changed = library.clone();
    if let Some(parent) = parent
        && !changed.folders.iter().any(|f| f.id == parent)
    {
        return Err(VaultError::Damaged);
    }
    let ids: HashSet<Uuid> = ids.into_iter().collect();
    for item in &mut changed.items {
        if ids.contains(&item.id) {
            item.folder = parent;
        }
    }
    Ok(changed)
}

/// A new folder. A missing parent is caught when the library is encoded.
#[uniffi::export]
pub fn library_add_folder(library: &VaultLibrary, name: String, parent: Option<Uuid>) -> Result<VaultLibrary, VaultError> {
    let mut changed = library.clone();
    VaultLibrary::validate_name(&name)?;
    changed.folders.push(VaultFolder { id: Uuid::new_v4(), name, parent });
    Ok(changed)
}

#[uniffi::export]
pub fn library_rename_folder(library: &VaultLibrary, id: Uuid, name: String) -> Result<VaultLibrary, VaultError> {
    let mut changed = library.clone();
    VaultLibrary::validate_name(&name)?;
    let Some(folder) = changed.folders.iter_mut().find(|f| f.id == id) else { return Err(VaultError::MissingFile) };
    folder.name = name;
    Ok(changed)
}

#[uniffi::export]
pub fn library_set_preferences(library: &VaultLibrary, sort: VaultOrder, grid: bool) -> VaultLibrary {
    let mut changed = library.clone();
    changed.sort = sort;
    changed.grid = grid;
    changed
}

/// Manual order: `ids` (unique, all present) get positions 0, 1, 2…; the sort becomes manual.
#[uniffi::export]
pub fn library_reorder(library: &VaultLibrary, ids: Vec<Uuid>) -> Result<VaultLibrary, VaultError> {
    let mut changed = library.clone();
    let unique: HashSet<Uuid> = ids.iter().copied().collect();
    let present: HashSet<Uuid> = library.items.iter().map(|i| i.id).collect();
    if unique.len() != ids.len() || !unique.is_subset(&present) {
        return Err(VaultError::Damaged);
    }
    for (order, id) in ids.iter().enumerate() {
        if let Some(item) = changed.items.iter_mut().find(|i| i.id == *id) {
            item.order = order as i64;
        }
    }
    changed.sort = VaultOrder::Manual;
    Ok(changed)
}

/// Remove items for good; every selected item must already be in the trash. Their blobs
/// ([`crate::blob_name`]) are deleted after the commit.
#[uniffi::export]
pub fn library_permanently_delete(library: &VaultLibrary, ids: Vec<Uuid>) -> Result<VaultLibrary, VaultError> {
    let ids: HashSet<Uuid> = ids.into_iter().collect();
    if !library.items.iter().filter(|i| ids.contains(&i.id)).all(|i| i.deleted.is_some()) {
        return Err(VaultError::Damaged);
    }
    let mut changed = library.clone();
    changed.items.retain(|i| !ids.contains(&i.id));
    Ok(changed)
}

/// After a manual backup is written: record when it was prepared.
#[uniffi::export]
pub fn library_mark_backup_prepared(library: &VaultLibrary, now: SystemTime) -> VaultLibrary {
    let mut changed = library.clone();
    changed.backup_prepared = Some(as_date(now));
    changed
}
