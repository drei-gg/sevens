//! Every VaultCore test from the iOS app (Kit/Tests/VaultCoreTests), ported one to one, so the
//! crate is held to the behaviour the Swift implementation has. Names match the Swift test
//! functions.
//!
//! VaultStorageTests mostly exercise encryption and files, which stay native. Where one of them
//! also checks a pure rule this crate owns (framing, credential routing, library edits), that part
//! is ported below under the same name; the storage half stays with the platform suites.

use std::time::{Duration, UNIX_EPOCH};
use vault_core::*;

fn hex(c: char) -> String {
    std::iter::repeat_n(c, 64).collect()
}

// MARK: CredentialTests.swift

#[test]
fn pin_validation_preserves_leading_zeros_and_rejects_non_ascii_digits() {
    VaultCredentialKind::Pin.validate("00123456").unwrap();
    for invalid in ["1234567", "１２３４５６７８", "1234567a", "1234 5678"] {
        assert!(VaultCredentialKind::Pin.validate(invalid).is_err(), "{invalid}");
    }
    assert_ne!(VaultCredentialKind::Pin.material("00123456"), VaultCredentialKind::Passphrase.material("00123456"));
}

#[test]
fn pattern_canonicalization_is_independent_of_skipped_gesture_samples() {
    let mut sparse = vec![];
    for dot in [0, 4, 24] {
        pattern_push(dot, &mut sparse);
    }
    assert_eq!(sparse, vec![0, 1, 2, 3, 4, 9, 14, 19, 24]);
    assert_eq!(pattern_decode(pattern_encode(sparse.clone())), Some(sparse.clone()));
    assert_eq!(pattern_decode("0,4,24".into()), None);
    assert_eq!(pattern_decode("0,1,1".into()), None);
    assert_eq!(pattern_decode("00,1".into()), None);
    assert_eq!(pattern_decode("0,25".into()), None);
    VaultCredentialKind::Pattern.validate(&pattern_encode(sparse)).unwrap();
    assert_eq!(VaultCredentialKind::Pattern.validate("0,1,2"), Err(VaultError::WeakPattern));
}

// MARK: LibraryTests.swift

#[test]
fn rejects_unsafe_names() {
    for name in ["", " ", "..", "../file", "a/b", "a\\b", "bad\0file", &"a".repeat(201)] {
        assert!(VaultLibrary::validate_name(name).is_err(), "{name:?}");
    }
    VaultLibrary::validate_name("Travel & receipts.pdf").unwrap();
}

#[test]
fn rejects_folder_cycles_and_dangling_parents() {
    let mut value = VaultLibrary::new("Private".into());
    let (first, second) = (Uuid::new_v4(), Uuid::new_v4());
    value.folders = vec![
        VaultFolder { id: first, name: "A".into(), parent: Some(second) },
        VaultFolder { id: second, name: "B".into(), parent: Some(first) },
    ];
    assert!(value.validate().is_err());
    value.folders = vec![new_vault_folder("Missing".into(), Some(Uuid::new_v4()))];
    assert!(value.validate().is_err());
}

#[test]
fn sort_search_and_roundtrip() {
    let mut value = VaultLibrary::new("Travel".into());
    let a = VaultItem::new("Boarding pass.pdf".into(), "com.adobe.pdf".into(), 900, hex('a'), None, 1);
    let b = VaultItem::new("Passport.png".into(), "public.png".into(), 300, hex('b'), None, 0);
    value.items = vec![a.clone(), b.clone()];
    value.sort = VaultOrder::Largest;
    value.grid = true;
    value.validate().unwrap();
    assert_eq!(VaultOrder::Largest.sorted(&value.items).first(), Some(&a));
    assert_eq!(VaultOrder::Manual.sorted(&value.items).first(), Some(&b));
    assert!(a.matches("BOARDING"));
    assert_eq!(library_decode(library_encode(&value).unwrap(), value.id).unwrap(), value);
}

// MARK: RepositoryTests.swift (the pure parts)

fn envelope(byte: u8) -> KeyEnvelope {
    KeyEnvelope { salt: vec![byte; 32], rounds: 600_000, sealed: vec![byte; 60] }
}

