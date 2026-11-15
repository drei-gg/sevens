//! A file in the exact shape the Swift app's `BackupCodec.encode` writes (default `JSONEncoder`,
//! `.prettyPrinted` and `.sortedKeys`, dates as seconds since 2001) must come back byte for byte.

use alarm_core::*;

#[test]
fn swift_written_archive_round_trips_byte_for_byte() {
    let swift = include_bytes!("fixtures/swift-written.json");
    let archive = backup_decode(swift).unwrap();
    assert_eq!(archive.alarm_defaults.as_ref().map(|d| d.snooze_minutes), Some(12));
    assert_eq!(archive.events[0].kind, EventKind::EmergencyStop);
    assert_eq!(archive.events[0].note, "Stopped at 06:58 / café ☕");
    assert_eq!(archive.routines[0].mission, MissionKind::Code);
    assert_eq!(archive.routines[0].code, "https://example.com/kitchen");
    assert_eq!(archive.installed[0].routine.reason, None);
    assert_eq!(archive.pending.as_ref().and_then(|p| p.occurrence_id).map(|u| u.to_string()), Some("00000000-0000-0000-0000-0000000000d1".into()));
    let ours = backup_encode(&archive).unwrap();
    assert_eq!(String::from_utf8(ours).unwrap(), String::from_utf8(swift.to_vec()).unwrap());
}

#[test]
fn missing_required_key_is_unreadable_and_missing_optional_is_not() {
    let swift = std::str::from_utf8(include_bytes!("fixtures/swift-written.json")).unwrap();
    let mut value: serde_json::Value = serde_json::from_str(swift).unwrap();
    value["routines"][0].as_object_mut().unwrap().remove("challengeLevel");
    value.as_object_mut().unwrap().remove("pending");
    assert!(backup_decode(value.to_string().as_bytes()).is_ok());
    // Swift's synthesized decoder needs every non-optional key, defaults or not.
    value["routines"][0].as_object_mut().unwrap().remove("mathCount");
    assert!(matches!(backup_decode(value.to_string().as_bytes()), Err(LoopError::Unreadable { .. })));
}
