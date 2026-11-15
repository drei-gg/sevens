//! Every AlarmCore test from the iOS app (Kit/Tests/AlarmCoreTests), ported one to one, so the
//! crate is held to the behaviour the Swift implementation shipped with. Names match the Swift
//! test functions. `failedWriteDoesNotPublish` is store-only (the write and publish stay native)
//! and is not here; `corruptAndFutureFilesArePreserved` keeps its codec half.

use alarm_core::*;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn cal(zone: &str) -> CalendarSpec {
    CalendarSpec { time_zone_id: zone.into(), first_weekday: 1 }
}
fn date(text: &str) -> SystemTime {
    let ts: jiff::Timestamp = text.parse().unwrap();
    SystemTime::from(ts)
}
fn now() -> SystemTime {
    SystemTime::now()
}
/// Foundation's `Date.distantPast`, 0001-01-01T00:00:00Z.
fn distant_past() -> SystemTime {
    UNIX_EPOCH - Duration::from_secs(62_135_596_800)
}
fn routine() -> Routine {
    new_routine(RoutineKind::Alarm, now())
}
fn slot(minute: i32, weekdays: &[i32]) -> TimeSlot {
    new_time_slot(minute, weekdays.to_vec())
}
fn hour_minute(t: SystemTime) -> (i8, i8) {
    let z = jiff::Timestamp::try_from(t).unwrap().to_zoned(jiff::tz::TimeZone::UTC);
    (z.hour(), z.minute())
}

// MARK: AlarmCoreTests.swift

#[test]
fn defining_mwf_example() {
    let mut r = new_routine(RoutineKind::Loop, now());
    r.slots = vec![slot(420, &[2, 6]), slot(720, &[2, 4, 6]), slot(840, &[2, 4, 6])];
    let compiled = schedule_registrations(&r, now()).unwrap();
    assert_eq!(compiled.len(), 3);
    let dates = schedule_occurrences(&r, date("2026-11-08T00:00:00Z"), 7, cal("UTC")).unwrap();
    assert_eq!(dates.len(), 8);
    assert!(!dates.contains(&date("2026-11-11T07:00:00Z")));
    r.slots[1].minute = 750;
    let edited = schedule_occurrences(&r, date("2026-11-08T00:00:00Z"), 7, cal("UTC")).unwrap();
    assert_eq!(edited.iter().filter(|d| hour_minute(**d) == (12, 30)).count(), 3);
    assert_eq!(r.slots.len(), 3);
}

#[test]
fn overnight_sunday_rolls_to_monday() {
    let mut r = routine();
    r.timing = Timing::Window;
    r.window_start = 1380;
    r.window_end = 60;
    r.interval_minutes = 60;
    r.window_days = vec![1];
    let rows = schedule_registrations(&r, now()).unwrap();
    assert_eq!(rows.iter().find(|x| x.minute == Some(0)).map(|x| x.weekdays.clone()), Some(vec![2]));
    assert_eq!(rows.iter().find(|x| x.minute == Some(1380)).map(|x| x.weekdays.clone()), Some(vec![1]));
    assert_eq!(rows.len(), 3);
}

#[test]
fn duplicates_coalesce_and_ids_survive_edits() {
    let mut r = routine();
    r.slots = vec![slot(420, &[2]), slot(420, &[2, 4])];
    let rows = schedule_registrations(&r, now()).unwrap();
    r.name = "New title".into();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].weekdays, vec![2, 4]);
    assert_eq!(schedule_registrations(&r, now()).unwrap()[0].id, rows[0].id);
}

#[test]
#[allow(non_snake_case)]
fn DST_gap_and_fold() {
    let mut r = routine();
    r.slots = vec![slot(150, &[1])];
    let gap = schedule_occurrences(&r, date("2026-03-08T05:00:00Z"), 1, cal("America/New_York")).unwrap();
    assert_eq!(gap, vec![date("2026-03-08T07:00:00Z")]);
    r.slots[0].minute = 90;
    let fold = schedule_occurrences(&r, date("2026-11-01T04:00:00Z"), 1, cal("America/New_York")).unwrap();
    assert_eq!(fold, vec![date("2026-11-01T05:30:00Z")]);
}

#[test]
fn ended_once_and_disabled_produce_nothing() {
    let mut r = routine();
    r.timing = Timing::Once;
    r.once = distant_past();
    assert!(schedule_registrations(&r, now()).unwrap().is_empty());
    r.timing = Timing::Weekly;
    r.enabled = false;
    assert!(schedule_registrations(&r, now()).unwrap().is_empty());
}

#[test]
fn invalid_and_oversized_windows_reject() {
    let mut r = routine();
    r.slots[0].weekdays = vec![];
    assert!(routine_validate(&r).is_err());
    r = routine();
    r.timing = Timing::Window;
    r.window_start = 0;
    r.window_end = 1439;
    r.interval_minutes = 5;
    assert!(schedule_registrations(&r, now()).is_err());
}

#[test]
fn mission_checks_are_exact() {
    assert!(arithmetic_accepts(ArithmeticChallenge { left: 12, right: 8 }, "20".into()));
    assert!(!arithmetic_accepts(ArithmeticChallenge { left: 12, right: 8 }, "21".into()));
    assert!(mission_check_phrase(" I am awake ".into(), "I am awake".into()));
    assert!(!mission_check_code("https://wrong".into(), "https://right".into()));
    assert!(!mission_check_code("".into(), "".into()));
}

