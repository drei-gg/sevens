//! The delivery rules added for Android (src/delivery.rs), checked against what iOS's AlarmModel
//! does with the same inputs.

use alarm_core::*;
use std::time::{Duration, SystemTime};

fn cal(zone: &str) -> CalendarSpec {
    CalendarSpec { time_zone_id: zone.into(), first_weekday: 1 }
}
fn date(text: &str) -> SystemTime {
    let ts: jiff::Timestamp = text.parse().unwrap();
    SystemTime::from(ts)
}
fn at(text: &str) -> SystemTime {
    date(text)
}

#[test]
fn reminders_split_per_weekday_and_alarms_do_not() {
    let now = at("2026-11-08T00:00:00Z");
    let mut alarm = new_routine(RoutineKind::Alarm, now);
    alarm.slots = vec![new_time_slot(420, vec![2, 4, 6])];
    let mut quiet = new_routine(RoutineKind::Loop, now);
    quiet.slots = vec![new_time_slot(720, vec![2, 4, 6])];
    let mut archive = new_loop_archive();
    archive.routines = vec![alarm.clone(), quiet.clone()];
    let rows = desired_registrations(&archive, now).unwrap();
    assert_eq!(rows.iter().filter(|r| r.routine.id == alarm.id).count(), 1);
    let split: Vec<_> = rows.iter().filter(|r| r.routine.id == quiet.id).collect();
    assert_eq!(split.len(), 3);
    assert!(split.iter().all(|r| r.weekdays.len() == 1));
    let root = schedule_registrations(&quiet, now).unwrap()[0].id;
    assert_eq!(split[0].id, schedule_identifier(root, "weekday:2".into()));
}

#[test]
fn more_than_sixty_reminders_are_refused() {
    let now = at("2026-11-08T00:00:00Z");
    let mut quiet = new_routine(RoutineKind::Loop, now);
    quiet.slots = (0..9).map(|i| new_time_slot(600 + i * 10, vec![1, 2, 3, 4, 5, 6, 7])).collect();
    let mut archive = new_loop_archive();
    archive.routines = vec![quiet];
    let error = desired_registrations(&archive, now).unwrap_err();
    assert!(matches!(error, LoopError::Invalid { reason } if reason.contains("more than 60 quiet reminders")));
}

#[test]
fn next_ring_of_a_weekly_registration() {
    let now = at("2026-11-11T08:00:00Z"); // Wednesday
    let mut r = new_routine(RoutineKind::Alarm, now);
    r.slots = vec![new_time_slot(420, vec![2, 6])];
    let row = &schedule_registrations(&r, now).unwrap()[0];
    // Friday 07:00 UTC is next; Monday is further.
    assert_eq!(registration_next(row, now, cal("UTC")), Some(at("2026-11-13T07:00:00Z")));
    // Read in the device's zone.
    assert_eq!(registration_next(row, now, cal("Asia/Manila")), Some(at("2026-11-12T23:00:00Z")));
    // Strictly after: ringing at 07:00 Friday, the next is Monday.
    assert_eq!(registration_next(row, at("2026-11-13T07:00:00Z"), cal("UTC")), Some(at("2026-11-16T07:00:00Z")));
}

#[test]
fn next_ring_across_daylight_saving() {
    // 02:30 on 8 March 2026 doesn't exist in New York; it rings when the gap ends, 03:00 EDT.
    let mut r = new_routine(RoutineKind::Alarm, at("2026-03-07T12:00:00Z"));
    r.slots = vec![new_time_slot(150, vec![1])];
    let row = &schedule_registrations(&r, at("2026-03-07T12:00:00Z")).unwrap()[0];
    assert_eq!(registration_next(row, at("2026-03-07T12:00:00Z"), cal("America/New_York")), Some(at("2026-03-08T07:00:00Z")));
    // 01:30 on 1 November 2026 happens twice; it rings the first time (EDT).
    let mut fold = r.clone();
    fold.slots = vec![new_time_slot(90, vec![1])];
    let row = &schedule_registrations(&fold, at("2026-10-31T12:00:00Z")).unwrap()[0];
    assert_eq!(registration_next(row, at("2026-10-31T12:00:00Z"), cal("America/New_York")), Some(at("2026-11-01T05:30:00Z")));
}

#[test]
fn one_off_next_only_while_in_the_future() {
    let now = at("2026-11-11T08:00:00Z");
    let mut r = new_routine(RoutineKind::Alarm, now);
    r.timing = Timing::Once;
    r.once = at("2026-11-11T09:00:00Z");
    let row = &schedule_registrations(&r, now).unwrap()[0];
    assert_eq!(registration_next(row, now, cal("UTC")), Some(r.once));
    assert_eq!(registration_next(row, at("2026-11-11T09:00:00Z"), cal("UTC")), None);
}

#[test]
fn snoozes_and_checks_belong_to_their_ringing() {
    let now = at("2026-11-11T07:00:00Z");
    let r = new_routine(RoutineKind::Alarm, now);
    let mut row = schedule_registrations(&r, now).unwrap()[0].clone();
    row.occurrence_id = Some(uuid::Uuid::new_v4());
    let snooze = follow_on_registration(&row, 5, "snooze".into(), now);
    assert_eq!(snooze.id, schedule_identifier(row.occurrence_id.unwrap(), "snooze:0".into()));
    assert_eq!(snooze.date, Some(as_swift_date(now + Duration::from_secs(300))));
    assert_eq!((snooze.minute, snooze.weekdays.len(), snooze.snoozes), (None, 0, 1));
    assert_eq!(snooze.occurrence_id, row.occurrence_id);
    let again = follow_on_registration(&snooze, 5, "snooze".into(), now);
    assert_ne!(again.id, snooze.id);
    assert_eq!(again.snoozes, 2);
    let check = follow_on_registration(&snooze, 10, "followUp".into(), now);
    assert_eq!((check.purpose.as_str(), check.snoozes), ("followUp", 1));
    // Without an occurrence the row's own ID roots it.
    row.occurrence_id = None;
    assert_eq!(follow_on_registration(&row, 5, "snooze".into(), now).occurrence_id, Some(row.id));
}