#[test]
fn malformed_backups_preserve_existing_state() {
    let header = header_encode(&new_vault_header(Uuid::new_v4(), envelope(1), envelope(2), Some(VaultCredentialKind::Passphrase))).unwrap();
    let bytes = backup_container_encode(vec![
        BackupEntry { name: "header.json".into(), data: header },
        BackupEntry { name: "index.sealed".into(), data: vec![9; 40] },
    ])
    .unwrap();
    backup_container_decode(bytes.clone()).unwrap();
    let mut trailing = bytes.clone();
    trailing.push(1);
    let mut huge = b"7DVAULT1".to_vec();
    huge.extend([255; 8]);
    for malformed in [bytes[..bytes.len() - 1].to_vec(), trailing, huge] {
        assert!(backup_container_decode(malformed).is_err());
    }
    assert!(!backup_name_valid("../header.json".into()));
    assert!(!backup_name_valid("header.json/extra".into()));
}

#[test]
fn backup_restore_trash_and_no_overwrite() {
    let now = UNIX_EPOCH + Duration::from_secs(1_900_000_000);
    let mut library = VaultLibrary::new("Travel".into());
    let item = library_import_item(&library, "ticket.txt".into(), "public.plain-text".into(), 17, hex('c'), None);
    library = library_append_item(&library, item.clone());
    library = library_update_item(&library, item.id, None, Some(true), Some(true), now).unwrap();
    assert_eq!(library.items[0].deleted, Some(now));
    assert!(library.items[0].favorite);
    backup_names_match(backup_entry_names(&library), &library).unwrap();
    library = library_update_item(&library, item.id, None, None, Some(false), now).unwrap();
    assert_eq!(library.items[0].deleted, None);
}

#[test]
fn note_edits_and_recovery_replacement_survive_restart() {
    let mut library = library_add_folder(&VaultLibrary::new("Notes".into()), "Work".into(), None).unwrap();
    let folder = library.folders[0].id;
    let name = note_name("Plan".into(), "original".into()).unwrap();
    assert_eq!(name, "Plan.txt");
    library_check_import(&library, name.clone(), Some(folder)).unwrap();
    let old = library_import_item(&library, name, NOTE_TYPE.into(), 8, hex('d'), Some(folder));
    library = library_append_item(&library, old.clone());
    library = library_update_item(&library, old.id, None, Some(true), None, UNIX_EPOCH).unwrap();
    let current = library_check_note_edit(&library, old.id, "updated without data loss".into()).unwrap();
    let replacement = note_replacement(&current, 25, hex('e'));
    library = library_replace_item(&library, old.id, replacement.clone());
    library_encode(&library).unwrap();
    let item = &library.items[0];
    assert_ne!(item.id, old.id);
    assert!(item.favorite);
    assert_eq!(item.folder, Some(folder));
    assert_eq!(item.created, old.created);
    library_check_note_read(&library, item.id).unwrap();
}

#[test]
fn pin_and_pattern_route_independently_and_round_trip_through_backup() {
    let pattern = "0,1,2,3,4,9,14,19,24";
    let pin = new_vault_header(Uuid::new_v4(), envelope(1), envelope(2), Some(VaultCredentialKind::Pin));
    let drawn = new_vault_header(Uuid::new_v4(), envelope(3), envelope(4), Some(VaultCredentialKind::Pattern));
    VaultCredentialKind::Pin.validate("00123456").unwrap();
    VaultCredentialKind::Pattern.validate(pattern).unwrap();
    let route = unlock_route(&pin, "00123456".into(), VaultCredentialKind::Pin, false).unwrap();
    assert_eq!(route.material, "Vault/PIN/v1/00123456");
    assert_eq!(route.context, password_context(pin.id));
    assert_eq!(route.envelope, pin.password);
    assert_eq!(unlock_route(&pin, pattern.into(), VaultCredentialKind::Pattern, false), None);
    assert_eq!(unlock_route(&drawn, pattern.into(), VaultCredentialKind::Passphrase, false), None);
    assert_eq!(unlock_route(&drawn, pattern.into(), VaultCredentialKind::Pattern, false).unwrap().material, format!("Vault/Pattern/5/v1/{pattern}"));
    let recovery = unlock_route(&drawn, "key".into(), VaultCredentialKind::Pin, true).unwrap();
    assert_eq!((recovery.envelope, recovery.material, recovery.context), (drawn.recovery.clone(), "key".to_string(), recovery_context(drawn.id)));
    let restored = header_decode(header_encode(&drawn).unwrap()).unwrap();
    assert_eq!(restored, drawn);
}

