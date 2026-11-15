//! The on-disk format around the cryptography (VaultStorage's `VaultHeader`, `KeyEnvelope`, the
//! index coding and the associated-data strings), version 1. The cryptography itself (PBKDF2,
//! AES-GCM, HKDF, Apple Encrypted Archive, randomness) stays native; this module fixes what it is
//! fed and what it must produce, so the platforms cannot drift.

use crate::credential::VaultCredentialKind;
use crate::error::VaultError;
use crate::library::VaultLibrary;
use crate::time::{base64_data, upper, uuid_string};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// The fixed parameters of format version 1.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VaultFormat {
    /// PBKDF2-HMAC-SHA256 iterations for every credential envelope.
    pub pbkdf2_rounds: u32,
    /// Random salt per envelope, bytes.
    pub salt_bytes: u32,
    /// The random per-vault master key, and every derived key, bytes.
    pub key_bytes: u32,
    /// Random bytes in a recovery key (Base64-encoded for the user).
    pub recovery_key_bytes: u32,
    /// The longest credential, in UTF-8 bytes, key derivation accepts.
    pub credential_max_bytes: u32,
    /// `header.json` must be 1…this many bytes.
    pub header_max_bytes: u64,
    /// `index.sealed` must be 1…this many bytes.
    pub sealed_index_max_bytes: u64,
    /// The encoded (plaintext) index may be at most this many bytes, or saving fails with no space.
    pub index_max_bytes: u64,
    /// A note's text, in UTF-8 bytes.
    pub note_max_bytes: u64,
    /// HKDF-SHA256 `info` for a file's key; the salt is the item's `uuidString` in UTF-8.
    pub file_key_info: String,
    /// The Apple Encrypted Archive profile for originals, without compression.
    pub archive_profile: String,
    /// Read and write buffer for streams, bytes.
    pub stream_chunk_bytes: u64,
}

pub const PBKDF2_ROUNDS: u32 = 600_000;
pub const HEADER_MAX_BYTES: u64 = 16_384;
pub const SEALED_INDEX_MAX_BYTES: u64 = 32_000_000;
pub const INDEX_MAX_BYTES: u64 = 31_000_000;
pub const NOTE_MAX_BYTES: u64 = 5_000_000;
pub const FILE_KEY_INFO: &str = "Vault/file/v1";
pub const NOTE_TYPE: &str = "public.plain-text";

#[uniffi::export]
pub fn vault_format() -> VaultFormat {
    VaultFormat {
        pbkdf2_rounds: PBKDF2_ROUNDS,
        salt_bytes: 32,
        key_bytes: 32,
        recovery_key_bytes: 32,
        credential_max_bytes: 4096,
        header_max_bytes: HEADER_MAX_BYTES,
        sealed_index_max_bytes: SEALED_INDEX_MAX_BYTES,
        index_max_bytes: INDEX_MAX_BYTES,
        note_max_bytes: NOTE_MAX_BYTES,
        file_key_info: FILE_KEY_INFO.to_string(),
        archive_profile: "hkdf_sha256_aesctr_hmac__symmetric__none".to_string(),
        stream_chunk_bytes: 1_048_576,
    }
}

/// A master key wrapped by one credential: PBKDF2 salt and rounds, and the AES-GCM combined box
/// (nonce, ciphertext, tag). `Data` fields are Base64 in JSON.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct KeyEnvelope {
    #[serde(with = "base64_data")]
    pub salt: Vec<u8>,
    pub rounds: u32,
    #[serde(with = "base64_data")]
    pub sealed: Vec<u8>,
}

/// The plaintext `header.json`. An absent `credentialKind` is a legacy passphrase vault.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultHeader {
    pub version: i64,
    #[serde(with = "upper")]
    pub id: Uuid,
    pub password: KeyEnvelope,
    pub recovery: KeyEnvelope,
    #[serde(rename = "credentialKind", default, skip_serializing_if = "Option::is_none")]
    pub credential_kind: Option<VaultCredentialKind>,
}

impl VaultHeader {
    /// The kind the password envelope answers to.
    pub fn kind(&self) -> VaultCredentialKind {
        self.credential_kind.unwrap_or(VaultCredentialKind::Passphrase)
    }
}

#[uniffi::export]
pub fn new_vault_header(id: Uuid, password: KeyEnvelope, recovery: KeyEnvelope, kind: Option<VaultCredentialKind>) -> VaultHeader {
    VaultHeader { version: 1, id, password, recovery, credential_kind: kind }
}