#[test]
fn pruning_keeps_what_ios_keeps() {
    let now = at("2026-11-11T07:00:00Z");
    let mut r = new_routine(RoutineKind::Alarm, now);
    r.timing = Timing::Once;
    r.once = at("2026-11-11T09:00:00Z");
    let mut archive = new_loop_archive();
    archive.routines = vec![r.clone()];
    let row = schedule_registrations(&r, now).unwrap()[0].clone();
    let snooze = follow_on_registration(&row, 5, "snooze".into(), now);
    assert!(receipt_keep(&snooze, &archive, false, now), "a future snooze of an enabled routine");
    archive.routines[0].enabled = false;
    assert!(!receipt_keep(&snooze, &archive, false, now), "its routine was turned off");
    let mut rang = row.clone();
    rang.date = Some(at("2026-11-11T06:30:00Z"));
    assert!(receipt_keep(&rang, &archive, false, now), "rang within the hour");
    rang.date = Some(at("2026-11-11T05:30:00Z"));
    assert!(!receipt_keep(&rang, &archive, false, now));
    archive.pending = Some(rang.clone());
    assert!(receipt_keep(&rang, &archive, false, now), "its mission is open");
    let mut weekly = row.clone();
    weekly.date = None;
    weekly.minute = Some(420);
    archive.pending = None;
    assert!(!receipt_keep(&weekly, &archive, true, now));
}

#[test]
fn past_one_offs_turn_off() {
    let now = at("2026-11-11T07:00:00Z");
    let mut past = new_routine(RoutineKind::Alarm, now);
    past.timing = Timing::Once;
    past.once = at("2026-11-11T06:00:00Z");
    let mut future = past.clone();
    future.once = at("2026-11-11T08:00:00Z");
    let weekly = new_routine(RoutineKind::Alarm, now);
    let out = routines_finish_past_once(vec![past, future, weekly], now);
    assert_eq!(out.iter().map(|r| r.enabled).collect::<Vec<_>>(), vec![false, true, true]);
}

#[test]
fn naps_and_duplicates() {
    let now = at("2026-11-11T07:00:00Z");
    let defaults = AlarmDefaults { tone: AlarmTone::Chime, snooze_minutes: 9, snooze_limit: 2, follow_up_minutes: 5 };
    let nap = nap_routine(Some(defaults.clone()), 20, now).unwrap();
    assert_eq!(nap.name, "20-minute nap");
    assert_eq!((nap.timing, nap.once), (Timing::Once, as_swift_date(now + Duration::from_secs(1200))));
    assert_eq!((nap.tone, nap.snooze_minutes, nap.follow_up_minutes), (Some(AlarmTone::Chime), 9, 5));
    assert!(nap_routine(None, 0, now).is_err());
    assert!(nap_routine(None, 721, now).is_err());
    assert!(nap_routine(None, 720, now).is_ok());

    let mut r = new_routine(RoutineKind::Alarm, now);
    r.name = "é".repeat(99);
    let copy = duplicate_routine(&r);
    assert_ne!(copy.id, r.id);
    assert!(!copy.enabled);
    assert_eq!(copy.name.chars().filter(|c| *c == 'é').count(), 99);
    assert_eq!(copy.name.len(), "é".repeat(99).len() + 1); // "é" ×99 and the space: 100 characters
}

#[test]
fn save_checks_refuse_a_past_one_off() {
    let now = at("2026-11-11T07:00:00Z");
    let mut r = new_routine(RoutineKind::Alarm, now);
    assert!(routine_check_save(&r, now).is_ok());
    r.timing = Timing::Once;
    r.once = at("2026-11-11T06:00:00Z");
    assert!(matches!(routine_check_save(&r, now), Err(LoopError::Invalid { reason }) if reason == "Choose a future date for this alarm."));
    r.enabled = false;
    assert!(routine_check_save(&r, now).is_ok());
}

#[test]
fn groups_are_trimmed_unique_and_sorted() {
    let now = at("2026-11-11T07:00:00Z");
    let mut a = new_routine(RoutineKind::Alarm, now);
    a.group = Some(" Training ".into());
    let mut b = a.clone();
    b.group = Some("Training".into());
    let mut c = a.clone();
    c.group = Some("Away".into());
    let mut d = a.clone();
    d.group = Some("  ".into());
    assert_eq!(routine_groups(&[a, b, c, d]), vec!["Away".to_string(), "Training".to_string()]);
}

#[test]
fn identifiers_match_the_shared_crate_pins() {
    // Pinned in crates/seven-schedule/tests/golden.rs; armed alarms on phones carry these.
    let root = Uuid::parse_str("3F2504E0-4F89-11D3-9A0C-0305E82C3301").unwrap();
    assert_eq!(schedule_identifier(root, "minute:420".into()).to_string(), "5a4ad36c-48ed-123e-3245-91ccebf9ff5f");
}
