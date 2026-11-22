//! Files written by the shipped Swift code (generated in the Swift 6.3 Linux container from the real
//! QRCore types and encoders; see the crate README). History and known addresses are written with
//! `.sortedKeys`, so those must come back byte for byte. The lists bundle the app stores is the exact
//! bytes `scripts/refresh-lists` published; Swift's own `JSONEncoder()` has unstable key order, so
//! its output only has to read back equal.

use qr_core::*;

const FRACTIONAL_INPUT: &str = r#"{"schema":1,"items":[{"id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","kind":"made","payload":"x","payloadEncoding":"text","symbology":"qr","typeID":"text","level":1,"signalIDs":[],"date":"2023-11-14T22:13:20.750Z","note":null},{"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301","kind":"made","payload":"y","payloadEncoding":"text","symbology":"qr","typeID":"text","level":1.0,"signalIDs":[],"date":"1969-12-31T23:59:59.250+01:00"}]}"#;

fn items(bytes: &[u8]) -> Vec<HistoryItem> {
    match decode_history(bytes) {
        HistoryDecoded::Items { items } => items,
        other => panic!("{other:?}"),
    }
}

fn text(b: &[u8]) -> String {
    String::from_utf8(b.to_vec()).unwrap()
}

#[test]
fn swift_written_history_round_trips_byte_for_byte() {
    let swift = include_bytes!("fixtures/swift-history.json");
    let decoded = items(swift);
    assert_eq!(decoded.len(), 4);
    assert_eq!(text(&encode_history(&decoded)), text(swift));
}

#[test]
fn swift_written_known_addresses_round_trip_byte_for_byte() {
    let swift = include_bytes!("fixtures/swift-known.json");
    let KnownAddressesDecoded::Addresses { known } = decode_known_file(swift) else {
        panic!()
    };
    assert_eq!(known.entries.len(), 3);
    assert_eq!(text(&encode_known_file(&known)), text(swift));
}

#[test]
fn swift_written_history_export_round_trips_byte_for_byte() {
    let swift = include_bytes!("fixtures/swift-history-export.json");
    let known = export_known_addresses(swift);
    assert_eq!(known.entries.len(), 3);
    assert_eq!(text(&export_json(&items(swift), Some(&known))), text(swift));
}

#[test]
fn fractional_dates_are_written_as_swift_writes_them() {
    let swift = include_bytes!("fixtures/swift-history-fractional.json");
    assert_eq!(
        text(&encode_history(&items(FRACTIONAL_INPUT.as_bytes()))),
        text(swift)
    );
}

#[test]
fn refresh_lists_bundle_round_trips_byte_for_byte() {
    let published = include_bytes!("fixtures/lists-refresh.json");
    let bundle = ListsBundle::decode(published).unwrap();
    assert_eq!(text(&bundle.encode()), text(published));
}

#[test]
fn swift_encoder_lists_bundle_reads_equal() {
    let swift = ListsBundle::decode(include_bytes!("fixtures/swift-encoder-lists.json")).unwrap();
    let published = ListsBundle::decode(include_bytes!("fixtures/lists-refresh.json")).unwrap();
    // Swift's dictionary order is random; compare confusables as a map.
    assert_eq!(
        swift.files.confusables.to_map(),
        published.files.confusables.to_map()
    );
    let mut s = swift.clone();
    s.files.confusables = published.files.confusables.clone();
    assert_eq!(s, published);
}
