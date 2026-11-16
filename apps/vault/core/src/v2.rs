//! Format version 2: one encrypted format for every platform, built on age
//! (age-encryption.org/v1, the `age` crate). Version 1 (`format`, `container`'s v1 functions)
//! is untouched; this module is additive. The README's "Format version 2" section is the spec.
//!
//! - Each vault has a random age X25519 identity. Its public recipient is in `header.json`; the
//!   identity itself is only ever stored sealed with age's scrypt recipient, once under the
//!   unlock secret's key material and once under the recovery key.
//! - `index.age` is the library JSON, age-encrypted to the recipient.
//! - Each original is `<UUID>.age`, age-encrypted to the recipient, streamed file to file.
//! - Encryption needs only the recipient; decryption needs the unlocked [`VaultIdentity`], an
//!   opaque object that never crosses the boundary as a string except through
//!   [`VaultIdentity::export_for_custody`].

use crate::container::{FRAMING_V1, Framing, MAX_ENTRIES};
use crate::credential::VaultCredentialKind;
use crate::error::VaultError;
use crate::format::{HEADER_MAX_BYTES, NOTE_MAX_BYTES, SEALED_INDEX_MAX_BYTES};
use crate::library::VaultLibrary;
use crate::time::{base64_data, upper, uuid_string};
use age::secrecy::{ExposeSecret, SecretString};
use age::{Decryptor, Encryptor, x25519};
use base64::Engine;
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::fs::{self, File, OpenOptions};
use std::io::{self, BufReader, BufWriter, Read, Write};
use std::iter;
use std::path::Path;
use std::str::FromStr;
use std::sync::{Arc, Mutex};
use uuid::Uuid;
use zeroize::{Zeroize, Zeroizing};

pub const FORMAT_V2: i64 = 2;
/// scrypt N = 2^17 with age's r = 8, p = 1: 128 · N · r = 128 MiB of memory per attempt.
pub const SCRYPT_LOG_N: u8 = 17;
/// The largest work factor an envelope may ask for (2^18: 256 MiB). A file asking for more is
/// refused before any work, so a crafted backup cannot exhaust a phone's memory.
pub const SCRYPT_MAX_LOG_N: u8 = 18;
pub const BACKUP_MAGIC_V2: &[u8; 8] = b"7DVAULT2";
pub(crate) const FRAMING_V2: Framing = Framing { magic: BACKUP_MAGIC_V2, index: "index.age", blob_suffix: ".age" };
const CHUNK: usize = 1_048_576;
const RECOVERY_KEY_BYTES: usize = 32;
const CREDENTIAL_MAX_BYTES: usize = 4096;

/// The fixed parameters of format version 2.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VaultFormatV2 {
    pub version: i64,
    /// age scrypt work factor (log2 N) for new envelopes.
    pub scrypt_log_n: u8,
    /// The largest work factor accepted when opening an envelope.
    pub scrypt_max_log_n: u8,
    pub recovery_key_bytes: u32,
    pub credential_max_bytes: u32,
    pub header_max_bytes: u64,
    pub sealed_index_max_bytes: u64,
    pub note_max_bytes: u64,
    pub index_name: String,
    pub backup_magic: String,
    pub stream_chunk_bytes: u64,
}

#[uniffi::export]
pub fn vault_format_v2() -> VaultFormatV2 {
    VaultFormatV2 {
        version: FORMAT_V2,
        scrypt_log_n: SCRYPT_LOG_N,
        scrypt_max_log_n: SCRYPT_MAX_LOG_N,
        recovery_key_bytes: RECOVERY_KEY_BYTES as u32,
        credential_max_bytes: CREDENTIAL_MAX_BYTES as u32,
        header_max_bytes: HEADER_MAX_BYTES,
        sealed_index_max_bytes: SEALED_INDEX_MAX_BYTES,
        note_max_bytes: NOTE_MAX_BYTES,
        index_name: FRAMING_V2.index.to_string(),
        backup_magic: String::from_utf8_lossy(BACKUP_MAGIC_V2).to_string(),
        stream_chunk_bytes: CHUNK as u64,
    }
}

// MARK: header