#[test]
fn round_trip_and_dormant_import() {
    let mut a = new_loop_archive();
    a.routines = vec![routine()];
    a.events = vec![new_loop_event(&a.routines[0], EventKind::Manual, now(), String::new())];
    assert_eq!(backup_decode(&backup_encode(&a).unwrap()).unwrap(), a);
    let merged = backup_merge(&a, &new_loop_archive()).unwrap();
    assert!(!merged.routines[0].enabled);
    assert!(merged.installed.is_empty());
    assert_eq!(backup_merge(&a, &merged).unwrap().events.len(), 1);
    let mut conflict = a.clone();
    conflict.events[0].note = "Changed".into();
    assert!(backup_merge(&conflict, &a).is_err());
}

// failedWriteDoesNotPublish: LoopRepository's writer and publish-after-write stay native.

#[test]
fn corrupt_and_future_files_are_preserved() {
    // The file half (bytes left untouched) stays with the native store; the decode refuses both.
    assert!(matches!(backup_decode(b"broken"), Err(LoopError::Unreadable { .. })));
    let mut a = new_loop_archive();
    a.version = 2;
    assert_eq!(
        backup_decode(&backup_encode(&a).unwrap()),
        Err(LoopError::Invalid { reason: "This backup needs a newer app. Your file has not been changed.".into() })
    );
}

#[test]
fn csv_escapes_formula_and_quotes() {
    let mut a = new_loop_archive();
    let mut r = routine();
    r.name = "=formula,\"x\"".into();
    a.events = vec![new_loop_event(&r, EventKind::Manual, now(), String::new())];
    assert!(backup_csv(&a).contains("\"'=formula,\"\"x\"\"\""));
}

// MARK: ChallengeTests.swift

#[test]
fn memory_requires_full_order() {
    let c = SequenceChallenge::new(vec![2, 1, 2]);
    let first = c.tap(2);
    assert!(first);
    let wrong = c.tap(0);
    assert!(!wrong);
    assert_eq!(c.progress(), 0);
    let a = c.tap(2);
    let b = c.tap(1);
    assert!(a && b);
    assert!(!c.complete());
    let last = c.tap(2);
    assert!(last && c.complete());
    let extra = c.tap(2);
    assert!(!extra);
}

#[test]
fn sustained_acceleration_does_not_count_repeatedly() {
    let c = ShakeCounter::new();
    c.sample(1.5, 1.0);
    c.sample(1.6, 2.0);
    assert_eq!(c.count(), 1);
    c.sample(0.1, 2.1);
    c.sample(1.5, 2.5);
    assert_eq!(c.count(), 2);
    c.sample(0.0, 2.55);
    c.sample(1.5, 2.6);
    assert_eq!(c.count(), 2);
    c.sample(f64::NAN, 3.0);
    assert_eq!(c.count(), 2);
}

#[test]
fn color_checks_ink() {
    assert!(color_word_accepts(ColorWord::Blue, ColorWord::Blue));
    assert!(!color_word_accepts(ColorWord::Red, ColorWord::Blue));
}

#[test]
fn old_backup_defaults_difficulty() {
    let mut r = routine();
    r.mission = MissionKind::Memory;
    let bytes = serde_json::to_vec(&r).unwrap();
    let decoded: Routine = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(routine_difficulty(&decoded), 1);
    r.challenge_level = Some(4);
    assert!(routine_validate(&r).is_err());
}

// MARK: DefaultsTests.swift

#[test]
fn archives_without_defaults_remain_readable() {
    let bytes = backup_encode(&new_loop_archive()).unwrap();
    let mut object: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    object.as_object_mut().unwrap().remove("alarmDefaults");
    let archive = backup_decode(&serde_json::to_vec(&object).unwrap()).unwrap();
    assert_eq!(archive.alarm_defaults, None);
    assert_eq!(alarm_defaults_routine(&archive.alarm_defaults.unwrap_or_default(), RoutineKind::Alarm, now()).snooze_minutes, 5);
}

#[test]
fn defaults_round_trip_and_reject_invalid_backup_values() {
    let mut archive = new_loop_archive();
    let mut defaults = default_alarm_defaults();
    defaults.tone = AlarmTone::Pulse;
    defaults.snooze_minutes = 12;
    defaults.snooze_limit = 0;
    defaults.follow_up_minutes = 7;
    archive.alarm_defaults = Some(defaults.clone());
    assert_eq!(backup_decode(&backup_encode(&archive).unwrap()).unwrap().alarm_defaults, Some(defaults.clone()));
    let looped = alarm_defaults_routine(&defaults, RoutineKind::Loop, now());
    assert_eq!(looped.delivery, Delivery::Reminder);
    assert_eq!(looped.follow_up_minutes, 0);
    routine_validate(&looped).unwrap();
    defaults.snooze_minutes = 0;
    archive.alarm_defaults = Some(defaults);
    assert!(backup_decode(&backup_encode(&archive).unwrap()).is_err());
}
