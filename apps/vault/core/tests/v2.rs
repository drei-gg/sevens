//! Format version 2 (age): the SPEC's verification gates, run on Linux against the real
//! encryption, plus interoperability with the reference age implementation's test vectors.

use std::fs;
use std::path::{Path, PathBuf};
use std::str::FromStr;
use std::sync::Arc;
use std::time::SystemTime;
use vault_core::*;

const SECRET: &str = "several private testing words";
const PIN: &str = "00123456";

struct Dir(PathBuf);
impl Dir {
    fn new(name: &str) -> Self {
        let path = std::env::temp_dir().join(format!("vault-v2-{name}-{}", Uuid::new_v4()));
        fs::create_dir_all(&path).unwrap();
        Dir(path)
    }
    fn at(&self, name: &str) -> PathBuf {
        self.0.join(name)
    }
    fn s(&self, name: &str) -> String {
        self.at(name).to_string_lossy().to_string()
    }
}
impl Drop for Dir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

/// A vault directory as a platform writes it: header.json and index.age.
struct Vault {
    dir: Dir,
    header: VaultHeaderV2,
    identity: Arc<VaultIdentity>,
    library: VaultLibrary,
    recovery: String,
}

fn create(name: &str, kind: VaultCredentialKind, credential: &str) -> Vault {
    let dir = Dir::new(name);
    let library = new_vault_library(name.to_string());
    let identity = vault_identity_generate();
    let recovery = generate_recovery_key();
    let header = header_v2_new(library.id, &identity, kind, credential.to_string(), recovery.clone()).unwrap();
    fs::write(dir.at("header.json"), header_v2_encode(&header).unwrap()).unwrap();
    fs::write(dir.at("index.age"), index_seal(&library, header.recipient.clone()).unwrap()).unwrap();
    Vault { dir, header, identity, library, recovery }
}

impl Vault {
    fn import(&mut self, name: &str, data: &[u8]) -> VaultItem {
        let source = self.dir.at(&format!("source-{name}"));
        fs::write(&source, data).unwrap();
        let id = Uuid::new_v4();
        let digest = file_encrypt(self.header.recipient.clone(), source.to_string_lossy().to_string(), self.dir.s(&blob_name_v2(id))).unwrap();
        fs::remove_file(source).unwrap();
        let mut item = library_import_item(&self.library, name.to_string(), "public.data".into(), digest.bytes, digest.digest, None);
        item.id = id;
        self.library = library_append_item(&self.library, item.clone());
        self.save();
        item
    }
    fn save(&self) {
        fs::write(self.dir.at("index.age"), index_seal(&self.library, self.header.recipient.clone()).unwrap()).unwrap();
    }
    fn reopen(&self, credential: &str, kind: VaultCredentialKind, recovery: bool) -> Result<VaultLibrary, VaultError> {
        let header = header_v2_decode(fs::read(self.dir.at("header.json")).unwrap())?;
        let identity = header_v2_open(&header, credential.to_string(), kind, recovery)?;
        index_open(fs::read(self.dir.at("index.age")).unwrap(), &identity, header.id)
    }
    fn export(&self, item: &VaultItem) -> Result<Vec<u8>, VaultError> {
        let out = self.dir.at(&format!("export-{}", Uuid::new_v4()));
        file_decrypt(&self.identity, self.dir.s(&blob_name_v2(item.id)), out.to_string_lossy().to_string(), item.bytes, item.digest.clone())?;
        let data = fs::read(&out).unwrap();
        fs::remove_file(out).unwrap();
        Ok(data)
    }
}

fn sample(len: usize) -> Vec<u8> {
    (0..len).map(|i| (i * 31 % 251) as u8).collect()
}

#[test]
fn round_trips_original_bytes_streamed_across_chunks() {
    let mut vault = create("round", VaultCredentialKind::Passphrase, SECRET);
    for len in [0, 1, 65_535, 65_536, 65_537, 3 * 1_048_576 + 7] {
        let data = sample(len);
        let item = vault.import(&format!("f{len}.bin"), &data);
        assert_eq!(item.bytes, len as i64);
        assert_eq!(vault.export(&item).unwrap(), data);
        file_verify(&vault.identity, vault.dir.s(&blob_name_v2(item.id)), item.bytes, item.digest.clone()).unwrap();
    }
    let library = vault.reopen(SECRET, VaultCredentialKind::Passphrase, false).unwrap();
    assert_eq!(library, vault.library);
}