#[test]
fn original_header_without_credential_type_still_unlocks() {
    let header = new_vault_header(Uuid::new_v4(), envelope(5), envelope(6), Some(VaultCredentialKind::Passphrase));
    let mut json: serde_json::Value = serde_json::from_slice(&header_encode(&header).unwrap()).unwrap();
    json.as_object_mut().unwrap().remove("credentialKind");
    let legacy = header_decode(serde_json::to_vec(&json).unwrap()).unwrap();
    assert_eq!(legacy.credential_kind, None);
    assert_eq!(header_credential_kind(&legacy), VaultCredentialKind::Passphrase);
    assert!(unlock_route(&legacy, "four separate private words".into(), VaultCredentialKind::Passphrase, false).is_some());
    assert!(unlock_route(&legacy, "anything".into(), VaultCredentialKind::Passphrase, true).is_some());
}

// MARK: Rules from the Swift sources without a Swift test

#[test]
fn library_validation_runs_in_swift_order() {
    let mut value = VaultLibrary::new("ok".into());
    value.version = 2;
    value.name = "a/b".into();
    assert_eq!(value.validate(), Err(VaultError::Unsupported));
    value.version = 1;
    assert_eq!(value.validate(), Err(VaultError::InvalidName));
    value.name = "ok".into();
    let folder = new_vault_folder("F".into(), None);
    value.folders = vec![folder.clone(), folder.clone()];
    assert_eq!(value.validate(), Err(VaultError::Damaged));
    value.folders = vec![VaultFolder { parent: Some(folder.id), ..folder.clone() }];
    assert_eq!(value.validate(), Err(VaultError::Damaged), "a folder that is its own parent");
    value.folders = vec![folder.clone()];
    let mut item = VaultItem::new("x".into(), "public.data".into(), -1, hex('a'), None, 0);
    value.items = vec![item.clone()];
    assert_eq!(value.validate(), Err(VaultError::Damaged));
    item.bytes = 1;
    item.folder = Some(Uuid::new_v4());
    value.items = vec![item.clone()];
    assert_eq!(value.validate(), Err(VaultError::Damaged));
    item.folder = Some(folder.id);
    item.name = "..".into();
    value.items = vec![item.clone()];
    assert_eq!(value.validate(), Err(VaultError::InvalidName));
    item.name = "x".into();
    value.items = vec![item];
    value.validate().unwrap();
}

#[test]
fn repository_edits_keep_their_checks() {
    let now = UNIX_EPOCH;
    let library = VaultLibrary::new("L".into());
    let a = library_import_item(&library, "a".into(), "public.data".into(), 1, hex('a'), None);
    assert_eq!(a.order, 0);
    let library = library_append_item(&library, a.clone());
    let b = library_import_item(&library, "b".into(), "public.data".into(), 1, hex('b'), None);
    assert_eq!(b.order, 1);
    let library = library_append_item(&library, b.clone());
    assert_eq!(library_check_import(&library, "x".into(), Some(Uuid::new_v4())), Err(VaultError::Damaged));
    assert_eq!(library_check_import(&library, "a/b".into(), Some(Uuid::new_v4())), Err(VaultError::InvalidName));
    assert_eq!(library_update_item(&library, Uuid::new_v4(), None, None, None, now), Err(VaultError::MissingFile));
    assert_eq!(library_move(&library, vec![a.id], Some(Uuid::new_v4())), Err(VaultError::Damaged));
    assert_eq!(library_rename_folder(&library, Uuid::new_v4(), "a/b".into()), Err(VaultError::InvalidName));
    assert_eq!(library_rename_folder(&library, Uuid::new_v4(), "ok".into()), Err(VaultError::MissingFile));
    assert_eq!(library_reorder(&library, vec![a.id, a.id]), Err(VaultError::Damaged));
    assert_eq!(library_reorder(&library, vec![Uuid::new_v4()]), Err(VaultError::Damaged));
    let reordered = library_reorder(&library, vec![b.id, a.id]).unwrap();
    assert_eq!(reordered.sort, VaultOrder::Manual);
    assert_eq!(VaultOrder::Manual.sorted(&reordered.items)[0].id, b.id);
    assert_eq!(library_permanently_delete(&library, vec![a.id]), Err(VaultError::Damaged));
    let trashed = library_update_item(&library, a.id, None, None, Some(true), now).unwrap();
    assert_eq!(library_permanently_delete(&trashed, vec![a.id]).unwrap().items, vec![b.clone()]);
    let dangling = library_add_folder(&library, "Sub".into(), Some(Uuid::new_v4())).unwrap();
    assert_eq!(library_encode(&dangling), Err(VaultError::Damaged));
    assert_eq!(note_name("n".into(), "x".repeat(5_000_001)), Err(VaultError::NoSpace));
    assert_eq!(note_name("a.txt".into(), String::new()).unwrap(), "a.txt");
    assert_eq!(library_check_note_edit(&library, a.id, String::new()), Err(VaultError::Unsupported));
    let moved = library_move(&library_add_folder(&library, "F".into(), None).unwrap(), vec![a.id], None).unwrap();
    assert_eq!(moved.items[0].folder, None);
    let prefs = library_set_preferences(&library, VaultOrder::Name, true);
    assert_eq!((prefs.sort, prefs.grid), (VaultOrder::Name, true));
    assert_eq!(library_mark_backup_prepared(&library, now).backup_prepared, Some(now));
}

