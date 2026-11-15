//! The portable backup (Swift `Backup.swift`), a `.scannerbackup` file:
//!
//! ```text
//! "SEVSCAN1\n"                  9 bytes
//! index length                  UInt32 little-endian
//! index                         JSONEncoder bytes of {"version":1,"entries":[...]}
//! index checksum                CRC-32 (IEEE), UInt32 little-endian
//! per page, in entry order:     the JPEG bytes, then their CRC-32, UInt32 little-endian
//! ```
//!
//! Entries are the active documents, then Recently Deleted. The store streams page files itself;
//! the core builds and checks the header, validates every record and the exact file length before
//! anything is restored, and gives the checksum to run over each page as it is copied.

use crate::documents::{MAX_DOCUMENT_IMAGE_BYTES, MAX_MANIFEST_BYTES, MAX_PAGE_BYTES, ScanDocument, ScanError, document_encode};
use crate::json::{self, swift_int};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

pub const BACKUP_MAGIC: &[u8] = b"SEVSCAN1\n";
pub const MAX_BACKUP_BYTES: u64 = 1_000_000_000;
pub const MAX_INDEX_BYTES: u64 = 16_000_000;
pub const MAX_BACKUP_DOCUMENTS: usize = 1_000;
/// Magic plus the index length.
pub const BACKUP_PREAMBLE_BYTES: u64 = 13;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct BackupEntry {
    pub document: ScanDocument,
    pub deleted: bool,
    /// Each page's image size in bytes, in page order.
    #[serde(with = "swift_int::vec")]
    pub sizes: Vec<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct BackupIndex {
    #[serde(with = "swift_int")]
    pub version: i64,
    pub entries: Vec<BackupEntry>,
}

#[uniffi::export]
pub fn backup_magic() -> Vec<u8> {
    BACKUP_MAGIC.to_vec()
}

static TABLE: [u32; 256] = {
    let mut table = [0u32; 256];
    let mut n = 0;
    while n < 256 {
        let mut c = n as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 == 1 { 0xedb8_8320 ^ (c >> 1) } else { c >> 1 };
            k += 1;
        }
        table[n] = c;
        n += 1;
    }
    table
};

/// CRC-32 (IEEE 802.3) of `data`.
#[uniffi::export]
pub fn crc32(data: &[u8]) -> u32 {
    crc32_continue(0, data)
}

/// CRC-32 of everything so far followed by `data`, given the checksum of everything so far
/// (start from 0). Lets a page be checked in chunks as it streams.
#[uniffi::export]
pub fn crc32_continue(checksum: u32, data: &[u8]) -> u32 {
    let mut crc = checksum ^ 0xffff_ffff;
    for &byte in data {
        crc = TABLE[((crc ^ byte as u32) & 0xff) as usize] ^ (crc >> 8);
    }
    crc ^ 0xffff_ffff
}

/// A checksum as it is written after a page: UInt32 little-endian.
#[uniffi::export]
pub fn backup_checksum_bytes(checksum: u32) -> Vec<u8> {
    checksum.to_le_bytes().to_vec()
}

/// `exportBackup` up to the first page: checks the limits (1,000 documents; each page 1 byte to
/// 20 MB; 1 GB in all) and returns magic, index length, index and its checksum. Page files follow,
/// each with `backup_checksum_bytes(crc32(page))`. Entries are active documents, then deleted ones.
#[uniffi::export]
pub fn backup_header(entries: Vec<BackupEntry>) -> Result<Vec<u8>, ScanError> {
    if entries.len() > MAX_BACKUP_DOCUMENTS {
        return Err(ScanError::TooLarge);
    }
    let mut total: u64 = 0;
    for entry in &entries {
        if entry.sizes.len() != entry.document.pages.len() {
            return Err(ScanError::InvalidDocument);
        }
        for &size in &entry.sizes {
            if size <= 0 || size as u64 > MAX_PAGE_BYTES {
                return Err(ScanError::TooLarge);
            }
            total += size as u64 + 4;
            if total > MAX_BACKUP_BYTES {
                return Err(ScanError::TooLarge);
            }
        }
    }
    let index = json::encode(&BackupIndex { version: 1, entries }).map_err(|_| ScanError::InvalidDocument)?;
    if index.len() as u64 > MAX_INDEX_BYTES || total + index.len() as u64 + 17 > MAX_BACKUP_BYTES {
        return Err(ScanError::TooLarge);
    }
    let mut out = Vec::with_capacity(index.len() + 17);
    out.extend_from_slice(BACKUP_MAGIC);
    out.extend_from_slice(&(index.len() as u32).to_le_bytes());
    out.extend_from_slice(&index);
    out.extend_from_slice(&crc32(&index).to_le_bytes());
    Ok(out)
}

