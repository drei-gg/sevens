//! The library index (Swift `VaultItem`, `VaultFolder`, `VaultOrder`, `VaultLibrary`): its JSON
//! keys and defaults, validation in Swift's order, search and ordering.

use crate::error::VaultError;
use crate::text::{characters, is_blank, is_hex_character, standard_contains, standard_order};
use crate::time::{as_date, date, date_opt, upper, upper_opt, uuid_string};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::time::SystemTime;
use unicode_segmentation::UnicodeSegmentation;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultItem {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
    /// A uniform type identifier, such as `public.png`. JSON key `type`.
    #[serde(rename = "type")]
    pub content_type: String,
    pub bytes: i64,
    /// Lower-case hex SHA-256 of the original bytes.
    pub digest: String,
    #[serde(with = "date")]
    pub created: SystemTime,
    #[serde(with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub folder: Option<Uuid>,
    pub favorite: bool,
    /// When the item was moved to the trash.
    #[serde(with = "date_opt", default, skip_serializing_if = "Option::is_none")]
    pub deleted: Option<SystemTime>,
    pub order: i64,
}

impl VaultItem {
    pub fn new(name: String, content_type: String, bytes: i64, digest: String, folder: Option<Uuid>, order: i64) -> Self {
        Self { id: Uuid::new_v4(), name, content_type, bytes, digest, created: as_date(SystemTime::now()), folder, favorite: false, deleted: None, order }
    }

    pub fn matches(&self, query: &str) -> bool {
        query.is_empty() || standard_contains(&self.name, query)
    }
}

/// Swift's `VaultItem.init`: a new identity, created now, not a favorite, not trashed.
#[uniffi::export]
pub fn new_vault_item(name: String, content_type: String, bytes: i64, digest: String, folder: Option<Uuid>, order: i64) -> VaultItem {
    VaultItem::new(name, content_type, bytes, digest, folder, order)
}

/// Whether `item`'s name contains `query`, ignoring case and diacritics. An empty query matches.
#[uniffi::export]
pub fn vault_item_matches(item: &VaultItem, query: String) -> bool {
    item.matches(&query)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultFolder {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
    #[serde(with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub parent: Option<Uuid>,
}

#[uniffi::export]
pub fn new_vault_folder(name: String, parent: Option<Uuid>) -> VaultFolder {
    VaultFolder { id: Uuid::new_v4(), name, parent }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum VaultOrder {
    Newest,
    Oldest,
    Name,
    Largest,
    Manual,
}

impl VaultOrder {
    pub const ALL: [VaultOrder; 5] = [Self::Newest, Self::Oldest, Self::Name, Self::Largest, Self::Manual];

    /// Ties fall back to the `uuidString` so the order is total.
    pub fn sorted(self, items: &[VaultItem]) -> Vec<VaultItem> {
        let mut items = items.to_vec();
        items.sort_by(|a, b| {
            let tie = || uuid_string(&a.id).cmp(&uuid_string(&b.id));
            match self {
                Self::Newest => if a.created == b.created { tie() } else { b.created.cmp(&a.created) },
                Self::Oldest => if a.created == b.created { tie() } else { a.created.cmp(&b.created) },
                Self::Name => if a.name == b.name { tie() } else { standard_order(&a.name, &b.name) },
                Self::Largest => if a.bytes == b.bytes { tie() } else { b.bytes.cmp(&a.bytes) },
                Self::Manual => if a.order == b.order { tie() } else { a.order.cmp(&b.order) },
            }
        });
        items
    }
}

#[uniffi::export]
pub fn vault_order_sorted(order: VaultOrder, items: Vec<VaultItem>) -> Vec<VaultItem> {
    order.sorted(&items)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultLibrary {
    pub version: i64,
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
    pub items: Vec<VaultItem>,
    pub folders: Vec<VaultFolder>,
    pub sort: VaultOrder,
    pub grid: bool,
    #[serde(rename = "backupPrepared", with = "date_opt", default, skip_serializing_if = "Option::is_none")]
    pub backup_prepared: Option<SystemTime>,
    /// The current recovery key was issued (vault creation, a replacement, a version 1 → 2
    /// migration) but the person has not yet confirmed saving it. Kept inside the authenticated
    /// index so no secret has to outlive the session: the next unlock asks for a replacement key.
    /// Absent (false) in older indexes and omitted when false, so existing files read back equal.
    #[serde(rename = "recoveryUnconfirmed", default, skip_serializing_if = "is_false")]
    pub recovery_unconfirmed: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
}

impl VaultLibrary {
    pub fn new(name: String) -> Self {
        Self::with_id(Uuid::new_v4(), name)
    }

    pub fn with_id(id: Uuid, name: String) -> Self {
        Self { version: 1, id, name, items: vec![], folders: vec![], sort: VaultOrder::Newest, grid: false, backup_prepared: None, recovery_unconfirmed: false }
    }

    pub fn validate_name(name: &str) -> Result<(), VaultError> {
        if is_blank(name) || characters(name) > 200 || name == "." || name == ".." || name.contains('/') || name.contains('\\') || name.contains('\0') {
            return Err(VaultError::InvalidName);
        }
        Ok(())
    }

    pub fn validate(&self) -> Result<(), VaultError> {
        if self.version != 1 {
            return Err(VaultError::Unsupported);
        }
        Self::validate_name(&self.name)?;
        let folder_ids: HashSet<Uuid> = self.folders.iter().map(|f| f.id).collect();
        if folder_ids.len() != self.folders.len() || self.items.iter().map(|i| i.id).collect::<HashSet<_>>().len() != self.items.len() {
            return Err(VaultError::Damaged);
        }
        for folder in &self.folders {
            Self::validate_name(&folder.name)?;
            let mut visited = HashSet::from([folder.id]);
            let mut next = folder.parent;
            while let Some(current) = next {
                if !visited.insert(current) {
                    return Err(VaultError::Damaged);
                }
                let Some(found) = self.folders.iter().find(|f| f.id == current) else { return Err(VaultError::Damaged) };
                next = found.parent;
            }
        }
        for item in &self.items {
            Self::validate_name(&item.name)?;
            let digest_ok = characters(&item.digest) == 64 && item.digest.graphemes(true).all(is_hex_character);
            if item.bytes < 0 || !digest_ok || item.folder.is_some_and(|f| !folder_ids.contains(&f)) {
                return Err(VaultError::Damaged);
            }
        }
        Ok(())
    }
}

/// Swift's `VaultLibrary(name:)`: a new identity, version 1, newest first, list view.
#[uniffi::export]
pub fn new_vault_library(name: String) -> VaultLibrary {
    VaultLibrary::new(name)
}

/// A vault, folder or item name: not blank, at most 200 characters, not `.` or `..`, and no `/`,
/// `\` or NUL.
#[uniffi::export]
pub fn library_validate_name(name: String) -> Result<(), VaultError> {
    VaultLibrary::validate_name(&name)
}

#[uniffi::export]
pub fn library_validate(library: &VaultLibrary) -> Result<(), VaultError> {
    library.validate()
}
