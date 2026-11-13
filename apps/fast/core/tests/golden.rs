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
    let archive = archive_decode_slice(swift, now()).unwrap();
    assert_eq!(archive.fasts[0].time_zone_id, "Etc/UTC");
    let ours = archive_encode(&archive).unwrap();
    assert_eq!(String::from_utf8(ours).unwrap(), String::from_utf8(swift.to_vec()).unwrap());
}

#[test]
fn kotlin_written_archive_reads_with_every_field() {
    let kotlin = include_bytes!("fixtures/kotlin-written.json");
    let archive = archive_decode_slice(kotlin, now()).unwrap();
    let swift = archive_decode_slice(include_bytes!("fixtures/swift-written.json"), now()).unwrap();
    // Same records as the Swift file the Kotlin port re-encoded.
    assert_eq!(archive.fasts.len(), swift.fasts.len());
    assert_eq!(archive.journal, swift.journal);
    assert_eq!(archive.metrics, swift.metrics);
    assert_eq!(archive.settings, swift.settings);
    let again = archive_decode_slice(&archive_encode(&archive).unwrap(), now()).unwrap();
    assert_eq!(again, archive);
}

#[test]
fn whole_milliseconds_round_trip_exactly() {
    use std::time::Duration;
    for ms in [1_794_571_200_797u64, 1_763_036_151_797, 0, 1] {
        let t = UNIX_EPOCH + Duration::from_millis(ms);
        let mut archive = new_archive();
        archive.fasts = vec![new_fast_record(t, "UTC".into())];
        let back = archive_decode_slice(&archive_encode(&archive).unwrap(), t + Duration::from_secs(1)).unwrap();
        assert_eq!(back.fasts[0].start, t, "{ms}");
    }
}