#[test]
fn notes_round_trip_in_memory() {
    let vault = create("note", VaultCredentialKind::Passphrase, SECRET);
    let path = vault.dir.s("note.age");
    let digest = data_encrypt(vault.header.recipient.clone(), "Passport\nBoarding pass".as_bytes().to_vec(), path.clone()).unwrap();
    let text = data_decrypt(&vault.identity, path, digest.bytes, digest.digest).unwrap();
    assert_eq!(text, b"Passport\nBoarding pass");
    assert_eq!(data_encrypt(vault.header.recipient.clone(), vec![0; 5_000_001], vault.dir.s("big.age")), Err(VaultError::NoSpace));
    assert!(!vault.dir.at("big.age").exists());
}

#[test]
fn wrong_credentials_are_refused() {
    let vault = create("wrong", VaultCredentialKind::Passphrase, SECRET);
    assert_eq!(vault.reopen("several private testing word", VaultCredentialKind::Passphrase, false), Err(VaultError::InvalidCredential));
    assert_eq!(vault.reopen("not the recovery key", VaultCredentialKind::Passphrase, true), Err(VaultError::InvalidCredential));
    // The same text typed as another kind never reaches this vault's envelope.
    assert_eq!(vault.reopen(SECRET, VaultCredentialKind::Pin, false), Err(VaultError::InvalidCredential));
    assert!(vault.reopen(&vault.recovery, VaultCredentialKind::Passphrase, true).is_ok());
    assert!(vault.reopen(&format!("{} ", vault.recovery), VaultCredentialKind::Passphrase, true).is_err());
}

#[test]
fn credential_rules_and_domain_separation_still_apply() {
    let identity = vault_identity_generate();
    let id = Uuid::new_v4();
    assert_eq!(header_v2_new(id, &identity, VaultCredentialKind::Pin, "1234".into(), generate_recovery_key()), Err(VaultError::WeakPin));
    assert_eq!(header_v2_new(id, &identity, VaultCredentialKind::Passphrase, "short".into(), generate_recovery_key()), Err(VaultError::WeakPassword));
    assert_eq!(header_v2_new(id, &identity, VaultCredentialKind::Pattern, "0,1,2".into(), generate_recovery_key()), Err(VaultError::WeakPattern));
    // A 12-digit passphrase and the same digits as a PIN are different secrets.
    let header = header_v2_new(id, &identity, VaultCredentialKind::Pin, "123456789012".into(), generate_recovery_key()).unwrap();
    assert!(header_v2_opens(&header, "123456789012".into(), VaultCredentialKind::Pin));
    assert!(!header_v2_opens(&header, "123456789012".into(), VaultCredentialKind::Passphrase));
    let mut forged = header.clone();
    forged.credential_kind = VaultCredentialKind::Passphrase;
    assert!(!header_v2_opens(&forged, "123456789012".into(), VaultCredentialKind::Passphrase), "material keeps the kind even if the header lies");
}

#[test]
fn modified_and_truncated_ciphertext_are_damaged() {
    let mut vault = create("tamper", VaultCredentialKind::Passphrase, SECRET);
    let data = sample(200_000);
    let item = vault.import("photo.jpg", &data);
    let blob = vault.dir.at(&blob_name_v2(item.id));
    let original = fs::read(&blob).unwrap();
    for change in 0..4 {
        let mut bad = original.clone();
        match change {
            0 => bad[original.len() / 2] ^= 1,
            1 => bad.truncate(original.len() - 1),
            2 => bad.truncate(original.len() - 65_536 - 16),
            _ => bad.extend_from_slice(b"trailing"),
        }
        fs::write(&blob, &bad).unwrap();
        assert_eq!(vault.export(&item), Err(VaultError::Damaged), "change {change}");
        assert!(fs::read_dir(&vault.dir.0).unwrap().all(|e| !e.unwrap().file_name().to_string_lossy().starts_with("export-")), "no partial plaintext left");
    }
    fs::write(&blob, &original).unwrap();
    // A blob from another item (same vault) fails its digest.
    let other = vault.import("other.jpg", &sample(10));
    fs::copy(vault.dir.at(&blob_name_v2(other.id)), &blob).unwrap();
    assert_eq!(vault.export(&item), Err(VaultError::Damaged));
    // A modified index.
    let mut index = fs::read(vault.dir.at("index.age")).unwrap();
    let last = index.len() - 1;
    index[last] ^= 0x80;
    fs::write(vault.dir.at("index.age"), index).unwrap();
    assert_eq!(vault.reopen(SECRET, VaultCredentialKind::Passphrase, false), Err(VaultError::Damaged));
}

