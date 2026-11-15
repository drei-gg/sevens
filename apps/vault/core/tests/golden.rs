//! Files and behaviour from Swift's real code, built in `swift:6.3` from copies of the app's
//! sources (see the README). The app's index and header use a plain `JSONEncoder()`, whose key
//! order is not fixed on swift-foundation, so those cannot be byte for byte: Rust must read them
//! back equal and re-encode the same JSON value, and Swift must read Rust's files (`*-rust.json`,
//! checked by the same Swift program). The `.vault7` framing is deterministic, so it must match
//! byte for byte.

use serde_json::Value;
use std::time::{Duration, UNIX_EPOCH};
use vault_core::*;

fn json(bytes: &[u8]) -> Value {
    serde_json::from_slice(bytes).unwrap()
}

fn library_id() -> Uuid {
    "6F1D3C2A-8B4E-4F7A-9C1D-2E3F4A5B6C7D".parse().unwrap()
}

#[test]
fn swift_written_library_reads_back_equal() {
    let swift = include_bytes!("fixtures/library-swift.json");
    let library = library_decode(swift.to_vec(), library_id()).unwrap();
    assert_eq!(library.name, "Travel & receipts");
    assert_eq!(library.items.len(), 3);
    assert_eq!(library.items[1].bytes, 3_000_000_000);
    assert_eq!(library.items[1].name, "Passport 🛂.png");
    assert!(library.items[0].favorite && library.items[0].deleted.is_none());
    // Date(timeIntervalSinceReferenceDate: 784000000) and -12.25.
    assert_eq!(library.items[1].created, UNIX_EPOCH + Duration::from_secs(978_307_200 + 784_000_000));
    assert_eq!(library.items[2].created, UNIX_EPOCH + Duration::from_millis(978_307_200_000 - 12_250));
    assert_eq!(library.folders[1].parent, Some(library.folders[0].id));
    assert_eq!((library.sort, library.grid), (VaultOrder::Largest, true));
    let ours = library_encode(&library).unwrap();
    assert_eq!(json(&ours), json(swift), "same JSON value, every Double unchanged");
    assert_eq!(library_decode(ours, library_id()).unwrap(), library);
}

#[test]
fn swift_written_empty_library_reads_back_equal() {
    let swift = include_bytes!("fixtures/library-empty-swift.json");
    let library = library_decode(swift.to_vec(), "00000000-0000-4000-8000-000000000001".parse().unwrap()).unwrap();
    assert_eq!((library.items.len(), library.backup_prepared, library.sort), (0, None, VaultOrder::Newest));
    assert_eq!(json(&library_encode(&library).unwrap()), json(swift));
}

#[test]
fn swift_written_headers_read_back_equal() {
    let swift = include_bytes!("fixtures/header-swift.json");
    let header = header_decode(swift.to_vec()).unwrap();
    assert_eq!(header.id, library_id());
    assert_eq!(header.credential_kind, Some(VaultCredentialKind::Pin));
    assert_eq!(header.password.salt, (0..32).collect::<Vec<u8>>());
    assert_eq!(header.password.sealed, (0..60).map(|i| 255 - i).collect::<Vec<u8>>());
    assert_eq!(header.recovery.rounds, 600_000);
    // Swift escapes "/" in strings; the JSON value is the same.
    assert_eq!(json(&header_encode(&header).unwrap()), json(swift));

    let legacy_swift = include_bytes!("fixtures/header-legacy-swift.json");
    let legacy = header_decode(legacy_swift.to_vec()).unwrap();
    assert_eq!(legacy.credential_kind, None);
    assert_eq!(header_credential_kind(&legacy), VaultCredentialKind::Passphrase);
    assert_eq!(VaultHeader { credential_kind: Some(VaultCredentialKind::Pin), ..legacy.clone() }, header);
    assert_eq!(json(&header_encode(&legacy).unwrap()), json(legacy_swift));
}

#[test]
fn swift_written_container_round_trips_byte_for_byte() {
    let swift = include_bytes!("fixtures/container-swift.vault7");
    let entries = backup_container_decode(swift.to_vec()).unwrap();
    let names: Vec<&str> = entries.iter().map(|e| e.name.as_str()).collect();
    assert_eq!(names, ["header.json", "index.sealed", "0A0B0C0D-0E0F-4A1B-8C2D-3E4F5A6B7C8D.aea", "12345678-9ABC-4DEF-8123-456789ABCDEF.aea"]);
    assert_eq!(entries[0].data, include_bytes!("fixtures/header-swift.json"));
    assert_eq!(entries[2].data, b"synthetic ciphertext");
    assert!(entries[3].data.is_empty());
    assert_eq!(backup_container_encode(entries).unwrap(), swift.to_vec());
}

