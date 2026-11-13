//! Files written by the shipped apps. Swift's must come back byte for byte; Kotlin's must read
//! and keep every field.

use fast_core::*;
use std::time::{Duration, UNIX_EPOCH};

fn now() -> std::time::SystemTime {
    UNIX_EPOCH + Duration::from_secs(1_900_000_000)
}

#[test]
fn swift_written_archive_round_trips_byte_for_byte() {
    let swift = include_bytes!("fixtures/swift-written.json");
    let archive = archive_decode(swift, now()).unwrap();
    assert_eq!(archive.fasts[0].time_zone_id, "Etc/UTC");
    let ours = archive_encode(&archive).unwrap();
    assert_eq!(String::from_utf8(ours).unwrap(), String::from_utf8(swift.to_vec()).unwrap());
}

#[test]
fn kotlin_written_archive_reads_with_every_field() {
    let kotlin = include_bytes!("fixtures/kotlin-written.json");
    let archive = archive_decode(kotlin, now()).unwrap();
    let swift = archive_decode(include_bytes!("fixtures/swift-written.json"), now()).unwrap();
    // Same records as the Swift file the Kotlin port re-encoded.
    assert_eq!(archive.fasts.len(), swift.fasts.len());
    assert_eq!(archive.journal, swift.journal);
    assert_eq!(archive.metrics, swift.metrics);
    assert_eq!(archive.settings, swift.settings);
    let again = archive_decode(&archive_encode(&archive).unwrap(), now()).unwrap();
    assert_eq!(again, archive);
}