/// `header.json`, version 2. `password` and `recovery` are complete age files (Base64 in JSON),
/// each sealing the vault identity with age's scrypt recipient.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct VaultHeaderV2 {
    pub version: i64,
    #[serde(with = "upper")]
    pub id: Uuid,
    /// The vault's age recipient, `age1…`.
    pub recipient: String,
    #[serde(rename = "credentialKind")]
    pub credential_kind: VaultCredentialKind,
    #[serde(with = "base64_data")]
    pub password: Vec<u8>,
    #[serde(with = "base64_data")]
    pub recovery: Vec<u8>,
}

#[derive(Deserialize)]
struct VersionOnly {
    version: i64,
}

/// A `header.json`'s format version (1 or 2), read before choosing a decoder. Unreadable is damaged.
#[uniffi::export]
pub fn header_format_version(data: Vec<u8>) -> Result<i64, VaultError> {
    if data.is_empty() || data.len() as u64 > HEADER_MAX_BYTES {
        return Err(VaultError::Damaged);
    }
    serde_json::from_slice::<VersionOnly>(&data).map(|v| v.version).map_err(|_| VaultError::Damaged)
}

#[uniffi::export]
pub fn header_v2_encode(header: &VaultHeaderV2) -> Result<Vec<u8>, VaultError> {
    serde_json::to_vec(header).map_err(|_| VaultError::Damaged)
}

/// Read a version 2 `header.json`: 1…16,384 bytes, version 2 (1 is the older format, others
/// unsupported), then a recipient that parses.
#[uniffi::export]
pub fn header_v2_decode(data: Vec<u8>) -> Result<VaultHeaderV2, VaultError> {
    match header_format_version(data.clone())? {
        FORMAT_V2 => {}
        1 => return Err(VaultError::OlderBackup),
        _ => return Err(VaultError::Unsupported),
    }
    let header: VaultHeaderV2 = serde_json::from_slice(&data).map_err(|_| VaultError::Damaged)?;
    x25519::Recipient::from_str(&header.recipient).map_err(|_| VaultError::Damaged)?;
    Ok(header)
}

/// The sealed plaintext's first line: binds an envelope to its vault and its role.
pub fn password_context_v2(id: Uuid) -> String {
    format!("Vault/password/v2/{}", uuid_string(&id))
}

pub fn recovery_context_v2(id: Uuid) -> String {
    format!("Vault/recovery/v2/{}", uuid_string(&id))
}

// MARK: identity

/// A vault's unlocked age identity. Opaque to the platforms; the secret is wiped by [`Self::wipe`]
/// (call it on lock) and when the object is dropped.
#[derive(uniffi::Object)]
pub struct VaultIdentity {
    inner: Mutex<Option<x25519::Identity>>,
    recipient: String,
}

impl VaultIdentity {
    fn new(identity: x25519::Identity) -> Arc<Self> {
        let recipient = identity.to_public().to_string();
        Arc::new(Self { inner: Mutex::new(Some(identity)), recipient })
    }

    fn with<T>(&self, run: impl FnOnce(&x25519::Identity) -> Result<T, VaultError>) -> Result<T, VaultError> {
        let guard = self.inner.lock().map_err(|_| VaultError::Locked)?;
        match guard.as_ref() {
            Some(identity) => run(identity),
            None => Err(VaultError::Locked),
        }
    }
}

#[uniffi::export]
impl VaultIdentity {
    /// The public recipient, `age1…`.
    pub fn recipient(&self) -> String {
        self.recipient.clone()
    }

    /// Forget the secret now. Every later use is `Locked`.
    pub fn wipe(&self) {
        if let Ok(mut guard) = self.inner.lock() {
            guard.take(); // x25519-dalek's StaticSecret zeroizes on drop.
        }
    }

    pub fn is_wiped(&self) -> bool {
        self.inner.lock().map(|g| g.is_none()).unwrap_or(true)
    }

    /// The identity's secret (`AGE-SECRET-KEY-1…`, UTF-8), solely for the platform to wrap with a
    /// hardware-held key for biometric unlock. Wipe the returned bytes after wrapping them.
    pub fn export_for_custody(&self) -> Result<Vec<u8>, VaultError> {
        self.with(|identity| Ok(identity.to_string().expose_secret().as_bytes().to_vec()))
    }
}