#[test]
fn format_strings_and_limits() {
    let id: Uuid = "6f1d3c2a-8b4e-4f7a-9c1d-2e3f4a5b6c7d".parse().unwrap();
    assert_eq!(password_context(id), "Vault/password/v1/6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D");
    assert_eq!(recovery_context(id), "Vault/recovery/v1/6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D");
    assert_eq!(index_context(id), "Vault/index/v1/6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D");
    assert_eq!(file_key_salt(id), b"6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D".to_vec());
    assert_eq!(blob_name(id), "6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D.aea");
    assert_eq!(backup_file_name(id), "Vault-6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D.vault7");
    assert!(backup_name_valid(blob_name(id)));
    assert!(!backup_name_valid("6f1d3c2a-8b4e-4f7a-9c1d-2e3f4a5b6c7d.aea".into()));
    assert!(!backup_name_valid("6F1D3C2A8B4E4F7A9C1D2E3F4A5B6C7D.aea".into()));
    let format = vault_format();
    assert_eq!((format.pbkdf2_rounds, format.file_key_info.as_str()), (600_000, "Vault/file/v1"));
    key_derivation_check(vec![0; 32], 600_000, "x".repeat(4096)).unwrap();
    for (salt, rounds, credential) in [(31, 600_000, 1), (32, 599_999, 1), (32, 600_000, 4097)] {
        assert_eq!(key_derivation_check(vec![0; salt], rounds, "x".repeat(credential)), Err(VaultError::Unsupported));
    }
    assert_eq!(header_decode(vec![]), Err(VaultError::Damaged));
    assert_eq!(header_decode(vec![b' '; 16_385]), Err(VaultError::Damaged));
    let mut future = serde_json::to_value(new_vault_header(id, envelope(1), envelope(2), None)).unwrap();
    future["version"] = 2.into();
    assert_eq!(header_decode(serde_json::to_vec(&future).unwrap()), Err(VaultError::Unsupported));
    let library = VaultLibrary::with_id(id, "L".into());
    assert_eq!(library_decode(library_encode(&library).unwrap(), Uuid::new_v4()), Err(VaultError::Damaged));
    assert_eq!(vault_error_description(VaultError::WeakPin), "Use 8–32 digits. Avoid dates and repeated sequences.");
}

#[test]
fn container_reader_checks_in_swift_order() {
    let entry = |name: &str, data: Vec<u8>| BackupEntry { name: name.into(), data };
    let good = backup_container_encode(vec![entry("header.json", vec![1]), entry("index.sealed", vec![2])]).unwrap();
    assert_eq!(backup_container_decode(b"7DVAU".to_vec()), Err(VaultError::Damaged), "short magic is truncation");
    let mut wrong = good.clone();
    wrong[7] = b'2';
    assert_eq!(backup_container_decode(wrong), Err(VaultError::Unsupported));
    let one = [b"7DVAULT1".as_slice(), &1u64.to_be_bytes()].concat();
    assert_eq!(backup_container_decode(one), Err(VaultError::Damaged));
    assert_eq!(backup_container_encode(vec![entry("header.json", vec![]), entry("header.json", vec![])]), Err(VaultError::Damaged));
    assert_eq!(backup_container_encode(vec![entry("../x", vec![])]), Err(VaultError::Damaged));
    let only_index = backup_container_encode(vec![entry("index.sealed", vec![]), entry(&blob_name(Uuid::new_v4()), vec![])]).unwrap();
    assert_eq!(backup_container_decode(only_index), Err(VaultError::Damaged), "no header");
    let big_header = backup_container_encode(vec![entry("header.json", vec![0; 16_385]), entry("index.sealed", vec![])]).unwrap();
    assert_eq!(backup_container_decode(big_header), Err(VaultError::Damaged));
    let decoded = backup_container_decode(good).unwrap();
    assert_eq!(decoded, vec![entry("header.json", vec![1]), entry("index.sealed", vec![2])]);
}