#[test]
fn encryption_is_randomized() {
    let vault = create("random", VaultCredentialKind::Passphrase, SECRET);
    let a = data_encrypt(vault.header.recipient.clone(), b"same".to_vec(), vault.dir.s("a")).unwrap();
    let b = data_encrypt(vault.header.recipient.clone(), b"same".to_vec(), vault.dir.s("b")).unwrap();
    assert_eq!(a, b);
    assert_ne!(fs::read(vault.dir.at("a")).unwrap(), fs::read(vault.dir.at("b")).unwrap());
    let other = create("random2", VaultCredentialKind::Passphrase, SECRET);
    assert_ne!(vault.header.recipient, other.header.recipient);
    assert_ne!(vault.header.password, other.header.password, "fresh scrypt salt per envelope");
    assert_ne!(index_seal(&vault.library, vault.header.recipient.clone()).unwrap(), index_seal(&vault.library, vault.header.recipient.clone()).unwrap());
    assert_ne!(generate_recovery_key(), generate_recovery_key());
}

#[test]
fn independent_vaults_and_envelope_swaps() {
    let mut a = create("a", VaultCredentialKind::Passphrase, SECRET);
    let b = create("b", VaultCredentialKind::Passphrase, SECRET);
    let item = a.import("a.txt", b"only in a");
    // B's identity cannot read A's index or originals.
    assert_eq!(index_open(fs::read(a.dir.at("index.age")).unwrap(), &b.identity, a.header.id), Err(VaultError::Damaged));
    let out = a.dir.s("cross");
    assert_eq!(file_decrypt(&b.identity, a.dir.s(&blob_name_v2(item.id)), out, item.bytes, item.digest.clone()), Err(VaultError::Damaged));
    // Same secret, but B's envelope pasted into A's header opens to B's identity: refused.
    let mut swapped = a.header.clone();
    swapped.password = b.header.password.clone();
    assert_eq!(header_v2_open(&swapped, SECRET.into(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::Damaged));
    swapped.id = b.header.id; // even with B's id, the recipient is still A's
    assert_eq!(header_v2_open(&swapped, SECRET.into(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::Damaged));
    // The recovery envelope cannot stand in for the password envelope.
    let mut roles = a.header.clone();
    roles.password = a.header.recovery.clone();
    assert_eq!(header_v2_open(&roles, a.recovery.clone(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::Damaged));
    // B's index under A's header id.
    fs::copy(b.dir.at("index.age"), a.dir.at("index.age")).unwrap();
    assert_eq!(a.reopen(SECRET, VaultCredentialKind::Passphrase, false), Err(VaultError::Damaged));
}

#[test]
fn interrupted_write_leaves_nothing() {
    let vault = create("interrupt", VaultCredentialKind::Passphrase, SECRET);
    // The source vanishes: no destination appears.
    let destination = vault.dir.s("x.age");
    assert_eq!(file_encrypt(vault.header.recipient.clone(), vault.dir.s("missing"), destination.clone()), Err(VaultError::MissingFile));
    assert!(!Path::new(&destination).exists());
    // An existing destination is never overwritten.
    fs::write(&destination, b"keep").unwrap();
    fs::write(vault.dir.at("src"), b"new").unwrap();
    assert!(file_encrypt(vault.header.recipient.clone(), vault.dir.s("src"), destination.clone()).is_err());
    assert_eq!(fs::read(&destination).unwrap(), b"keep");
    // A torn write (the stream never finished) is detected on read.
    let item_id = Uuid::new_v4();
    let full = vault.dir.s(&blob_name_v2(item_id));
    let digest = file_encrypt(vault.header.recipient.clone(), vault.dir.s("src"), full.clone()).unwrap();
    let bytes = fs::read(&full).unwrap();
    fs::write(&full, &bytes[..bytes.len() - 5]).unwrap();
    assert_eq!(file_verify(&vault.identity, full, digest.bytes, digest.digest), Err(VaultError::Damaged));
    // A backup that fails midway leaves no output.
    let mut library = vault.library.clone();
    library.items.push(new_vault_item("ghost".into(), "public.data".into(), 1, "0".repeat(64), None, 0));
    let output = vault.dir.s("backup.vault7");
    assert_eq!(backup_v2_write(vault.dir.s(""), &library, output.clone()), Err(VaultError::MissingFile));
    assert!(!Path::new(&output).exists());
}

#[test]
fn credential_rotation_rewraps_without_touching_content() {
    let mut vault = create("rotate", VaultCredentialKind::Passphrase, SECRET);
    let item = vault.import("keep.bin", &sample(70_000));
    let blob = fs::read(vault.dir.at(&blob_name_v2(item.id))).unwrap();
    let index = fs::read(vault.dir.at("index.age")).unwrap();
    let pattern = "0,5,10,15,20,21,22,23,24";
    let rotated = header_v2_rewrap_password(&vault.header, &vault.identity, VaultCredentialKind::Pattern, pattern.into()).unwrap();
    assert_eq!((rotated.recipient.clone(), rotated.recovery.clone()), (vault.header.recipient.clone(), vault.header.recovery.clone()));
    fs::write(vault.dir.at("header.json"), header_v2_encode(&rotated).unwrap()).unwrap();
    assert_eq!(fs::read(vault.dir.at(&blob_name_v2(item.id))).unwrap(), blob);
    assert_eq!(fs::read(vault.dir.at("index.age")).unwrap(), index);
    assert_eq!(vault.reopen(SECRET, VaultCredentialKind::Passphrase, false), Err(VaultError::InvalidCredential));
    assert_eq!(vault.reopen(pattern, VaultCredentialKind::Pattern, false).unwrap(), vault.library);
    // A new recovery key replaces the old one; the unlock secret still works.
    let key = generate_recovery_key();
    let rewrapped = header_v2_rewrap_recovery(&rotated, &vault.identity, key.clone()).unwrap();
    fs::write(vault.dir.at("header.json"), header_v2_encode(&rewrapped).unwrap()).unwrap();
    assert!(vault.reopen(&key, VaultCredentialKind::Passphrase, true).is_ok());
    assert_eq!(vault.reopen(&vault.recovery, VaultCredentialKind::Passphrase, true), Err(VaultError::InvalidCredential));
    // Another vault's identity cannot rewrap this header.
    let other = create("rotate-other", VaultCredentialKind::Passphrase, SECRET);
    assert_eq!(header_v2_rewrap_password(&rewrapped, &other.identity, VaultCredentialKind::Pin, PIN.into()), Err(VaultError::Damaged));
}

#[test]
fn identity_wipe_and_custody() {
    let vault = create("custody", VaultCredentialKind::Pin, PIN);
    let mut secret = vault.identity.export_for_custody().unwrap();
    assert!(String::from_utf8(secret.clone()).unwrap().starts_with("AGE-SECRET-KEY-1"));
    let restored = vault_identity_from_custody(secret.clone(), &vault.header).unwrap();
    assert_eq!(restored.recipient(), vault.header.recipient);
    let other = create("custody-other", VaultCredentialKind::Pin, "00999999");
    assert_eq!(vault_identity_from_custody(secret.clone(), &other.header).err(), Some(VaultError::InvalidCredential));
    secret.iter_mut().for_each(|b| *b = 0);
    restored.wipe();
    assert!(restored.is_wiped());
    assert_eq!(restored.export_for_custody(), Err(VaultError::Locked));
    assert_eq!(index_open(fs::read(vault.dir.at("index.age")).unwrap(), &restored, vault.header.id), Err(VaultError::Locked));
    // Encryption needs only the recipient, so a commit can finish after the session locked.
    assert!(data_encrypt(vault.header.recipient.clone(), b"late".to_vec(), vault.dir.s("late.age")).is_ok());
    assert!(index_seal(&vault.library, vault.header.recipient.clone()).is_ok());
}

#[test]
fn header_versions_and_limits() {
    let vault = create("header", VaultCredentialKind::Passphrase, SECRET);
    let encoded = header_v2_encode(&vault.header).unwrap();
    assert_eq!(header_format_version(encoded.clone()), Ok(2));
    assert_eq!(header_v2_decode(encoded.clone()).unwrap(), vault.header);
    let v1 = include_bytes!("fixtures/header-swift.json");
    assert_eq!(header_format_version(v1.to_vec()), Ok(1));
    assert_eq!(header_v2_decode(v1.to_vec()), Err(VaultError::OlderBackup));
    assert_eq!(header_v2_decode(br#"{"version":3}"#.to_vec()), Err(VaultError::Unsupported));
    assert_eq!(header_v2_decode(vec![]), Err(VaultError::Damaged));
    assert_eq!(header_v2_decode(vec![b' '; 16_385]), Err(VaultError::Damaged));
    let mut bad = vault.header.clone();
    bad.recipient = "age1notarecipient".into();
    assert_eq!(header_v2_decode(header_v2_encode(&bad).unwrap()), Err(VaultError::Damaged));
    let format = vault_format_v2();
    assert_eq!((format.scrypt_log_n, format.scrypt_max_log_n, format.version), (17, 18, 2));
    assert_eq!(format.backup_magic, "7DVAULT2");
}

#[test]
fn backup_restore_into_clean_storage() {
    let mut vault = create("backup", VaultCredentialKind::Passphrase, SECRET);
    let a = vault.import("a.bin", &sample(1_500_000));
    let b = vault.import("b.txt", b"second");
    let output = vault.dir.s("out.vault7");
    backup_v2_write(vault.dir.s(""), &vault.library, output.clone()).unwrap();
    let data = fs::read(&output).unwrap();
    assert_eq!(&data[..8], b"7DVAULT2");
    assert_eq!(backup_format_version(data[..8].to_vec()), 2);
    // The streamed reader and the in-memory codec agree.
    let entries = backup_v2_container_decode(data.clone()).unwrap();
    assert_eq!(entries.iter().map(|e| e.name.clone()).collect::<Vec<_>>(), backup_v2_entry_names(&vault.library));
    let clean = Dir::new("clean");
    let names = backup_v2_read(output.clone(), clean.s("")).unwrap();
    let header = header_v2_decode(fs::read(clean.at("header.json")).unwrap()).unwrap();
    let identity = header_v2_open(&header, SECRET.into(), VaultCredentialKind::Passphrase, false).unwrap();
    let library = index_open(fs::read(clean.at("index.age")).unwrap(), &identity, header.id).unwrap();
    backup_v2_names_match(names, &library).unwrap();
    for item in [&a, &b] {
        file_verify(&identity, clean.s(&blob_name_v2(item.id)), item.bytes, item.digest.clone()).unwrap();
    }
    assert_eq!(library, vault.library);
    // The recovery key restores too.
    assert!(header_v2_open(&header, vault.recovery.clone(), VaultCredentialKind::Passphrase, true).is_ok());
    // A restore into storage that already holds a file of that name is refused, not overwritten.
    let busy = Dir::new("busy");
    fs::write(busy.at("header.json"), b"existing").unwrap();
    assert!(backup_v2_read(output, busy.s("")).is_err());
    assert_eq!(fs::read(busy.at("header.json")).unwrap(), b"existing");
}

fn frame(magic: &[u8], entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut out = magic.to_vec();
    out.extend((entries.len() as u64).to_be_bytes());
    for (name, data) in entries {
        out.extend((name.len() as u64).to_be_bytes());
        out.extend(name.as_bytes());
        out.extend((data.len() as u64).to_be_bytes());
        out.extend(*data);
    }
    out
}

fn read_both(data: &[u8]) -> (Result<Vec<BackupEntry>, VaultError>, Result<Vec<String>, VaultError>) {
    let dir = Dir::new("frame");
    fs::write(dir.at("in"), data).unwrap();
    let staging = dir.at("staging");
    fs::create_dir(&staging).unwrap();
    let streamed = backup_v2_read(dir.s("in"), staging.to_string_lossy().to_string());
    if streamed.is_err() {
        // Nothing escapes the staging directory.
        assert_eq!(fs::read_dir(&dir.0).unwrap().count(), 2);
    }
    (backup_v2_container_decode(data.to_vec()), streamed)
}

#[test]
fn backup_framing_refuses_traversal_duplicates_trailing_and_old_versions() {
    let id = "6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D";
    let blob = format!("{id}.age");
    let good = frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x"), (&blob, b"y")]);
    let (memory, streamed) = read_both(&good);
    assert!(memory.is_ok() && streamed.is_ok());
    let cases: Vec<(Vec<u8>, VaultError)> = vec![
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("../index.age", b"x")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x"), ("/etc/passwd", b"y")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x"), (&format!("sub/{blob}"), b"y")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x"), (&blob.to_lowercase(), b"y")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x"), ("index.age", b"z")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.sealed", b"x")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), (&format!("{id}.aea"), b"x")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}")]), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", b"{}"), (&blob, b"y")]), VaultError::Damaged),
        ([good.clone(), b"!".to_vec()].concat(), VaultError::Damaged),
        (good[..good.len() - 1].to_vec(), VaultError::Damaged),
        (good[..20].to_vec(), VaultError::Damaged),
        (frame(b"7DVAULT2", &[("header.json", &vec![b' '; 16_385]), ("index.age", b"x")]), VaultError::Damaged),
        (frame(b"7DVAULT1", &[("header.json", b"{}"), ("index.sealed", b"x")]), VaultError::OlderBackup),
        (frame(b"PK\x03\x04xxxx", &[("header.json", b"{}"), ("index.age", b"x")]), VaultError::Unsupported),
    ];
    for (index, (data, want)) in cases.iter().enumerate() {
        let (memory, streamed) = read_both(data);
        assert_eq!(memory.err(), Some(*want), "case {index} (memory)");
        assert_eq!(streamed.err(), Some(*want), "case {index} (streamed)");
    }
    // A payload length past the end of a file is caught before writing it.
    let mut huge = frame(b"7DVAULT2", &[("header.json", b"{}"), ("index.age", b"x")]);
    let at = 8 + 8 + 8 + 11;
    huge[at..at + 8].copy_from_slice(&u64::MAX.to_be_bytes());
    assert_eq!(read_both(&huge).1.err(), Some(VaultError::Damaged));
    // The iPhone's own (v1) reader still refuses a v2 container as unsupported, not damaged.
    assert_eq!(backup_container_decode(good).err(), Some(VaultError::Unsupported));
    assert!(vault_error_description(VaultError::OlderBackup).contains("older format made by Vault on iPhone"));
}