/// What Rust writes for the Swift fixtures' values. The Swift program decodes these files and
/// compares them with its own values (all equal when the fixtures were made). Regenerate with
/// `VAULT_WRITE_FIXTURES=1 cargo test --test golden`, then rerun the Swift program.
#[test]
fn rust_written_files_are_the_ones_swift_read() {
    let library = library_decode(include_bytes!("fixtures/library-swift.json").to_vec(), library_id()).unwrap();
    let empty = library_decode(include_bytes!("fixtures/library-empty-swift.json").to_vec(), "00000000-0000-4000-8000-000000000001".parse().unwrap()).unwrap();
    let header = header_decode(include_bytes!("fixtures/header-swift.json").to_vec()).unwrap();
    let legacy = header_decode(include_bytes!("fixtures/header-legacy-swift.json").to_vec()).unwrap();
    let container = backup_container_decode(include_bytes!("fixtures/container-swift.vault7").to_vec()).unwrap();
    let files = [
        ("library-rust.json", library_encode(&library).unwrap()),
        ("library-empty-rust.json", library_encode(&empty).unwrap()),
        ("header-rust.json", header_encode(&header).unwrap()),
        ("header-legacy-rust.json", header_encode(&legacy).unwrap()),
        ("container-rust.vault7", backup_container_encode(container).unwrap()),
    ];
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/rust");
    for (name, bytes) in files {
        if std::env::var_os("VAULT_WRITE_FIXTURES").is_some() {
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(dir.join(name), &bytes).unwrap();
        }
        assert_eq!(std::fs::read(dir.join(name)).unwrap(), bytes, "{name}");
    }
}

// MARK: Behaviour table (Swift's answers for the same inputs)

fn table() -> Value {
    json(include_bytes!("fixtures/behaviour-swift.json"))
}

fn outcome(result: Result<(), VaultError>) -> &'static str {
    match result {
        Ok(()) => "ok",
        Err(VaultError::WeakPin) => "weakPIN",
        Err(VaultError::WeakPattern) => "weakPattern",
        Err(VaultError::WeakPassword) => "weakPassword",
        Err(VaultError::InvalidName) => "invalidName",
        Err(VaultError::Damaged) => "damaged",
        Err(VaultError::Unsupported) => "unsupported",
        Err(_) => "other",
    }
}

fn kind(raw: &str) -> VaultCredentialKind {
    serde_json::from_value(Value::String(raw.into())).unwrap()
}

#[test]
fn credentials_validate_and_derive_as_swift() {
    for case in table()["credentials"].as_array().unwrap() {
        let (k, value) = (kind(case["kind"].as_str().unwrap()), case["value"].as_str().unwrap());
        assert_eq!(outcome(k.validate(value)), case["result"], "{k:?} {value:?}");
        assert_eq!(k.material(value), case["material"].as_str().unwrap());
        assert_eq!(k.title(), case["title"].as_str().unwrap());
    }
}

#[test]
fn gestures_canonicalize_as_swift() {
    for case in table()["gestures"].as_array().unwrap() {
        let mut path = vec![];
        for dot in case["samples"].as_array().unwrap() {
            pattern_push(dot.as_i64().unwrap() as i32, &mut path);
        }
        let expected: Vec<i32> = case["path"].as_array().unwrap().iter().map(|d| d.as_i64().unwrap() as i32).collect();
        assert_eq!(path, expected, "{}", case["samples"]);
    }
}

#[test]
fn names_and_digests_validate_as_swift() {
    for case in table()["names"].as_array().unwrap() {
        let name = case["name"].as_str().unwrap();
        assert_eq!(outcome(VaultLibrary::validate_name(name)), case["result"], "{name:?}");
    }
    for case in table()["digests"].as_array().unwrap() {
        let mut library = VaultLibrary::new("D".into());
        library.items = vec![VaultItem::new("x".into(), "public.data".into(), 1, case["digest"].as_str().unwrap().into(), None, 0)];
        assert_eq!(outcome(library.validate()), case["result"], "{}", case["digest"]);
    }
}

#[test]
fn search_matches_as_swift() {
    for case in table()["searches"].as_array().unwrap() {
        let item = VaultItem::new(case["name"].as_str().unwrap().into(), "t".into(), 0, String::new(), None, 0);
        assert_eq!(item.matches(case["query"].as_str().unwrap()), case["matches"].as_bool().unwrap(), "{case}");
    }
}

#[test]
fn name_order_matches_swift() {
    let t = table();
    for case in t["compares"].as_array().unwrap() {
        let got = standard_compare(case["a"].as_str().unwrap().into(), case["b"].as_str().unwrap().into());
        assert_eq!(got as i64, case["result"].as_i64().unwrap(), "{case}");
    }
    let expected: Vec<&str> = t["names_sorted"].as_array().unwrap().iter().map(|v| v.as_str().unwrap()).collect();
    let mut shuffled = expected.clone();
    shuffled.reverse();
    shuffled.rotate_left(7);
    let items: Vec<VaultItem> = shuffled.iter().map(|n| VaultItem::new(n.to_string(), "t".into(), 0, String::new(), None, 0)).collect();
    let sorted: Vec<String> = VaultOrder::Name.sorted(&items).into_iter().map(|i| i.name).collect();
    assert_eq!(sorted, expected);
}
