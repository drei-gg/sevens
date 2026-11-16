//! The `.vault7` backup framing (VaultStorage's `BackupContainer`): `7DVAULT1`, a big-endian
//! UInt64 entry count, then per entry a UInt64 name length, the UTF-8 name, a UInt64 payload length
//! and the payload. Transport framing only: the index and each payload are authenticated
//! ciphertext on their own. Checks run in the Swift reader's order.
//!
//! The codec here works on bytes in memory. The app streams large files; it can use
//! [`backup_container_prefix`] and [`backup_entry_prefix`] to write, and must apply the same
//! checks as [`backup_container_decode`] when it reads.

use crate::error::VaultError;
use crate::format::{HEADER_MAX_BYTES, SEALED_INDEX_MAX_BYTES};
use crate::time::{parse_uuid, uuid_string};
use crate::library::VaultLibrary;
use std::collections::HashSet;
use uuid::Uuid;

pub const BACKUP_MAGIC: &[u8; 8] = b"7DVAULT1";
pub(crate) const MAX_ENTRIES: u64 = 1_000_000;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct BackupEntry {
    pub name: String,
    pub data: Vec<u8>,
}

/// What tells one `.vault7` generation from another: its magic, its index entry and the suffix of
/// its originals. Version 1 is the iPhone's Apple Encrypted Archive format; version 2 is the
/// cross-platform age format ([`crate::v2`]). The framing and its checks are the same.
#[derive(Clone, Copy)]
pub(crate) struct Framing {
    pub magic: &'static [u8; 8],
    pub index: &'static str,
    pub blob_suffix: &'static str,
}

pub(crate) const FRAMING_V1: Framing = Framing { magic: BACKUP_MAGIC, index: "index.sealed", blob_suffix: ".aea" };

impl Framing {
    pub fn name_valid(self, name: &str) -> bool {
        if name == "header.json" || name == self.index {
            return true;
        }
        let Some(stem) = name.strip_suffix(self.blob_suffix) else { return false };
        let Some(id) = parse_uuid(stem) else { return false };
        name == format!("{}{}", uuid_string(&id), self.blob_suffix)
    }

    pub fn blob_name(self, item: Uuid) -> String {
        format!("{}{}", uuid_string(&item), self.blob_suffix)
    }

    pub fn entry_names(self, library: &VaultLibrary) -> Vec<String> {
        let mut names = vec!["header.json".to_string(), self.index.to_string()];
        names.extend(library.items.iter().map(|i| self.blob_name(i.id)));
        names
    }

    pub fn names_match(self, names: Vec<String>, library: &VaultLibrary) -> Result<(), VaultError> {
        let got: HashSet<String> = names.into_iter().collect();
        let expected: HashSet<String> = self.entry_names(library).into_iter().collect();
        if got != expected {
            return Err(VaultError::Damaged);
        }
        Ok(())
    }

    pub fn container_prefix(self, names: &[String]) -> Result<Vec<u8>, VaultError> {
        let unique: HashSet<&String> = names.iter().collect();
        if names.len() as u64 > MAX_ENTRIES || unique.len() != names.len() {
            return Err(VaultError::Damaged);
        }
        let mut out = self.magic.to_vec();
        out.extend_from_slice(&(names.len() as u64).to_be_bytes());
        Ok(out)
    }

    pub fn entry_prefix(self, name: &str, size: u64) -> Result<Vec<u8>, VaultError> {
        if !self.name_valid(name) {
            return Err(VaultError::Damaged);
        }
        let mut out = (name.len() as u64).to_be_bytes().to_vec();
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(&size.to_be_bytes());
        Ok(out)
    }

    /// The count check after the magic.
    pub fn count_valid(count: u64) -> bool {
        (2..=MAX_ENTRIES).contains(&count)
    }

    /// The size check for one entry, `remaining` bytes after its framing (None when unknown).
    pub fn size_valid(self, name: &str, size: u64, remaining: Option<u64>) -> bool {
        if remaining.is_some_and(|r| size > r) {
            return false;
        }
        if name == "header.json" && size > HEADER_MAX_BYTES {
            return false;
        }
        !(name == self.index && size > SEALED_INDEX_MAX_BYTES)
    }

    pub fn encode(self, entries: Vec<BackupEntry>) -> Result<Vec<u8>, VaultError> {
        let names: Vec<String> = entries.iter().map(|e| e.name.clone()).collect();
        let mut out = self.container_prefix(&names)?;
        for entry in entries {
            out.extend(self.entry_prefix(&entry.name, entry.data.len() as u64)?);
            out.extend(entry.data);
        }
        Ok(out)
    }

    /// `wrong_magic` is what another generation's magic is refused with.
    pub fn decode(self, data: &[u8], other: impl Fn(&[u8]) -> VaultError) -> Result<Vec<BackupEntry>, VaultError> {
        let total = data.len() as u64;
        let mut reader = Reader { data, offset: 0 };
        let magic = reader.exact(8)?;
        if magic != self.magic {
            return Err(other(magic));
        }
        let count = reader.number()?;
        if !Self::count_valid(count) {
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
            if !self.name_valid(&name) || !names.insert(name.clone()) {
                return Err(VaultError::Damaged);
            }
            let size = reader.number()?;
            let offset = reader.offset as u64;
            if offset > total || !self.size_valid(&name, size, Some(total - offset)) {
                return Err(VaultError::Damaged);
            }
            let payload = reader.exact(size as usize)?.to_vec();
            entries.push(BackupEntry { name, data: payload });
        }
        if reader.offset as u64 != total || !names.contains("header.json") || !names.contains(self.index) {
            return Err(VaultError::Damaged);
        }
        Ok(entries)
    }
}

/// Only `header.json`, `index.sealed` and canonical upper-case `<UUID>.aea` names are accepted.
#[uniffi::export]
pub fn backup_name_valid(name: String) -> bool {
    FRAMING_V1.name_valid(&name)
}

/// The names a vault's backup holds, in the order the app writes them.
#[uniffi::export]
pub fn backup_entry_names(library: &VaultLibrary) -> Vec<String> {
    FRAMING_V1.entry_names(library)
}

/// Restore's last framing check, after the index is opened: the container held exactly the
/// header, the index and every item's original.
#[uniffi::export]
pub fn backup_names_match(names: Vec<String>, library: &VaultLibrary) -> Result<(), VaultError> {
    FRAMING_V1.names_match(names, library)
}

/// The file name a manual backup gets.
#[uniffi::export]
pub fn backup_file_name(id: uuid::Uuid) -> String {
    format!("Vault-{}.vault7", uuid_string(&id))
}

/// Magic and entry count. Too many or duplicate names are damaged, as in the Swift writer.
#[uniffi::export]
pub fn backup_container_prefix(names: Vec<String>) -> Result<Vec<u8>, VaultError> {
    FRAMING_V1.container_prefix(&names)
}

/// One entry's framing before its `size` payload bytes.
#[uniffi::export]
pub fn backup_entry_prefix(name: String, size: u64) -> Result<Vec<u8>, VaultError> {
    FRAMING_V1.entry_prefix(&name, size)
}

#[uniffi::export]
pub fn backup_container_encode(entries: Vec<BackupEntry>) -> Result<Vec<u8>, VaultError> {
    FRAMING_V1.encode(entries)
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
    FRAMING_V1.decode(&data, |_| VaultError::Unsupported)
}