/// A new random identity for a new vault (or a v1 → v2 migration).
#[uniffi::export]
pub fn vault_identity_generate() -> Arc<VaultIdentity> {
    VaultIdentity::new(x25519::Identity::generate())
}

/// An identity unwrapped from biometric custody, accepted only if it is `header`'s.
#[uniffi::export]
pub fn vault_identity_from_custody(secret: Vec<u8>, header: &VaultHeaderV2) -> Result<Arc<VaultIdentity>, VaultError> {
    let mut secret = Zeroizing::new(secret);
    let text = std::str::from_utf8(&secret).map_err(|_| VaultError::InvalidCredential)?;
    let identity = x25519::Identity::from_str(text).map_err(|_| VaultError::InvalidCredential)?;
    secret.zeroize();
    if identity.to_public().to_string() != header.recipient {
        return Err(VaultError::InvalidCredential);
    }
    Ok(VaultIdentity::new(identity))
}

/// 32 random bytes, standard Base64: what the person keeps (the same shape as version 1's).
#[uniffi::export]
pub fn generate_recovery_key() -> String {
    let mut bytes = Zeroizing::new([0u8; RECOVERY_KEY_BYTES]);
    rand::rngs::OsRng.fill_bytes(bytes.as_mut());
    base64::engine::general_purpose::STANDARD.encode(&bytes[..])
}

// MARK: envelopes

fn seal_envelope(identity: &x25519::Identity, context: &str, material: &str) -> Result<Vec<u8>, VaultError> {
    if material.len() > CREDENTIAL_MAX_BYTES {
        return Err(VaultError::Unsupported);
    }
    let secret = identity.to_string();
    let plaintext = Zeroizing::new(format!("{context}\n{}", secret.expose_secret()));
    let mut recipient = age::scrypt::Recipient::new(SecretString::from(material.to_string()));
    recipient.set_work_factor(SCRYPT_LOG_N);
    let encryptor = Encryptor::with_recipients(iter::once(&recipient as &dyn age::Recipient)).map_err(|_| VaultError::Damaged)?;
    let mut out = vec![];
    let mut writer = encryptor.wrap_output(&mut out).map_err(|_| VaultError::Damaged)?;
    writer.write_all(plaintext.as_bytes()).map_err(|_| VaultError::Damaged)?;
    writer.finish().map_err(|_| VaultError::Damaged)?;
    Ok(out)
}

/// Open an envelope with `material`: a wrong secret is an invalid credential; an envelope for
/// another vault or role, or whose identity is not the header's recipient, is damaged.
fn open_envelope(envelope: &[u8], context: &str, material: &str, recipient: &str) -> Result<Arc<VaultIdentity>, VaultError> {
    if material.len() > CREDENTIAL_MAX_BYTES {
        return Err(VaultError::InvalidCredential);
    }
    let decryptor = Decryptor::new_buffered(envelope).map_err(|_| VaultError::Damaged)?;
    if !decryptor.is_scrypt() {
        return Err(VaultError::Damaged);
    }
    let mut identity = age::scrypt::Identity::new(SecretString::from(material.to_string()));
    identity.set_max_work_factor(SCRYPT_MAX_LOG_N);
    let mut reader = decryptor.decrypt(iter::once(&identity as &dyn age::Identity)).map_err(|e| match e {
        age::DecryptError::DecryptionFailed | age::DecryptError::NoMatchingKeys => VaultError::InvalidCredential,
        age::DecryptError::ExcessiveWork { .. } => VaultError::Unsupported,
        _ => VaultError::Damaged,
    })?;
    let mut plaintext = Zeroizing::new(vec![]);
    reader.read_to_end(&mut plaintext).map_err(|_| VaultError::Damaged)?;
    let text = std::str::from_utf8(&plaintext).map_err(|_| VaultError::Damaged)?;
    let (line, secret) = text.split_once('\n').ok_or(VaultError::Damaged)?;
    if line != context {
        return Err(VaultError::Damaged);
    }
    let identity = x25519::Identity::from_str(secret).map_err(|_| VaultError::Damaged)?;
    if identity.to_public().to_string() != recipient {
        return Err(VaultError::Damaged);
    }
    Ok(VaultIdentity::new(identity))
}