#[test]
fn trash_then_restore_and_permanent_delete() {
    let mut vault = create("trash", VaultCredentialKind::Passphrase, SECRET);
    let item = vault.import("t.bin", b"trash me");
    vault.library = library_update_item(&vault.library, item.id, None, None, Some(true), SystemTime::now()).unwrap();
    vault.save();
    let reopened = vault.reopen(SECRET, VaultCredentialKind::Passphrase, false).unwrap();
    assert!(reopened.items[0].deleted.is_some());
    vault.library = library_update_item(&reopened, item.id, None, None, Some(false), SystemTime::now()).unwrap();
    vault.save();
    let reopened = vault.reopen(SECRET, VaultCredentialKind::Passphrase, false).unwrap();
    assert!(reopened.items[0].deleted.is_none());
    assert_eq!(vault.export(&reopened.items[0]).unwrap(), b"trash me");
    assert_eq!(library_permanently_delete(&reopened, vec![item.id]), Err(VaultError::Damaged), "only from the trash");
}

#[test]
fn revision_follows_header_and_index() {
    let mut vault = create("revision", VaultCredentialKind::Passphrase, SECRET);
    let first = vault_revision_v2(vault.dir.s("")).unwrap();
    assert_eq!(first.len(), 64);
    assert_eq!(vault_revision_v2(vault.dir.s("")).unwrap(), first);
    vault.import("r.bin", b"r");
    assert_ne!(vault_revision_v2(vault.dir.s("")).unwrap(), first);
}

