use alarm_core::*;
use std::time::SystemTime;

#[test]
fn template_preserves_instructions_but_has_no_history_or_device_state() {
    let mut original = new_routine(RoutineKind::Alarm, SystemTime::now());
    original.name = "Weekday study".into();
    original.reason = Some("Leave time for breakfast".into());
    original.mission = MissionKind::Code;
    original.code = "my selected mission code".into();
    let saved = backup_decode(&routine_template(&original).unwrap()).unwrap();
    let copy = &saved.routines[0];
    assert!(!copy.enabled);
    assert_ne!(copy.id, original.id);
    assert_ne!(copy.slots[0].id, original.slots[0].id);
    assert_eq!(copy.code, original.code);
    assert_eq!(copy.reason, original.reason);
    assert_eq!(copy.slots[0].minute, original.slots[0].minute);
    assert_eq!(copy.slots[0].weekdays, original.slots[0].weekdays);
    assert!(saved.events.is_empty());
    assert!(saved.installed.is_empty());
    assert!(saved.pending.is_none());
    assert!(saved.alarm_defaults.is_none());
    assert!(original.enabled);
}

#[test]
fn a_template_can_be_imported_locally_without_overwriting_and_reimport_is_idempotent() {
    let original = new_routine(RoutineKind::Loop, SystemTime::now());
    let template = backup_decode(&routine_template(&original).unwrap()).unwrap();
    let current = LoopArchive { routines: vec![original.clone()], ..LoopArchive::default() };
    let first = backup_merge(&template, &current).unwrap();
    assert_eq!(first.routines.len(), 2);
    assert_eq!(first.routines[0], original);
    assert!(!first.routines[1].enabled);
    assert_eq!(backup_merge(&template, &first).unwrap(), first);
}

#[test]
fn invalid_templates_are_rejected_before_export() {
    let mut original = new_routine(RoutineKind::Alarm, SystemTime::now());
    original.slots[0].minute = 1440;
    assert!(routine_template(&original).is_err());
}