/// A new vault's header: the credential is validated for `kind` and its domain-separated material
/// seals the identity; the recovery key seals it again.
#[uniffi::export]
pub fn header_v2_new(id: Uuid, identity: &VaultIdentity, kind: VaultCredentialKind, credential: String, recovery_key: String) -> Result<VaultHeaderV2, VaultError> {
    kind.validate(&credential)?;
    let credential = Zeroizing::new(credential);
    let recovery_key = Zeroizing::new(recovery_key);
    identity.with(|secret| {
        let material = Zeroizing::new(kind.material(&credential));
        Ok(VaultHeaderV2 {
            version: FORMAT_V2,
            id,
            recipient: identity.recipient(),
            credential_kind: kind,
            password: seal_envelope(secret, &password_context_v2(id), &material)?,
            recovery: seal_envelope(secret, &recovery_context_v2(id), &recovery_key)?,
        })
    })
}

/// Unlock or restore: the identity for `credential` typed as `kind`, or with `recovery` the
/// recovery key. A kind the vault does not use, or a wrong secret, is an invalid credential.
#[uniffi::export]
pub fn header_v2_open(header: &VaultHeaderV2, credential: String, kind: VaultCredentialKind, recovery: bool) -> Result<Arc<VaultIdentity>, VaultError> {
    let credential = Zeroizing::new(credential);
    if recovery {
        open_envelope(&header.recovery, &recovery_context_v2(header.id), &credential, &header.recipient)
    } else if header.credential_kind == kind {
        let material = Zeroizing::new(kind.material(&credential));
        open_envelope(&header.password, &password_context_v2(header.id), &material, &header.recipient)
    } else {
        Err(VaultError::InvalidCredential)
    }
}

/// Whether `credential` as `kind` opens this vault: creating or changing a secret refuses one
/// that already opens another vault.
#[uniffi::export]
pub fn header_v2_opens(header: &VaultHeaderV2, credential: String, kind: VaultCredentialKind) -> bool {
    header_v2_open(header, credential, kind, false).is_ok()
}

/// Change the unlock method or secret: only the password envelope is replaced. The index and
/// originals are untouched, so content is never re-encrypted.
#[uniffi::export]
pub fn header_v2_rewrap_password(header: &VaultHeaderV2, identity: &VaultIdentity, kind: VaultCredentialKind, credential: String) -> Result<VaultHeaderV2, VaultError> {
    kind.validate(&credential)?;
    check_identity(header, identity)?;
    let credential = Zeroizing::new(credential);
    let material = Zeroizing::new(kind.material(&credential));
    let password = identity.with(|secret| seal_envelope(secret, &password_context_v2(header.id), &material))?;
    Ok(VaultHeaderV2 { credential_kind: kind, password, ..header.clone() })
}

/// A replacement recovery key's envelope.
#[uniffi::export]
pub fn header_v2_rewrap_recovery(header: &VaultHeaderV2, identity: &VaultIdentity, recovery_key: String) -> Result<VaultHeaderV2, VaultError> {
    check_identity(header, identity)?;
    let recovery_key = Zeroizing::new(recovery_key);
    let recovery = identity.with(|secret| seal_envelope(secret, &recovery_context_v2(header.id), &recovery_key))?;
    Ok(VaultHeaderV2 { recovery, ..header.clone() })
}

fn check_identity(header: &VaultHeaderV2, identity: &VaultIdentity) -> Result<(), VaultError> {
    if identity.recipient != header.recipient {
        return Err(VaultError::Damaged);
    }
    Ok(())
}

fn recipient(text: &str) -> Result<x25519::Recipient, VaultError> {
    x25519::Recipient::from_str(text).map_err(|_| VaultError::Damaged)
}

// MARK: age streams

fn io_error(error: io::Error) -> VaultError {
    match error.kind() {
        io::ErrorKind::NotFound => VaultError::MissingFile,
        io::ErrorKind::StorageFull | io::ErrorKind::QuotaExceeded => VaultError::NoSpace,
        _ => VaultError::Damaged,
    }
}

