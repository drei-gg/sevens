//! The `.vault7` backup framing (VaultStorage's `BackupContainer`): `7DVAULT1`, a big-endian
//! UInt64 entry count, then per entry a UInt64 name length, the UTF-8 name, a UInt64 payload length
//! and the payload. Transport framing only: the index and each payload are authenticated
//! ciphertext on their own. Checks run in the Swift reader's order.
//!
//! The codec here works on bytes in memory. The app streams large files; it can use
//! [`backup_container_prefix`] and [`backup_entry_prefix`] to write, and must apply the same
//! checks as [`backup_container_decode`] when it reads.

use crate::error::VaultError;
use crate::format::{HEADER_MAX_BYTES, SEALED_INDEX_MAX_BYTES, blob_name};
use crate::time::{parse_uuid, uuid_string};
use crate::library::VaultLibrary;
use std::collections::HashSet;

pub const BACKUP_MAGIC: &[u8; 8] = b"7DVAULT1";
const MAX_ENTRIES: u64 = 1_000_000;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupEntry {
    pub name: String,
    pub data: Vec<u8>,
}

/// Only `header.json`, `index.sealed` and canonical upper-case `<UUID>.aea` names are accepted.
#[uniffi::export]
pub fn backup_name_valid(name: String) -> bool {
    if name == "header.json" || name == "index.sealed" {
        return true;
    }
    let Some(stem) = name.strip_suffix(".aea") else { return false };
    let Some(id) = parse_uuid(stem) else { return false };
    name == format!("{}.aea", uuid_string(&id))
}

/// The names a vault's backup holds, in the order the app writes them.
#[uniffi::export]
pub fn backup_entry_names(library: &VaultLibrary) -> Vec<String> {
    let mut names = vec!["header.json".to_string(), "index.sealed".to_string()];
    names.extend(library.items.iter().map(|i| blob_name(i.id)));
    names
}

/// Restore's last framing check, after the index is opened: the container held exactly the
/// header, the index and every item's original.
#[uniffi::export]
pub fn backup_names_match(names: Vec<String>, library: &VaultLibrary) -> Result<(), VaultError> {
    let got: HashSet<String> = names.into_iter().collect();
    let expected: HashSet<String> = backup_entry_names(library).into_iter().collect();
    if got != expected {
        return Err(VaultError::Damaged);
    }
    Ok(())
}

/// The file name a manual backup gets.
#[uniffi::export]
pub fn backup_file_name(id: uuid::Uuid) -> String {
    format!("Vault-{}.vault7", uuid_string(&id))
}

/// Magic and entry count. Too many or duplicate names are damaged, as in the Swift writer.
#[uniffi::export]
pub fn backup_container_prefix(names: Vec<String>) -> Result<Vec<u8>, VaultError> {
    let unique: HashSet<&String> = names.iter().collect();
    if names.len() as u64 > MAX_ENTRIES || unique.len() != names.len() {
        return Err(VaultError::Damaged);
    }
    let mut out = BACKUP_MAGIC.to_vec();
    out.extend_from_slice(&(names.len() as u64).to_be_bytes());
    Ok(out)
}

/// One entry's framing before its `size` payload bytes.
#[uniffi::export]
pub fn backup_entry_prefix(name: String, size: u64) -> Result<Vec<u8>, VaultError> {
    if !backup_name_valid(name.clone()) {
        return Err(VaultError::Damaged);
    }
    let mut out = (name.len() as u64).to_be_bytes().to_vec();
    out.extend_from_slice(name.as_bytes());
    out.extend_from_slice(&size.to_be_bytes());
    Ok(out)
}

#[uniffi::export]
pub fn backup_container_encode(entries: Vec<BackupEntry>) -> Result<Vec<u8>, VaultError> {
    let mut out = backup_container_prefix(entries.iter().map(|e| e.name.clone()).collect())?;
    for entry in entries {
        out.extend(backup_entry_prefix(entry.name, entry.data.len() as u64)?);
        out.extend(entry.data);
    }
    Ok(out)
}

struct Reader<'a> {
    data: &'a [u8],
    offset: usize,
}

impl Reader<'_> {
    fn exact(&mut self, count: usize) -> Result<&[u8], VaultError> {
        if self.data.len() - self.offset < count {
            return Err(VaultError::Damaged);
        }
        let slice = &self.data[self.offset..self.offset + count];
        self.offset += count;
        Ok(slice)
    }
    fn number(&mut self) -> Result<u64, VaultError> {
        Ok(u64::from_be_bytes(self.exact(8)?.try_into().unwrap()))
    }
}

/// Every entry of a container, or why it is refused: wrong magic is unsupported; truncation,
/// fewer than 2 or more than 1,000,000 entries, names that are empty, over 80 bytes, not UTF-8,
/// not accepted or repeated, payloads past the end, an oversized header or index, trailing bytes,
/// or a missing header or index are damaged.
#[uniffi::export]
pub fn backup_container_decode(data: Vec<u8>) -> Result<Vec<BackupEntry>, VaultError> {
    let total = data.len() as u64;
    let mut reader = Reader { data: &data, offset: 0 };
    if reader.exact(8)? != BACKUP_MAGIC {
        return Err(VaultError::Unsupported);
    }
    let count = reader.number()?;
    if !(2..=MAX_ENTRIES).contains(&count) {
        return Err(VaultError::Damaged);
    }
    let mut names = HashSet::new();
    let mut entries = vec![];
    for _ in 0..count {
        let length = reader.number()?;
        if length == 0 || length > 80 {
            return Err(VaultError::Damaged);
        }
        let name = String::from_utf8(reader.exact(length as usize)?.to_vec()).map_err(|_| VaultError::Damaged)?;
        if !backup_name_valid(name.clone()) || !names.insert(name.clone()) {
            return Err(VaultError::Damaged);
        }
        let size = reader.number()?;
        let offset = reader.offset as u64;
        if offset > total || size > total - offset {
            return Err(VaultError::Damaged);
        }
        if name == "header.json" && size > HEADER_MAX_BYTES {
            return Err(VaultError::Damaged);
        }
        if name == "index.sealed" && size > SEALED_INDEX_MAX_BYTES {
            return Err(VaultError::Damaged);
        }
        let payload = reader.exact(size as usize)?.to_vec();
        entries.push(BackupEntry { name, data: payload });
    }
    if reader.offset as u64 != total || !names.contains("header.json") || !names.contains("index.sealed") {
        return Err(VaultError::Damaged);
    }
    Ok(entries)
}