#[uniffi::export]
pub fn header_credential_kind(header: &VaultHeader) -> VaultCredentialKind {
    header.kind()
}

/// AES-GCM associated data for the password envelope.
#[uniffi::export]
pub fn password_context(id: Uuid) -> String {
    format!("Vault/password/v1/{}", uuid_string(&id))
}

/// AES-GCM associated data for the recovery envelope.
#[uniffi::export]
pub fn recovery_context(id: Uuid) -> String {
    format!("Vault/recovery/v1/{}", uuid_string(&id))
}

/// AES-GCM associated data for `index.sealed`.
#[uniffi::export]
pub fn index_context(id: Uuid) -> String {
    format!("Vault/index/v1/{}", uuid_string(&id))
}

/// HKDF salt for an original's key: its item `uuidString` in UTF-8.
#[uniffi::export]
pub fn file_key_salt(item: Uuid) -> Vec<u8> {
    uuid_string(&item).into_bytes()
}

/// An original's file name inside the vault directory and the backup.
#[uniffi::export]
pub fn blob_name(item: Uuid) -> String {
    format!("{}.aea", uuid_string(&item))
}

/// The parameters key derivation accepts, checked before deriving: a 32-byte salt, exactly
/// 600,000 rounds and a credential of at most 4096 UTF-8 bytes.
#[uniffi::export]
pub fn key_derivation_check(salt: Vec<u8>, rounds: u32, credential: String) -> Result<(), VaultError> {
    if salt.len() != 32 || rounds != PBKDF2_ROUNDS || credential.len() > 4096 {
        return Err(VaultError::Unsupported);
    }
    Ok(())
}

/// What to unwrap for an unlock or restore attempt: the envelope, the derivation input and the
/// associated data.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct UnlockRoute {
    pub envelope: KeyEnvelope,
    pub material: String,
    pub context: String,
}

/// The route for `credential`, or `None` when the vault's password envelope is for another kind
/// (unlock skips that vault; restore reports an invalid credential). A recovery key fits any vault.
/// Creating or changing a secret uses the non-recovery route to refuse one that opens another vault.
#[uniffi::export]
pub fn unlock_route(header: &VaultHeader, credential: String, kind: VaultCredentialKind, recovery: bool) -> Option<UnlockRoute> {
    if recovery {
        Some(UnlockRoute { envelope: header.recovery.clone(), material: credential, context: recovery_context(header.id) })
    } else if header.kind() == kind {
        Some(UnlockRoute { envelope: header.password.clone(), material: kind.material(&credential), context: password_context(header.id) })
    } else {
        None
    }
}

/// `header.json` as the app writes it (Foundation `JSONEncoder()` keys; key order is not fixed).
#[uniffi::export]
pub fn header_encode(header: &VaultHeader) -> Result<Vec<u8>, VaultError> {
    serde_json::to_vec(header).map_err(|_| VaultError::Damaged)
}

/// Read `header.json`: 1…16,384 bytes, then the JSON, then version 1.
#[uniffi::export]
pub fn header_decode(data: Vec<u8>) -> Result<VaultHeader, VaultError> {
    if data.is_empty() || data.len() as u64 > HEADER_MAX_BYTES {
        return Err(VaultError::Damaged);
    }
    let header: VaultHeader = serde_json::from_slice(&data).map_err(|_| VaultError::Damaged)?;
    if header.version != 1 {
        return Err(VaultError::Unsupported);
    }
    Ok(header)
}

/// The index plaintext to seal: validated first, then encoded; over 31 MB is no space.
#[uniffi::export]
pub fn library_encode(library: &VaultLibrary) -> Result<Vec<u8>, VaultError> {
    library.validate()?;
    let data = serde_json::to_vec(library).map_err(|_| VaultError::Damaged)?;
    if data.len() as u64 > INDEX_MAX_BYTES {
        return Err(VaultError::NoSpace);
    }
    Ok(data)
}

/// An opened index: the JSON, then its identity against the header's, then validation.
#[uniffi::export]
pub fn library_decode(data: Vec<u8>, header_id: Uuid) -> Result<VaultLibrary, VaultError> {
    let library: VaultLibrary = serde_json::from_slice(&data).map_err(|_| VaultError::Damaged)?;
    if library.id != header_id {
        return Err(VaultError::Damaged);
    }
    library.validate()?;
    Ok(library)
}