fn encrypt_stream<W: Write>(recipient_text: &str, input: &mut dyn Read, output: W) -> Result<(i64, String, W), VaultError> {
    let recipient = recipient(recipient_text)?;
    let encryptor = Encryptor::with_recipients(iter::once(&recipient as &dyn age::Recipient)).map_err(|_| VaultError::Damaged)?;
    let mut writer = encryptor.wrap_output(output).map_err(io_error)?;
    let mut hash = Sha256::new();
    let mut bytes: i64 = 0;
    let mut buffer = Zeroizing::new(vec![0u8; CHUNK]);
    loop {
        let count = match input.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(io_error(e)),
        };
        hash.update(&buffer[..count]);
        bytes += count as i64;
        writer.write_all(&buffer[..count]).map_err(io_error)?;
    }
    let output = writer.finish().map_err(io_error)?;
    Ok((bytes, hex(&hash.finalize()), output))
}

/// Decrypt `input`, passing each authenticated chunk to `consume`, and check the plaintext is
/// exactly `bytes` long with SHA-256 `digest`. A wrong key, a modified or truncated file, or
/// trailing bytes are damaged.
fn decrypt_stream(identity: &VaultIdentity, input: impl Read, bytes: i64, digest: &str, mut consume: impl FnMut(&[u8]) -> Result<(), VaultError>) -> Result<(), VaultError> {
    identity.with(|secret| {
        let decryptor = Decryptor::new(input).map_err(|_| VaultError::Damaged)?;
        let mut reader = decryptor.decrypt(iter::once(secret as &dyn age::Identity)).map_err(|_| VaultError::Damaged)?;
        let mut hash = Sha256::new();
        let mut total: i64 = 0;
        let mut buffer = Zeroizing::new(vec![0u8; CHUNK]);
        loop {
            let count = match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(n) => n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => return Err(VaultError::Damaged),
            };
            total += count as i64;
            if total > bytes {
                return Err(VaultError::Damaged);
            }
            hash.update(&buffer[..count]);
            consume(&buffer[..count])?;
        }
        if total != bytes || hex(&hash.finalize()) != digest {
            return Err(VaultError::Damaged);
        }
        Ok(())
    })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// A new file that is not there yet, owner-only on Unix.
fn create_new(path: &Path) -> Result<File, VaultError> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|e| if e.kind() == io::ErrorKind::AlreadyExists { VaultError::Damaged } else { io_error(e) })
}

/// What an encrypted original measured: its plaintext size and lower-case hex SHA-256.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PlainDigest {
    pub bytes: i64,
    pub digest: String,
}

fn write_encrypted(recipient_text: &str, input: &mut dyn Read, destination: &Path) -> Result<PlainDigest, VaultError> {
    // Only a file this call created is removed on failure, never one that was already there.
    let file = create_new(destination)?;
    let result = (|| {
        let (bytes, digest, writer) = encrypt_stream(recipient_text, input, BufWriter::new(file))?;
        let file = writer.into_inner().map_err(|e| io_error(e.into_error()))?;
        file.sync_all().map_err(io_error)?;
        Ok(PlainDigest { bytes, digest })
    })();
    if result.is_err() {
        let _ = fs::remove_file(destination);
    }
    result
}

/// Encrypt the file at `source` to the vault's recipient as a new file at `destination` (which
/// must not exist), streaming in 1 MiB chunks, and measure the plaintext. Needs no secret. On any
/// failure `destination` is removed, so an interrupted import never leaves a partial original.
#[uniffi::export]
pub fn file_encrypt(recipient: String, source: String, destination: String) -> Result<PlainDigest, VaultError> {
    let mut input = BufReader::new(File::open(&source).map_err(io_error)?);
    write_encrypted(&recipient, &mut input, Path::new(&destination))
}

/// A duplicate of an open descriptor the platform lends for the length of one call (an Android
/// document or photo-picker `ParcelFileDescriptor`, whose path the app may not open itself).
#[cfg(unix)]
fn borrowed_file(fd: i32) -> Result<File, VaultError> {
    use std::os::fd::BorrowedFd;
    if fd < 0 {
        return Err(VaultError::MissingFile);
    }
    // SAFETY: the caller keeps `fd` open for the duration of the call; we only duplicate it.
    let owned = unsafe { BorrowedFd::borrow_raw(fd) }.try_clone_to_owned().map_err(io_error)?;
    Ok(File::from(owned))
}

