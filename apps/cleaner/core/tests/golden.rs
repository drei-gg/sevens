//! Files written by Swift's real encoders, and files Rust writes for Swift to read.
//!
//! The app encodes with a plain `JSONEncoder()` (no `.sortedKeys`), so Swift's key order is
//! hash-random and byte identity is impossible. These tests assert instead that Rust reads each
//! Swift file back equal to the same sample values Swift encoded, and re-encodes the same JSON
//! value (key order, set order and number spelling aside). The `rust-*.json` fixtures are what
//! Rust writes today; Swift 6.3 decoded each of them back equal to its own samples (see the
//! README), and the `rust_writes_*` tests keep Rust's output identical to them.
//!
//! The fixtures came from `swiftc` in `docker.io/library/swift:6.3`, encoding the samples below
//! with CleanerCore's types as of the 2026-11-15 snapshot.

use cleaner_core::*;
use serde_json::Value;
use std::collections::HashMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// `Date(timeIntervalSinceReferenceDate:)`.
fn reference(seconds: f64) -> SystemTime {
    let base = UNIX_EPOCH + Duration::from_secs(978_307_200);
    if seconds >= 0.0 {
        base + Duration::from_secs_f64(seconds)
    } else {
        base - Duration::from_secs_f64(-seconds)
    }
}
fn strings(values: &[&str]) -> Vec<String> {
    values.iter().map(|s| s.to_string()).collect()
}

const FINGERPRINT: &str = "1:public.heic:abc|2:com.apple.quicktime-movie:def";

fn sample_items() -> Vec<CleanupItem> {
    vec![
        CleanupItem {
            revision: "784891800.25:4032x3024:2".into(),
            bytes: Some(2_345_678),
            date: Some(reference(784_891_800.25)),
            width: 4032,
            height: 3024,
            favorite: true,
            tags: strings(&["HDR", "Live Photos", "Screenshots"]),
            fingerprint: Some(FINGERPRINT.into()),
            ..new_cleanup_item(
                "A1B2/L0/001".into(),
                "IMG_0001.HEIC".into(),
                MediaKind::Photo,
            )
        },
        CleanupItem {
            revision: "0.0:-1".into(),
            ..new_cleanup_item(
                "file:///Docs/report%20final.pdf".into(),
                "Docs/report final.pdf".into(),
                MediaKind::File,
            )
        },
        CleanupItem {
            revision: "1.5:1920x1080:1".into(),
            bytes: Some(0),
            date: Some(reference(-12.125)),
            width: 1920,
            height: 1080,
            duration: 12.5,
            fingerprint: Some(FINGERPRINT.into()),
            ..new_cleanup_item("C3/L0/001".into(), "Clip.MOV".into(), MediaKind::Video)
        },
        CleanupItem {
            bytes: Some(9_007_199_254_740_993),
            date: Some(reference(784_891_800.123456789)),
            fingerprint: Some(FINGERPRINT.into()),
            ..new_cleanup_item("D4/L0/001".into(), "Zoë.jpg".into(), MediaKind::Photo)
        },
    ]
}

fn sample_assessment() -> CompressionAssessment {
    let source = ImageFacts {
        orientation: 6,
        color_space: "kCGColorSpaceSRGB".into(),
        ..new_image_facts(4000, 3000, 8_000_000)
    };
    let output = ImageFacts {
        bytes: 7_998_000,
        orientation: 1,
        ..source.clone()
    };
    new_compression_assessment(source, output, 41.123456789, 29.5, false)
}

fn sample_state() -> ReviewState {
    let mut state = new_review_state();
    state.decisions = HashMap::from([
        ("A1B2/L0/001".to_string(), ReviewDecision::Remove),
        (
            "file:///Docs/report%20final.pdf".to_string(),
            ReviewDecision::Keep,
        ),
        ("C3/L0/001".to_string(), ReviewDecision::Later),
        ("D4/L0/001".to_string(), ReviewDecision::Undecided),
    ]);
    state.revisions = HashMap::from([
        (
            "A1B2/L0/001".to_string(),
            "784891800.25:4032x3024:2".to_string(),
        ),
        ("C3/L0/001".to_string(), "1.5:1920x1080:1".to_string()),
    ]);
    state.history = vec![
        HistoryEntry {
            id: "6F9619FF-8B86-D011-B42D-00C04FC964FF".parse().unwrap(),
            date: reference(784_891_900.5),
            message: "Moved 2 items to Recently Deleted.".into(),
        },
        HistoryEntry {
            id: "00000000-0000-4000-8000-000000000001".parse().unwrap(),
            date: reference(784_000_000.0),
            message: "Saved a compressed copy of “Zoë.jpg”.".into(),
        },
    ];
    state.processed_sources = strings(&["A1B2/L0/001", "D4/L0/001"]);
    state
}

