//! The Swift app's plain `JSONEncoder()` writes compact JSON with `\/`, upper-case UUIDs,
//! reference-date doubles and `nil` left out, but its key order is hash-random from run to run, so
//! there is no fixed Swift byte layout to match. The core writes declaration order, and the
//! `swift-manifest`/`swift-backup-index` fixtures pin that output byte for byte. The
//! `swift-encoder-*` fixtures were written by Swift 6.3's real encoder (Linux, swift-foundation, as on
//! iOS 17+) and must read back equal to them; Swift reads both fixtures back (checked the same way).

use scanner_core::*;

const MANIFEST: &[u8] = include_bytes!("fixtures/swift-manifest.json");
const INDEX: &[u8] = include_bytes!("fixtures/swift-backup-index.json");

#[test]
fn manifest_round_trips_byte_for_byte() {
    let document = manifest_read(MANIFEST, "6F1C2A9E-3B7D-4E21-9A55-0C8D2E4F7A10".into()).unwrap();
    assert_eq!(document.title, "Receipts/November – café");
    assert_eq!(document.created, 816_091_200.123456);
    assert_eq!(document.pages[0].adjustments.unwrap().brightness, 0.15);
    assert!(document.pages[1].adjustments.is_none());
    let ours = manifest_encode(&document).unwrap();
    assert_eq!(
        String::from_utf8(ours).unwrap(),
        String::from_utf8(MANIFEST.to_vec()).unwrap()
    );
    // The folder must be named by the ID exactly as `uuidString` writes it.
    assert_eq!(
        manifest_read(MANIFEST, "6f1c2a9e-3b7d-4e21-9a55-0c8d2e4f7a10".into()),
        Err(ScanError::InvalidDocument)
    );
}

#[test]
fn backup_index_round_trips_byte_for_byte() {
    let index: BackupIndex = {
        let mut body = INDEX.to_vec();
        body.extend(backup_checksum_bytes(crc32(INDEX)));
        let length = 13 + body.len() as u64 + (1_100_000 + 2 + 1) + 3 * 4;
        read_backup_index(length, &body).unwrap()
    };
    assert_eq!(index.entries.len(), 2);
    assert!(index.entries[1].deleted && index.entries[1].document.note.is_none());
    assert_eq!(
        index.entries[0].document.pages[0]
            .adjustments
            .unwrap()
            .contrast,
        1.0
    );
    let header = backup_header(index.entries).unwrap();
    assert_eq!(&header[..9], b"SEVSCAN1\n");
    assert_eq!(
        u32::from_le_bytes(header[9..13].try_into().unwrap()) as usize,
        INDEX.len()
    );
    assert_eq!(
        String::from_utf8(header[13..13 + INDEX.len()].to_vec()).unwrap(),
        String::from_utf8(INDEX.to_vec()).unwrap()
    );
    assert_eq!(header[13 + INDEX.len()..], crc32(INDEX).to_le_bytes());
}

#[test]
fn doubles_are_written_as_swift_writes_them() {
    let mut document = document_decode(MANIFEST).unwrap();
    let written = |d: &ScanDocument| {
        let json = String::from_utf8(document_encode(d).unwrap()).unwrap();
        json.split("\"created\":")
            .nth(1)
            .unwrap()
            .split(',')
            .next()
            .unwrap()
            .to_string()
    };
    for (value, swift) in [
        (0.0, "0"),
        (816_000_000.0, "816000000"),
        (0.5, "0.5"),
        (-978_307_200.25, "-978307200.25"),
        (0.0001, "0.0001"),
        (0.00001, "1e-05"),
        (1.5e20, "1.5e+20"),
        (9_007_199_254_740_992.0, "9007199254740992"),
        (1e16, "1e+16"),
    ] {
        document.created = value;
        assert_eq!(written(&document), swift, "{value}");
    }
    // Swift `Int` fields also read from integral doubles.
    let loose = String::from_utf8(MANIFEST.to_vec())
        .unwrap()
        .replace("\"version\":1", "\"version\":1.0");
    assert_eq!(document_decode(loose.as_bytes()).unwrap().version, 1);
    let strict = String::from_utf8(MANIFEST.to_vec())
        .unwrap()
        .replace("\"version\":1", "\"version\":1.5");
    assert_eq!(
        document_decode(strict.as_bytes()),
        Err(ScanError::Unreadable)
    );
    // `UUID(uuidString:)` reads only the hyphenated form.
    let simple = String::from_utf8(MANIFEST.to_vec()).unwrap().replace(
        "6F1C2A9E-3B7D-4E21-9A55-0C8D2E4F7A10",
        "6F1C2A9E3B7D4E219A550C8D2E4F7A10",
    );
    assert_eq!(
        document_decode(simple.as_bytes()),
        Err(ScanError::Unreadable)
    );
}

const REAL_MANIFEST: &[u8] = include_bytes!("fixtures/swift-encoder-manifest.json");
const REAL_INDEX: &[u8] = include_bytes!("fixtures/swift-encoder-backup-index.json");

#[test]
fn swift_encoder_output_reads_in_any_key_order() {
    let ours = manifest_read(MANIFEST, "6F1C2A9E-3B7D-4E21-9A55-0C8D2E4F7A10".into()).unwrap();
    let theirs =
        manifest_read(REAL_MANIFEST, "6F1C2A9E-3B7D-4E21-9A55-0C8D2E4F7A10".into()).unwrap();
    assert_eq!(theirs, ours);
    assert_eq!(
        manifest_encode(&theirs).unwrap(),
        MANIFEST.strip_suffix(b"\n").unwrap_or(MANIFEST)
    );

    let read = |index: &[u8]| {
        let mut body = index.to_vec();
        body.extend(backup_checksum_bytes(crc32(index)));
        let length = 13 + body.len() as u64 + (1_100_000 + 2 + 1) + 3 * 4;
        read_backup_index(length, &body).unwrap()
    };
    assert_eq!(read(REAL_INDEX), read(INDEX));
}