/// [`file_encrypt`] reading from an open descriptor, read once from its current position. The
/// caller keeps `source_fd` open until this returns and closes it afterwards.
#[cfg(unix)]
#[uniffi::export]
pub fn file_encrypt_fd(recipient: String, source_fd: i32, destination: String) -> Result<PlainDigest, VaultError> {
    let mut input = BufReader::new(borrowed_file(source_fd)?);
    write_encrypted(&recipient, &mut input, Path::new(&destination))
}

/// A note's text (or any small buffer, at most 5 MB) encrypted as a new file at `destination`.
#[uniffi::export]
pub fn data_encrypt(recipient: String, data: Vec<u8>, destination: String) -> Result<PlainDigest, VaultError> {
    let data = Zeroizing::new(data);
    if data.len() as u64 > NOTE_MAX_BYTES {
        return Err(VaultError::NoSpace);
    }
    write_encrypted(&recipient, &mut &data[..], Path::new(&destination))
}

/// Check an original decrypts with `identity` to exactly `bytes` bytes with SHA-256 `digest`.
#[uniffi::export]
pub fn file_verify(identity: &VaultIdentity, path: String, bytes: i64, digest: String) -> Result<(), VaultError> {
    let input = BufReader::new(File::open(&path).map_err(io_error)?);
    decrypt_stream(identity, input, bytes, &digest, |_| Ok(()))
}

/// Decrypt an original to a new file at `destination` (which must not exist), verified against
/// `bytes` and `digest`. The platform writes to a partial name and renames on success; on any
/// failure `destination` is removed.
#[uniffi::export]
pub fn file_decrypt(identity: &VaultIdentity, path: String, destination: String, bytes: i64, digest: String) -> Result<(), VaultError> {
    let destination = Path::new(&destination);
    let input = BufReader::new(File::open(&path).map_err(io_error)?);
    let created = create_new(destination)?;
    let result = (|| {
        let mut output = BufWriter::new(created);
        decrypt_stream(identity, input, bytes, &digest, |chunk| output.write_all(chunk).map_err(io_error))?;
        output.into_inner().map_err(|e| io_error(e.into_error()))?.sync_all().map_err(io_error)
    })();
    if result.is_err() {
        let _ = fs::remove_file(destination);
    }
    result
}

/// A small original (a note, at most 5 MB) decrypted into memory and verified.
#[uniffi::export]
pub fn data_decrypt(identity: &VaultIdentity, path: String, bytes: i64, digest: String) -> Result<Vec<u8>, VaultError> {
    if bytes < 0 || bytes as u64 > NOTE_MAX_BYTES {
        return Err(VaultError::Unsupported);
    }
    let input = BufReader::new(File::open(&path).map_err(io_error)?);
    let mut out = Vec::with_capacity(bytes as usize);
    decrypt_stream(identity, input, bytes, &digest, |chunk| {
        out.extend_from_slice(chunk);
        Ok(())
    })?;
    Ok(out)
}

// MARK: index

/// The index to write as `index.age`: validated and encoded ([`crate::library_encode`]), then
/// encrypted to the recipient. Needs no secret.
#[uniffi::export]
pub fn index_seal(library: &VaultLibrary, recipient: String) -> Result<Vec<u8>, VaultError> {
    let plaintext = Zeroizing::new(crate::format::library_encode(library)?);
    let mut out = vec![];
    encrypt_stream(&recipient, &mut &plaintext[..], &mut out)?;
    Ok(out)
}

/// An `index.age` opened with the identity: 1…32,000,000 bytes, then the library and its identity
/// against the header's ([`crate::library_decode`]).
#[uniffi::export]
pub fn index_open(data: Vec<u8>, identity: &VaultIdentity, header_id: Uuid) -> Result<VaultLibrary, VaultError> {
    if data.is_empty() || data.len() as u64 > SEALED_INDEX_MAX_BYTES {
        return Err(VaultError::Damaged);
    }
    let plaintext = identity.with(|secret| {
        let decryptor = Decryptor::new_buffered(&data[..]).map_err(|_| VaultError::Damaged)?;
        let mut reader = decryptor.decrypt(iter::once(secret as &dyn age::Identity)).map_err(|_| VaultError::Damaged)?;
        let mut plaintext = Zeroizing::new(vec![]);
        reader.read_to_end(&mut plaintext).map_err(|_| VaultError::Damaged)?;
        Ok(plaintext)
    })?;
    crate::format::library_decode(plaintext.to_vec(), header_id)
}