// MARK: the reference implementation's test vectors (C2SP CCTV, made by Go age)

/// A CCTV vector: its `key: value` header lines and the age file after the blank line.
fn vector(bytes: &[u8]) -> (std::collections::HashMap<String, String>, Vec<u8>) {
    let split = bytes.windows(2).position(|w| w == b"\n\n").unwrap();
    let head = std::str::from_utf8(&bytes[..split]).unwrap();
    let fields = head.lines().filter_map(|l| l.split_once(": ")).map(|(k, v)| (k.to_string(), v.to_string())).collect();
    (fields, bytes[split + 2..].to_vec())
}

#[test]
fn decrypts_a_file_made_by_the_reference_age_implementation() {
    let (fields, file) = vector(include_bytes!("fixtures/age/x25519"));
    let secret = &fields["identity"];
    let public = age::x25519::Identity::from_str(secret).unwrap().to_public().to_string();
    let header = VaultHeaderV2 { version: 2, id: Uuid::new_v4(), recipient: public, credential_kind: VaultCredentialKind::Passphrase, password: vec![], recovery: vec![] };
    let identity = vault_identity_from_custody(secret.as_bytes().to_vec(), &header).unwrap();
    let dir = Dir::new("cctv");
    fs::write(dir.at("file.age"), &file).unwrap();
    let length = age::decrypt(&age::x25519::Identity::from_str(secret).unwrap(), &file).unwrap().len() as i64;
    // The vector's payload hash is the reference's; ours must match it.
    file_verify(&identity, dir.s("file.age"), length, fields["payload"].clone()).unwrap();
    let out = dir.s("plain");
    file_decrypt(&identity, dir.s("file.age"), out.clone(), length, fields["payload"].clone()).unwrap();
    // And the reference decrypts what we write: the same crate's reader stands in for the CLI,
    // with the identity in its standard text form.
    let digest = data_encrypt(header.recipient.clone(), b"to the reference".to_vec(), dir.s("ours.age")).unwrap();
    let theirs = age::decrypt(&age::x25519::Identity::from_str(secret).unwrap(), &fs::read(dir.at("ours.age")).unwrap()).unwrap();
    assert_eq!(theirs, b"to the reference");
    assert_eq!(digest.bytes, 16);
    assert!(fs::read(dir.at("ours.age")).unwrap().starts_with(b"age-encryption.org/v1\n-> X25519 "));
}

#[test]
fn reference_scrypt_files_reach_the_envelope_checks() {
    let id = Uuid::new_v4();
    let recipient = vault_identity_generate().recipient();
    let header = |file: Vec<u8>| VaultHeaderV2 { version: 2, id, recipient: recipient.clone(), credential_kind: VaultCredentialKind::Passphrase, password: file, recovery: vec![] };
    // log N = 10 with passphrase "password": it decrypts (so the scrypt stanza is real age), but
    // its plaintext is not a vault envelope.
    let (fields, file) = vector(include_bytes!("fixtures/age/scrypt"));
    assert_eq!(fields["passphrase"], "password");
    assert_eq!(header_v2_open(&header(file.clone()), "password".into(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::Damaged));
    assert_eq!(header_v2_open(&header(file), "wrong".into(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::InvalidCredential));
    // log N = 23 (1 GiB) is over the cap: refused before any work.
    let (_, file) = vector(include_bytes!("fixtures/age/scrypt_work_factor_23"));
    let started = std::time::Instant::now();
    assert_eq!(header_v2_open(&header(file), "password".into(), VaultCredentialKind::Passphrase, false).err(), Some(VaultError::Unsupported));
    assert!(started.elapsed().as_secs() < 2);
}