fn sample_package() -> ContactPackage {
    let a = new_contact_record(
        "A:ABPerson".into(),
        "Jean Example Test".into(),
        "local".into(),
        vec![
            new_contact_field("email".into(), "jean@example.com".into(), "Work".into()),
            new_contact_field("givenName".into(), "Jean".into(), String::new()),
            new_contact_field(
                "phone".into(),
                "+44 20 7946 0018 ext 12".into(),
                "_$!<Home>!$_".into(),
            ),
        ],
        strings(&["+442079460018;ext=12", "+442079460018"]),
        strings(&["jean@example.com"]),
    );
    let b = ContactRecord {
        inaccessible_fields: vec![],
        ..new_contact_record(
            "B:ABPerson".into(),
            "Zoë".into(),
            "exchange".into(),
            vec![new_contact_field(
                "givenName".into(),
                "Zoë".into(),
                String::new(),
            )],
            vec![],
            vec![],
        )
    };
    new_contact_package(
        vec![a, b],
        HashMap::from([
            ("A:ABPerson".to_string(), vec![0, 1, 2, 250, 255]),
            ("B:ABPerson".to_string(), b"bplist00".to_vec()),
        ]),
    )
}

/// The JSON value with numbers as doubles and string arrays sorted (Swift writes sets in hash
/// order), so two encoders' outputs compare by content.
fn normalized(value: Value) -> Value {
    match value {
        Value::Number(n) => serde_json::json!(n.as_f64().unwrap()),
        Value::Array(items) => {
            let mut items: Vec<Value> = items.into_iter().map(normalized).collect();
            if items.iter().all(Value::is_string) {
                items.sort_by(|a, b| a.as_str().cmp(&b.as_str()));
            }
            Value::Array(items)
        }
        Value::Object(map) => {
            Value::Object(map.into_iter().map(|(k, v)| (k, normalized(v))).collect())
        }
        other => other,
    }
}
fn same_json(swift: &[u8], rust: &[u8]) {
    let swift: Value = serde_json::from_slice(swift).unwrap();
    let rust: Value = serde_json::from_slice(rust).unwrap();
    assert_eq!(normalized(rust), normalized(swift));
}

// MARK: Swift wrote, Rust reads

#[test]
fn swift_review_state_reads_back_equal() {
    let swift = include_bytes!("fixtures/swift-review-state.json");
    let state = review_state_decode(swift).unwrap();
    assert_eq!(state, sample_state());
    same_json(swift, &review_state_encode(&state).unwrap());
}

#[test]
fn swift_contacts_package_reads_back_equal() {
    let swift = include_bytes!("fixtures/swift-contacts-package.json");
    let package = contact_package_decode(swift).unwrap();
    assert_eq!(package, sample_package());
    contact_package_verify(&sample_package(), swift).unwrap();
    same_json(swift, &contact_package_encode(&package).unwrap());
}

#[test]
fn swift_items_groups_and_assessment_read_back_equal() {
    let items: Vec<CleanupItem> =
        serde_json::from_slice(include_bytes!("fixtures/swift-items.json")).unwrap();
    assert_eq!(items, sample_items());
    same_json(
        include_bytes!("fixtures/swift-items.json"),
        &serde_json::to_vec(&items).unwrap(),
    );

    let groups: Vec<MatchGroup> =
        serde_json::from_slice(include_bytes!("fixtures/swift-groups.json")).unwrap();
    assert_eq!(groups, matching_exact(&sample_items()));
    same_json(
        include_bytes!("fixtures/swift-groups.json"),
        &serde_json::to_vec(&groups).unwrap(),
    );

    let assessment: CompressionAssessment =
        serde_json::from_slice(include_bytes!("fixtures/swift-assessment.json")).unwrap();
    assert_eq!(assessment, sample_assessment());
    same_json(
        include_bytes!("fixtures/swift-assessment.json"),
        &serde_json::to_vec(&assessment).unwrap(),
    );
}

#[test]
fn swift_revision_strings_match() {
    let table = include_str!("fixtures/swift-revisions.tsv");
    let mut checked = 0;
    for line in table.lines() {
        let columns: Vec<&str> = line.split('\t').collect();
        let stamp = (columns[1] != "nil").then(|| columns[1].parse::<f64>().unwrap());
        let ours = match columns[0] {
            "file" => file_revision(
                stamp,
                (columns[2] != "nil").then(|| columns[2].parse().unwrap()),
            ),
            "photo" => photo_revision(stamp, 4032, 3024, 2),
            other => panic!("unknown row {other}"),
        };
        assert_eq!(ours, columns[3], "{line}");
        checked += 1;
    }
    assert_eq!(checked, 125);
}

// MARK: Rust writes, Swift reads

fn rust_written() -> Vec<(&'static str, Vec<u8>)> {
    vec![
        (
            "rust-review-state.json",
            review_state_encode(&sample_state()).unwrap(),
        ),
        (
            "rust-contacts-package.json",
            contact_package_encode(&sample_package()).unwrap(),
        ),
        (
            "rust-items.json",
            serde_json::to_vec(&sample_items()).unwrap(),
        ),
        (
            "rust-groups.json",
            serde_json::to_vec(&matching_exact(&sample_items())).unwrap(),
        ),
        (
            "rust-assessment.json",
            serde_json::to_vec(&sample_assessment()).unwrap(),
        ),
    ]
}

/// `CLEANER_WRITE_FIXTURES=1 cargo test rust_writes` refreshes them; Swift must then read them again.
#[test]
fn rust_writes_the_files_swift_read() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures");
    for (name, bytes) in rust_written() {
        if std::env::var_os("CLEANER_WRITE_FIXTURES").is_some() {
            std::fs::write(dir.join(name), &bytes).unwrap();
        }
        let fixture = std::fs::read(dir.join(name)).unwrap();
        assert_eq!(
            String::from_utf8(bytes).unwrap(),
            String::from_utf8(fixture).unwrap(),
            "{name}"
        );
    }
}