// MARK: files in a vault directory

/// An original's file name: `<UUID>.age`.
#[uniffi::export]
pub fn blob_name_v2(item: Uuid) -> String {
    FRAMING_V2.blob_name(item)
}

/// Automatic backup's revision: SHA-256 (hex) of `header.json` then `index.age` in `directory`.
/// Every change to content, names or credentials changes one of them.
#[uniffi::export]
pub fn vault_revision_v2(directory: String) -> Result<String, VaultError> {
    let directory = Path::new(&directory);
    let mut hash = Sha256::new();
    for (name, max) in [("header.json", HEADER_MAX_BYTES), (FRAMING_V2.index, SEALED_INDEX_MAX_BYTES)] {
        let data = bounded_read(&directory.join(name), max)?;
        hash.update(&data);
    }
    Ok(hex(&hash.finalize()))
}

fn bounded_read(path: &Path, max: u64) -> Result<Vec<u8>, VaultError> {
    let size = fs::metadata(path).map_err(io_error)?.len();
    if size == 0 || size > max {
        return Err(VaultError::Damaged);
    }
    fs::read(path).map_err(io_error)
}

// MARK: backup (.vault7 v2)

/// Only `header.json`, `index.age` and canonical upper-case `<UUID>.age` names are accepted.
#[uniffi::export]
pub fn backup_v2_name_valid(name: String) -> bool {
    FRAMING_V2.name_valid(&name)
}

/// The names a version 2 backup holds, in the order they are written.
#[uniffi::export]
pub fn backup_v2_entry_names(library: &VaultLibrary) -> Vec<String> {
    FRAMING_V2.entry_names(library)
}

/// Restore's last framing check, after the index is opened.
#[uniffi::export]
pub fn backup_v2_names_match(names: Vec<String>, library: &VaultLibrary) -> Result<(), VaultError> {
    FRAMING_V2.names_match(names, library)
}

#[uniffi::export]
pub fn backup_v2_container_encode(entries: Vec<crate::container::BackupEntry>) -> Result<Vec<u8>, VaultError> {
    FRAMING_V2.encode(entries)
}

/// The in-memory reader, with the streamed reader's checks: a version 1 container is
/// [`VaultError::OlderBackup`], any other magic unsupported, every framing fault damaged.
#[uniffi::export]
pub fn backup_v2_container_decode(data: Vec<u8>) -> Result<Vec<crate::container::BackupEntry>, VaultError> {
    FRAMING_V2.decode(&data, other_magic)
}

fn other_magic(magic: &[u8]) -> VaultError {
    if magic == FRAMING_V1.magic { VaultError::OlderBackup } else { VaultError::Unsupported }
}

/// Write `library`'s backup from the vault `directory` to a new file at `output`: the framing,
/// then `header.json`, `index.age` and every original, streamed. Only regular files, never
/// links. On failure `output` is removed.
#[uniffi::export]
pub fn backup_v2_write(directory: String, library: &VaultLibrary, output: String) -> Result<(), VaultError> {
    let output = Path::new(&output);
    let directory = Path::new(&directory);
    let created = create_new(output)?;
    let result = (|| {
        let names = FRAMING_V2.entry_names(library);
        let mut writer = BufWriter::new(created);
        writer.write_all(&FRAMING_V2.container_prefix(&names)?).map_err(io_error)?;
        for name in &names {
            let path = directory.join(name);
            let meta = fs::symlink_metadata(&path).map_err(|_| VaultError::MissingFile)?;
            if !meta.file_type().is_file() {
                return Err(VaultError::MissingFile);
            }
            let mut input = File::open(&path).map_err(io_error)?;
            writer.write_all(&FRAMING_V2.entry_prefix(name, meta.len())?).map_err(io_error)?;
            let copied = io::copy(&mut (&mut input).take(meta.len()), &mut writer).map_err(io_error)?;
            if copied != meta.len() {
                return Err(VaultError::MissingFile);
            }
        }
        writer.into_inner().map_err(|e| io_error(e.into_error()))?.sync_all().map_err(io_error)
    })();
    if result.is_err() {
        let _ = fs::remove_file(output);
    }
    result
}