/// `restoreBackup`, first step: the file's total length and its first 13 bytes. Returns the index
/// length; read that many bytes plus the 4-byte checksum and pass them to `read_backup_index`.
#[uniffi::export]
pub fn backup_index_length(file_length: u64, preamble: &[u8]) -> Result<u32, ScanError> {
    if file_length > MAX_BACKUP_BYTES {
        return Err(ScanError::TooLarge);
    }
    if preamble.len() < BACKUP_MAGIC.len() || &preamble[..BACKUP_MAGIC.len()] != BACKUP_MAGIC || preamble.len() != BACKUP_PREAMBLE_BYTES as usize {
        return Err(ScanError::InvalidDocument);
    }
    let length = u32::from_le_bytes(preamble[9..13].try_into().unwrap());
    if length as u64 > MAX_INDEX_BYTES {
        return Err(ScanError::TooLarge);
    }
    Ok(length)
}

/// `restoreBackup`, second step: `header` is the index followed by its checksum. Validates every
/// record (unique IDs, valid documents, manifests within 6 MB, one size per page, each 1 byte to
/// 20 MB, 100 MB per document) and that the file is exactly as long as the index says, before a
/// single page is copied. Restored documents then get new IDs, so nothing existing is replaced;
/// each page's bytes must match the checksum that follows them.
#[uniffi::export]
pub fn read_backup_index(file_length: u64, header: &[u8]) -> Result<BackupIndex, ScanError> {
    if header.len() < 4 {
        return Err(ScanError::InvalidDocument);
    }
    let (index_bytes, checksum) = header.split_at(header.len() - 4);
    if u32::from_le_bytes(checksum.try_into().unwrap()) != crc32(index_bytes) {
        return Err(ScanError::InvalidDocument);
    }
    let index: BackupIndex = serde_json::from_slice(index_bytes).map_err(|_| ScanError::Unreadable)?;
    let ids: HashSet<_> = index.entries.iter().map(|e| e.document.id).collect();
    if index.version != 1 || index.entries.len() > MAX_BACKUP_DOCUMENTS || ids.len() != index.entries.len() {
        return Err(ScanError::InvalidDocument);
    }
    let mut expected = BACKUP_MAGIC.len() as u64 + 8 + index_bytes.len() as u64;
    for entry in &index.entries {
        entry.document.validate()?;
        if document_encode(&entry.document)?.len() as u64 > MAX_MANIFEST_BYTES {
            return Err(ScanError::TooLarge);
        }
        // In Swift's order, so the sum is only taken over sizes already known to be small.
        let sizes_ok = entry.sizes.len() == entry.document.pages.len() && entry.sizes.iter().all(|&s| s > 0 && s as u64 <= MAX_PAGE_BYTES);
        let sum = if sizes_ok { entry.sizes.iter().map(|&s| s as u64).sum::<u64>() } else { 0 };
        if !sizes_ok || sum > MAX_DOCUMENT_IMAGE_BYTES {
            return Err(ScanError::InvalidDocument);
        }
        expected += sum + entry.sizes.len() as u64 * 4;
        if expected > MAX_BACKUP_BYTES {
            return Err(ScanError::TooLarge);
        }
    }
    if expected != file_length {
        return Err(ScanError::InvalidDocument);
    }
    Ok(index)
}

/// The copy a restore adds: the same document under a new ID (page IDs are kept).
#[uniffi::export]
pub fn restored_copy(document: &ScanDocument) -> ScanDocument {
    ScanDocument { id: uuid::Uuid::new_v4(), ..document.clone() }
}
