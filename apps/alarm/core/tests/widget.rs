use alarm_core::*;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
fn now() -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(1_800_000_000)
}
fn calendar() -> CalendarSpec {
    CalendarSpec {
        time_zone_id: "UTC".into(),
        first_weekday: 1,
    }
}
fn archive() -> LoopArchive {
    let mut r = new_routine(RoutineKind::Alarm, now());
    r.name = "Study".into();
    r.timing = Timing::Once;
    r.reason = Some("secret reason".into());
    r.phrase = "secret phrase".into();
    r.code = "secret code".into();
    let mut a = new_loop_archive();
    a.routines.push(r);
    a.installed = desired_registrations(&a, now()).unwrap();
    a
}
fn snapshot(a: &LoopArchive) -> AlarmWidgetSnapshot {
    alarm_widget_snapshot(
        a,
        a.installed.iter().map(|r| r.id).collect(),
        now(),
        calendar(),
    )
    .unwrap()
}
#[test]
fn receipts_need_native_verification_and_exact_content_match() {
    let mut a = archive();
    let s = alarm_widget_snapshot(&a, vec![], now(), calendar()).unwrap();
    assert!(s.needs_attention);
    assert!(!s.items[0].registered);
    assert!(snapshot(&a).items[0].registered);
    a.routines[0].once += Duration::from_secs(900);
    let changed = snapshot(&a);
    assert!(!changed.items[0].registered);
    assert!(changed.needs_attention);
}
#[test]
fn disabled_deleted_and_past_routines_never_appear_as_upcoming() {
    let mut a = archive();
    a.routines[0].enabled = false;
    let s = snapshot(&a);
    assert!(s.items.is_empty());
    assert!(s.needs_attention); // failed cancellation
    a.installed.clear();
    assert!(!snapshot(&a).needs_attention);
    a.routines.clear();
    assert!(snapshot(&a).items.is_empty());
    a = archive();
    a.routines[0].once = now();
    assert!(snapshot(&a).items.is_empty());
}
#[test]
fn snapshot_round_trip_is_minimal_and_validation_is_bounded() {
    let a = archive();
    let s = snapshot(&a);
    let text = alarm_widget_encode(&s).unwrap();
    for secret in [
        "secret",
        "phrase",
        "code",
        "history",
        "receipt",
        &a.routines[0].id.to_string(),
    ] {
        assert!(!text.contains(secret));
    }
    assert_eq!(alarm_widget_decode(text.clone()).unwrap(), s);
    assert!(alarm_widget_decode(text.replace("\"version\":1", "\"version\":2")).is_err());
    assert!(alarm_widget_decode(" ".repeat(65_537)).is_err());
    assert!(alarm_widget_decode("{\"version\":1}".into()).is_err());
}
#[test]
fn expiration_clock_rollback_and_timezone_change_hide_old_times() {
    let s = snapshot(&archive());
    assert!(!alarm_widget_view(&s, now(), "UTC".into()).stale);
    for (at, zone) in [
        (s.valid_until, "UTC"),
        (now() - Duration::from_secs(1), "UTC"),
        (now(), "Asia/Manila"),
    ] {
        let view = alarm_widget_view(&s, at, zone.into());
        assert!(view.stale);
        assert!(view.items.is_empty());
    }
    assert!(
        alarm_widget_view(&s, s.items[0].date, "UTC".into())
            .items
            .is_empty()
    );
}
#[test]
fn daily_registration_advances_within_the_snapshot_without_app_execution() {
    let mut a = archive();
    a.routines[0].timing = Timing::Weekly;
    a.routines[0].slots = vec![new_time_slot(7 * 60, vec![1, 2, 3, 4, 5, 6, 7])];
    a.installed = desired_registrations(&a, now()).unwrap();
    let s = snapshot(&a);
    assert!(!s.items.is_empty());
    assert!(s.items.windows(2).all(|w| w[0].date < w[1].date));
    assert!(s.items.iter().all(|i| i.registered));
}
#[test]
fn snooze_and_follow_up_are_distinct_but_test_alarms_are_not_exported() {
    let mut a = archive();
    let mut r = a.installed[0].clone();
    r.occurrence_id = Some(Uuid::new_v4());
    a.installed
        .push(follow_on_registration(&r, 5, "snooze".into(), now()));
    a.installed
        .push(follow_on_registration(&r, 10, "followUp".into(), now()));
    a.installed
        .push(follow_on_registration(&r, 1, "test".into(), now()));
    let s = snapshot(&a);
    assert_eq!(s.items.len(), 3);
    assert_eq!(s.items[0].purpose, "snooze");
    assert_eq!(s.items[1].purpose, "followUp");
}
#[test]
fn truncation_expires_before_omitted_events_can_look_like_an_empty_schedule() {
    let mut a = new_loop_archive();
    for n in 1..=100 {
        let mut r = new_routine(RoutineKind::Alarm, now());
        r.timing = Timing::Once;
        r.once = now() + Duration::from_secs(n * 60);
        a.routines.push(r);
    }
    a.installed = desired_registrations(&a, now()).unwrap();
    let s = snapshot(&a);
    assert_eq!(s.items.len(), 64);
    assert_eq!(s.valid_until, now() + Duration::from_secs(65 * 60));
    assert!(alarm_widget_view(&s, s.valid_until, "UTC".into()).stale);
}

#[test]
fn an_uncancelled_follow_up_for_a_removed_routine_requires_attention() {
    let mut a = archive();
    let row = follow_on_registration(&a.installed[0], 10, "followUp".into(), now());
    a.installed = vec![row];
    a.routines.clear();
    let s = snapshot(&a);
    assert!(s.items.is_empty());
    assert!(s.needs_attention);
}