fn read_exact(input: &mut impl Read, count: usize) -> Result<Vec<u8>, VaultError> {
    let mut data = vec![0u8; count];
    input.read_exact(&mut data).map_err(|_| VaultError::Damaged)?;
    Ok(data)
}

fn read_number(input: &mut impl Read) -> Result<u64, VaultError> {
    Ok(u64::from_be_bytes(read_exact(input, 8)?.try_into().unwrap()))
}

/// Read a version 2 backup at `input` (a file, or a readable stream path such as
/// `/proc/self/fd/N`) into the empty `staging` directory, applying
/// [`backup_v2_container_decode`]'s checks in its order without loading it whole. Entries are
/// only ever created under their validated fixed names, so no path in a backup is trusted.
/// Returns the names written; the caller then opens the header and index, checks
/// [`backup_v2_names_match`] and verifies every original before publishing the directory.
#[uniffi::export]
pub fn backup_v2_read(input: String, staging: String) -> Result<Vec<String>, VaultError> {
    read_backup(File::open(&input).map_err(io_error)?, &staging)
}

/// [`backup_v2_read`] from an open descriptor (kept open by the caller for the call).
#[cfg(unix)]
#[uniffi::export]
pub fn backup_v2_read_fd(input_fd: i32, staging: String) -> Result<Vec<String>, VaultError> {
    read_backup(borrowed_file(input_fd)?, &staging)
}

fn read_backup(file: File, staging: &str) -> Result<Vec<String>, VaultError> {
    let total = file.metadata().ok().filter(|m| m.is_file()).map(|m| m.len());
    let staging = Path::new(staging);
    let mut reader = BufReader::new(file);
    let mut offset: u64 = 0;
    let magic = read_exact(&mut reader, 8)?;
    if magic != FRAMING_V2.magic {
        return Err(other_magic(&magic));
    }
    let count = read_number(&mut reader)?;
    offset += 16;
    if !Framing::count_valid(count) || count > MAX_ENTRIES {
        return Err(VaultError::Damaged);
    }
    let mut names = HashSet::new();
    let mut written = vec![];
    for _ in 0..count {
        let length = read_number(&mut reader)?;
        if length == 0 || length > 80 {
            return Err(VaultError::Damaged);
        }
        let name = String::from_utf8(read_exact(&mut reader, length as usize)?).map_err(|_| VaultError::Damaged)?;
        if !FRAMING_V2.name_valid(&name) || !names.insert(name.clone()) {
            return Err(VaultError::Damaged);
        }
        let size = read_number(&mut reader)?;
        offset += 16 + length;
        let remaining = total.map(|t| t.saturating_sub(offset));
        if total.is_some_and(|t| offset > t) || !FRAMING_V2.size_valid(&name, size, remaining) {
            return Err(VaultError::Damaged);
        }
        let mut output = BufWriter::new(create_new(&staging.join(&name))?);
        let copied = io::copy(&mut (&mut reader).take(size), &mut output).map_err(io_error)?;
        if copied != size {
            return Err(VaultError::Damaged);
        }
        output.into_inner().map_err(|e| io_error(e.into_error()))?.sync_all().map_err(io_error)?;
        offset += size;
        written.push(name);
    }
    let mut probe = [0u8; 1];
    let trailing = loop {
        match reader.read(&mut probe) {
            Ok(n) => break n,
            Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => return Err(VaultError::Damaged),
        }
    };
    if trailing != 0 || !names.contains("header.json") || !names.contains(FRAMING_V2.index) {
        return Err(VaultError::Damaged);
    }
    Ok(written)
}

/// Which `.vault7` generation the first eight bytes of a file are: 1, 2, or 0 for neither.
#[uniffi::export]
pub fn backup_format_version(prefix: Vec<u8>) -> i64 {
    if prefix.len() < 8 {
        0
    } else if &prefix[..8] == FRAMING_V1.magic {
        1
    } else if &prefix[..8] == FRAMING_V2.magic {
        2
    } else {
        0
    }
}
