use bloom_core::*;
use bloom_core::{insights, model::*, schedule};
use serde_json::json;
use uuid::Uuid;
const NOW: i64 = 1794873600; // 2026-11-17 00:00 UTC; tests derive their date from this fixed clock.
fn id() -> String {
    Uuid::new_v4().to_string()
}
fn day(at: &str, flow: &str) -> DayLog {
    DayLog {
        id: id(),
        day: at.into(),
        flow: flow.into(),
        period_start: flow != "none",
        symptoms: Default::default(),
        tags: vec![],
        notes: String::new(),
        bbt: None,
        mucus: "none".into(),
        ovulation_test: "none".into(),
        pregnancy_test: "none".into(),
    }
}
fn plan() -> Plan {
    Plan {
        id: id(),
        name: "Daily pill".into(),
        method: "pill".into(),
        hormonal: true,
        start: "2026-11-01".into(),
        end: None,
        minute: 480,
        zone: "UTC".into(),
        fixed_zone: false,
        pattern: "pack".into(),
        active_days: 21,
        break_days: 7,
        every_days: 1,
        weekdays: vec![1, 2, 3, 4, 5, 6, 7],
        remind_break: false,
        enabled: true,
        initial_stock: Some(21),
        notes: String::new(),
    }
}
fn apply(a: &Archive, op: &str, record: serde_json::Value, paid: bool) -> Result<Archive> {
    let c = json!({"op":op,"record":record,"id":null,"request_id":id(),"expected_revision":a.revision,"created_at":NOW});
    serde_json::from_str(&bloom_apply(
        serde_json::to_string(a).unwrap(),
        c.to_string(),
        NOW,
        "UTC".into(),
        paid,
    )?)
    .map_err(|_| Error::invalid("test decode"))
}
#[test]
fn rejects_future_and_corrupt_schema() {
    let mut a = Archive::default();
    a.schema = 999;
    assert!(bloom_validate(serde_json::to_string(&a).unwrap()).is_err());
    assert!(bloom_validate("{broken".into()).is_err());
    assert!(
        apply(
            &Archive::default(),
            "day",
            json!(day("2099-01-01", "light")),
            true
        )
        .is_err()
    );
}
#[test]
fn pack_break_and_rollover() {
    let p = plan();
    assert_eq!(schedule::phase(&p, "2026-11-21").unwrap(), Some("active"));
    assert_eq!(schedule::phase(&p, "2026-11-22").unwrap(), None);
    assert_eq!(schedule::phase(&p, "2026-11-28").unwrap(), None);
    assert_eq!(schedule::phase(&p, "2026-11-29").unwrap(), Some("active"));
}
#[test]
fn interval_and_once() {
    let mut p = plan();
    p.pattern = "interval".into();
    p.every_days = 7;
    assert!(schedule::phase(&p, "2026-11-08").unwrap().is_some());
    assert!(schedule::phase(&p, "2026-11-09").unwrap().is_none());
    p.pattern = "once".into();
    assert!(schedule::phase(&p, "2026-11-02").unwrap().is_none());
}
#[test]
fn dst_uses_alarm_engine_gap_and_fold() {
    let mut a = Archive::default();
    let mut p = plan();
    p.pattern = "daily".into();
    p.start = "2026-01-01".into();
    p.minute = 150;
    p.zone = "America/New_York".into();
    p.fixed_zone = true;
    a.plans.push(p);
    let now = "2026-03-07T00:00:00Z"
        .parse::<jiff::Timestamp>()
        .unwrap()
        .as_second();
    let o = schedule::occurrences(&a, now, "UTC", 3).unwrap();
    let gap = o.iter().find(|o| o.day == "2026-03-08").unwrap();
    assert_eq!(
        jiff::Timestamp::from_second(gap.at).unwrap().to_string(),
        "2026-03-08T07:00:00Z"
    );
    a.plans[0].minute = 90;
    let now = "2026-10-31T00:00:00Z"
        .parse::<jiff::Timestamp>()
        .unwrap()
        .as_second();
    let o = schedule::occurrences(&a, now, "UTC", 3).unwrap();
    let fold = o
        .iter()
        .filter(|o| o.day == "2026-11-01")
        .collect::<Vec<_>>();
    assert_eq!(fold.len(), 1);
    assert_eq!(
        jiff::Timestamp::from_second(fold[0].at)
            .unwrap()
            .to_string(),
        "2026-11-01T05:30:00Z"
    );
}
#[test]
fn reminders_do_not_record_doses() {
    let mut a = Archive::default();
    a.plans.push(plan());
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert!(!d.today_doses.is_empty());
    assert!(a.doses.is_empty());
    assert_eq!(d.taken, 0);
    assert!(d.reminders.len() <= 56);
}
#[test]
fn dose_replay_and_conflict() {
    let mut a = Archive::default();
    let p = plan();
    let date = time::today(NOW, "UTC").unwrap();
    let d = Dose {
        id: schedule::dose_id(&p.id, &date).unwrap(),
        plan_id: p.id.clone(),
        day: date,
        scheduled: NOW,
        status: "taken".into(),
        recorded: NOW,
        snooze_until: None,
        notes: String::new(),
    };
    a.plans.push(p);
    let c=json!({"op":"dose","record":d,"id":null,"request_id":id(),"expected_revision":null,"created_at":NOW}).to_string();
    let s = serde_json::to_string(&a).unwrap();
    let first = bloom_apply(s, c.clone(), NOW, "UTC".into(), false).unwrap();
    let again = bloom_apply(first.clone(), c.clone(), NOW, "UTC".into(), false).unwrap();
    assert_eq!(first, again);
    let changed = c.replace("taken", "skipped");
    assert!(bloom_apply(first, changed, NOW, "UTC".into(), false).is_err());
}
#[test]
fn expired_can_correct_but_not_add() {
    let a = Archive::default();
    let d = day("2026-11-01", "light");
    assert!(apply(&a, "day", json!(d), false).is_err());
    let mut a = apply(&a, "day", json!(d), true).unwrap();
    a.days[0].notes = "corrected".into();
    assert!(apply(&a, "day", json!(a.days[0]), false).is_ok());
}
#[test]
fn stale_editor_cannot_overwrite() {
    let a = Archive::default();
    let c = json!({"op":"profile","record":a.profile,"id":null,"request_id":id(),"expected_revision":99,"created_at":NOW});
    assert!(
        bloom_apply(
            serde_json::to_string(&a).unwrap(),
            c.to_string(),
            NOW,
            "UTC".into(),
            true
        )
        .is_err()
    );
}
#[test]
fn duplicate_dates_and_orphan_doses_rejected() {
    let mut a = Archive::default();
    a.days = vec![day("2026-11-01", "light"), day("2026-11-01", "medium")];
    assert!(validate(&a).is_err());
}
#[test]
fn imported_plans_are_dormant_and_conflicts_atomic() {
    let a = Archive::default();
    let mut b = Archive::default();
    b.plans.push(plan());
    b.days.push(day("2026-11-01", "light"));
    let merged = bloom_merge(
        serde_json::to_string(&a).unwrap(),
        serde_json::to_string(&b).unwrap(),
    )
    .unwrap();
    let result: Archive = serde_json::from_str(&merged).unwrap();
    assert!(!result.plans[0].enabled);
    b.days[0].notes = "different".into();
    assert!(bloom_merge(merged, serde_json::to_string(&b).unwrap()).is_err());
}
#[test]
fn prediction_suppression_and_expired_estimate() {
    let mut a = Archive::default();
    for d in ["2026-08-01", "2026-08-29", "2026-09-26", "2026-10-24"] {
        a.days.push(day(d, "medium"));
    }
    a.profile.mode = "conceive".into();
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert_eq!(d.next_period.as_deref(), Some("2026-11-21"));
    assert!(d.fertile_start.is_some());
    a.plans.push(plan());
    assert!(
        insights::dashboard(&a, NOW, "UTC")
            .unwrap()
            .next_period
            .is_none()
    );
    a.plans.clear();
    assert!(
        insights::dashboard(&a, NOW + 60 * 86400, "UTC")
            .unwrap()
            .next_period
            .is_none()
    );
}
#[test]
fn reports_default_to_no_intimate_notes() {
    let mut a = Archive::default();
    let mut d = day("2026-11-01", "light");
    d.notes = "private secret".into();
    a.days.push(d);
    a.contacts.push(Contact {
        id: id(),
        day: "2026-11-01".into(),
        at: NOW,
        activity: "private activity".into(),
        protection: "none".into(),
        libido: None,
        orgasm: None,
        pain: None,
        notes: "contact secret".into(),
        tags: vec![],
    });
    let s = serde_json::to_string(&a).unwrap();
    let r = bloom_report(
        s.clone(),
        "2026-10-01".into(),
        "2026-11-30".into(),
        false,
        false,
        false,
    )
    .unwrap();
    assert!(!r.contains("secret") && !r.contains("private activity"));
    let csv = bloom_csv(s, false, false).unwrap();
    assert!(!csv.contains("secret") && !csv.contains("private activity"));
}
#[test]
fn authenticated_backup_round_trip_and_tamper() {
    let a = bloom_empty();
    let password = "a long backup passphrase".to_string();
    let encrypted = bloom_seal(a.clone(), password.clone()).unwrap();
    assert_eq!(bloom_open(encrypted.clone(), password.clone()).unwrap(), a);
    assert!(bloom_open(encrypted.clone(), "wrong password".into()).is_err());
    let mut damaged = encrypted;
    let last = damaged.len() - 1;
    damaged[last] ^= 1;
    assert!(bloom_open(damaged, password).is_err());
}
#[test]
fn bloom_fixture_is_valid() {
    let a = Archive::default();
    let s = serde_json::to_string(&a).unwrap();
    assert_eq!(bloom_validate(s.clone()).unwrap(), s);
}
#[test]
fn imported_appointments_never_enable_notifications() {
    let a = Archive::default();
    let mut b = Archive::default();
    b.appointments.push(Appointment {
        id: id(),
        title: "Private appointment".into(),
        at: NOW + 86400,
        remind_minutes: 60,
        notes: String::new(),
        kind: "Visit".into(),
    });
    let merged = bloom_merge(
        serde_json::to_string(&a).unwrap(),
        serde_json::to_string(&b).unwrap(),
    )
    .unwrap();
    let r: Archive = serde_json::from_str(&merged).unwrap();
    assert_eq!(r.appointments[0].remind_minutes, -1);
    assert!(schedule::reminders(&r, NOW, "UTC").unwrap().is_empty());
    assert!(bloom_merge(merged, serde_json::to_string(&b).unwrap()).is_ok());
}
#[test]
fn weekly_dose_rejects_unscheduled_day() {
    let mut p = plan();
    p.pattern = "weekly".into();
    p.weekdays = vec![1];
    assert!(schedule::phase(&p, "2026-11-15").unwrap().is_some());
    assert!(schedule::phase(&p, "2026-11-16").unwrap().is_none());
}
#[test]
fn pregnancy_can_be_recorded_late() {
    let mut a = Archive::default();
    a.pregnancies.push(Pregnancy {
        id: id(),
        start: "2026-11-01".into(),
        due: "2026-12-01".into(),
        ended: None,
        outcome: String::new(),
        notes: String::new(),
    });
    assert!(validate(&a).is_ok());
}
#[test]
fn large_stock_totals_do_not_overflow() {
    let mut a = Archive::default();
    let p = plan();
    for _ in 0..30_000 {
        a.refills.push(Refill {
            id: id(),
            plan_id: p.id.clone(),
            day: "2026-11-01".into(),
            quantity: 100_000,
        });
    }
    a.plans.push(p);
    assert_eq!(
        insights::dashboard(&a, NOW, "UTC").unwrap().stock[0].remaining,
        3_000_000_021
    );
}
#[test]
fn fixed_zone_dose_can_cross_the_travellers_date() {
    let now = "2026-11-16T16:00:00Z"
        .parse::<jiff::Timestamp>()
        .unwrap()
        .as_second();
    let mut a = Archive::default();
    let mut p = plan();
    p.fixed_zone = true;
    p.zone = "Asia/Tokyo".into();
    p.minute = 60;
    let d = Dose {
        id: schedule::dose_id(&p.id, "2026-11-17").unwrap(),
        plan_id: p.id.clone(),
        day: "2026-11-17".into(),
        scheduled: now,
        status: "taken".into(),
        recorded: now,
        snooze_until: None,
        notes: String::new(),
    };
    a.plans.push(p);
    let c = json!({"op":"dose", "record":d, "id":null, "request_id":id(), "expected_revision":0, "created_at":now});
    assert!(
        bloom_apply(
            serde_json::to_string(&a).unwrap(),
            c.to_string(),
            now,
            "America/Los_Angeles".into(),
            true
        )
        .is_ok()
    );
}

fn observation(kind: &str, label: &str, at: i64) -> Observation {
    Observation {
        id: id(),
        day: time::today(at, "UTC").unwrap(),
        at,
        zone: "UTC".into(),
        kind: kind.into(),
        label: label.into(),
        value: "".into(),
        level: Some(1),
        number: None,
        period_start: false,
        notes: "private event note".into(),
    }
}
#[test]
fn v1_migration_preserves_untimed_records_and_is_idempotent() {
    let mut a = Archive::default();
    a.days.push(day("2026-11-01", "heavy"));
    let mut v = json!(a);
    v["schema"] = json!(1);
    v.as_object_mut().unwrap().remove("observations");
    v.as_object_mut().unwrap().remove("exclusions");
    let migrated = bloom_validate(v.to_string()).unwrap();
    let b = model::decode(&migrated).unwrap();
    assert_eq!(b.schema, 2);
    assert_eq!(b.days, a.days);
    assert!(b.observations.is_empty());
    assert_eq!(migrated, bloom_validate(migrated.clone()).unwrap());
}
#[test]
fn timed_events_keep_all_values_but_count_a_symptom_day_once() {
    let a = Archive::default();
    let o = observation("symptom", "Focused", NOW - 3600);
    let a = apply(&a, "observation", json!(o), true).unwrap();
    let mut o = observation("symptom", "Focused", NOW - 1800);
    o.level = Some(3);
    let a = apply(&a, "observation", json!(o), true).unwrap();
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert_eq!(a.observations.len(), 2);
    assert_eq!(d.symptoms[0].count, 1);
    assert_eq!(d.symptoms[0].logged_days, 1);
    assert_eq!(d.symptoms[0].average, 3.0);
    let report = bloom_report(
        json!(a).to_string(),
        "2026-11-01".into(),
        "2026-11-30".into(),
        false,
        false,
        false,
    )
    .unwrap();
    assert_eq!(report.matches("symptom Focused").count(), 2);
    assert!(!report.contains("private event note"));
}
#[test]
fn timed_observations_validate_local_date_and_remain_correctable_after_expiry() {
    let mut o = observation("symptom", "Pain", NOW - 100);
    let a = apply(&Archive::default(), "observation", json!(o), true).unwrap();
    o.level = Some(0);
    assert!(apply(&a, "observation", json!(o), false).is_ok());
    o.id = id();
    assert!(apply(&a, "observation", json!(o), false).is_err());
    o.day = "2026-01-01".into();
    assert!(apply(&a, "observation", json!(o), true).is_err());
}
#[test]
fn pregnancy_gap_is_visible_but_never_used_in_estimates_after_mode_exit() {
    let mut a = Archive::default();
    for d in [
        "2025-12-01",
        "2026-01-01",
        "2026-09-01",
        "2026-09-29",
        "2026-10-27",
    ] {
        a.days.push(day(d, "medium"));
    }
    a.pregnancies.push(Pregnancy {
        id: id(),
        start: "2026-01-10".into(),
        due: "2026-10-01".into(),
        ended: Some("2026-08-20".into()),
        outcome: "".into(),
        notes: "".into(),
    });
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert!(d.cycles[1].length.unwrap() > 200);
    assert_eq!(
        d.cycles[1].excluded_reason.as_deref(),
        Some("Pregnancy interval")
    );
    assert_eq!(d.lengths, vec![31, 28, 28]);
    assert!(d.next_period.is_some());
}
#[test]
fn manual_exclusion_and_mode_intervals_preserve_observations() {
    let mut a = Archive::default();
    a.days.push(day("2026-11-01", "heavy"));
    let e = Exclusion {
        id: id(),
        start: "2026-10-01".into(),
        end: None,
        reason: "Missing records".into(),
        source: "manual".into(),
    };
    a = apply(&a, "exclusion", json!(e), false).unwrap();
    assert!(
        insights::dashboard(&a, NOW, "UTC")
            .unwrap()
            .next_period
            .is_none()
    );
    assert_eq!(a.days.len(), 1);
    let mut p = a.profile.clone();
    p.mode = "postpartum".into();
    a = apply(&a, "profile", json!(p), false).unwrap();
    p.mode = "cycle".into();
    a = apply(&a, "profile", json!(p), false).unwrap();
    assert!(
        a.exclusions
            .iter()
            .find(|e| e.source == "postpartum")
            .unwrap()
            .end
            .is_some()
    );
    assert!(
        a.exclusions
            .iter()
            .find(|e| e.source == "manual")
            .unwrap()
            .end
            .is_none()
    );
}
#[test]
fn timed_flow_contributes_without_duplicating_daily_summary() {
    let mut a = Archive::default();
    a.days.push(day("2026-11-16", "light"));
    let mut o = observation("flow", "", NOW - 100);
    o.value = "heavy".into();
    o.level = None;
    a = apply(&a, "observation", json!(o), true).unwrap();
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert_eq!(d.cycles.len(), 1);
    assert_eq!(d.cycles[0].bleeding_days, 1);
    assert_eq!(d.observed_days[0].flow, "heavy");
    assert_eq!(a.days[0].flow, "light");
}
#[test]
fn event_import_conflicts_and_backup_round_trip() {
    let mut a = Archive::default();
    a.observations
        .push(observation("symptom", "Calm", NOW - 10));
    let merged = bloom_merge(bloom_empty(), json!(a).to_string()).unwrap();
    let mut changed = a.clone();
    changed.observations[0].level = Some(3);
    assert!(bloom_merge(merged.clone(), json!(changed).to_string()).is_err());
    let backup = bloom_seal(merged.clone(), "a long test password".into()).unwrap();
    assert_eq!(
        merged,
        bloom_open(backup, "a long test password".into()).unwrap()
    );
}
#[test]
fn pausing_a_hormonal_plan_retains_a_bounded_history_interval() {
    let mut a = Archive::default();
    let p = plan();
    a.plans.push(p.clone());
    let mut paused = p;
    paused.enabled = false;
    a = apply(&a, "plan", json!(paused), false).unwrap();
    assert_eq!(a.plans[0].end, Some(time::today(NOW, "UTC").unwrap()));
    assert!(bloom_core::observations::exclusion(&a, "2026-11-01", "2026-11-16").is_some());
    assert!(bloom_core::observations::exclusion(&a, "2026-12-01", "2026-12-30").is_none());
}

#[test]
fn rolling_delivery_is_not_capped_by_apples_pending_queue_limit() {
    let mut a = Archive::default();
    for i in 0..70 {
        a.appointments.push(Appointment {
            id: id(),
            title: format!("Visit {i}"),
            at: NOW,
            remind_minutes: 0,
            notes: "".into(),
            kind: "Appointment".into(),
        });
    }
    assert_eq!(
        insights::dashboard(&a, NOW - 1, "UTC")
            .unwrap()
            .reminders
            .len(),
        56
    );
    let due: serde_json::Value = serde_json::from_str(
        &bloom_due_reminders(json!(a).to_string(), NOW - 1, NOW, "UTC".into()).unwrap(),
    )
    .unwrap();
    assert_eq!(due.as_array().unwrap().len(), 70);
}
#[test]
fn doses_that_follow_the_phone_are_wall_clock_times() {
    let mut a = Archive::default();
    a.plans.push(plan());
    let r = schedule::reminders(&a, NOW, "UTC").unwrap();
    assert!(
        !r.is_empty() && r.iter().all(|r| r.floating),
        "a plan that follows the phone floats"
    );
    a.plans[0].fixed_zone = true;
    assert!(
        schedule::reminders(&a, NOW, "UTC")
            .unwrap()
            .iter()
            .all(|r| !r.floating),
        "a fixed-zone plan is an instant"
    );
    a.plans[0].fixed_zone = false;
    let first = &schedule::reminders(&a, NOW, "UTC").unwrap()[0];
    let o = first.dose.clone().unwrap();
    a.doses.push(Dose {
        id: o.id.clone(),
        plan_id: o.plan_id.clone(),
        day: o.day.clone(),
        scheduled: o.at,
        status: "snoozed".into(),
        recorded: NOW,
        snooze_until: Some(o.at + 600),
        notes: String::new(),
    });
    let snoozed = schedule::reminders(&a, NOW, "UTC").unwrap();
    let again = snoozed.iter().find(|r| r.id == first.id).unwrap();
    assert!(!again.floating, "a snooze is an instant");
    // Old payloads without the field still decode.
    let old: schedule::Reminder =
        serde_json::from_str(r#"{"id":"bloom.x","at":1,"title":"t","body":"b","dose":null}"#)
            .unwrap();
    assert!(!old.floating);
}
#[test]
fn discreet_period_reminder_hides_its_reason() {
    let mut a = Archive::default();
    for d in ["2026-08-01", "2026-08-29", "2026-09-26", "2026-10-24"] {
        a.days.push(day(d, "medium"));
    }
    a.profile.period_reminder = true;
    a.profile.discreet = false;
    let period = |a: &Archive| {
        insights::dashboard(a, NOW, "UTC")
            .unwrap()
            .reminders
            .into_iter()
            .find(|r| r.id == "bloom.period")
            .expect("period reminder")
    };
    let open = period(&a);
    assert_eq!(open.title, "Period estimate");
    assert!(open.body.contains("period"));
    a.profile.discreet = true;
    let hidden = period(&a);
    assert_eq!(hidden.title, "Your reminder");
    assert!(!hidden.body.to_lowercase().contains("period"));
    assert!(!hidden.body.to_lowercase().contains("estimate"));
}
#[test]
fn future_records_are_rejected_with_their_own_reason() {
    let mut a = Archive::default();
    let p = plan();
    let tomorrow = time::add(&time::today(NOW, "UTC").unwrap(), 1).unwrap();
    let d = Dose {
        id: schedule::dose_id(&p.id, &tomorrow).unwrap(),
        plan_id: p.id.clone(),
        day: tomorrow.clone(),
        scheduled: NOW + 86400 + 8 * 3600,
        status: "taken".into(),
        recorded: NOW,
        snooze_until: None,
        notes: String::new(),
    };
    a.plans.push(p);
    let reason = |r: Result<Archive>| match r {
        Err(Error::Invalid { reason }) => reason,
        Ok(_) => panic!("future record accepted"),
    };
    let dose = reason(apply(&a, "dose", json!(d), true));
    assert!(dose.contains("dose"), "{dose}");
    assert!(!dose.contains("Observations"), "{dose}");
    let mut day_log = day(&tomorrow, "light");
    day_log.period_start = false;
    assert!(reason(apply(&a, "day", json!(day_log), true)).contains("Observations"));
}
#[test]
fn temperatures_convert_for_display_and_entry() {
    assert_eq!(bloom_temperature_display(36.55, false), 36.55);
    assert_eq!(bloom_temperature_display(37.0, true), 98.6);
    assert_eq!(bloom_temperature_display(36.5, true), 97.7);
    assert_eq!(bloom_temperature_display(36.444, true), 97.6);
    assert_eq!(bloom_temperature_celsius(98.6, true).unwrap(), 37.0);
    assert_eq!(bloom_temperature_celsius(97.9, true).unwrap(), 36.611);
    assert_eq!(bloom_temperature_celsius(36.42, false).unwrap(), 36.42);
    // Entries with up to two decimals survive the round trip through stored Celsius.
    for hundredths in 9_000..=9_999 {
        let f = hundredths as f64 / 100.0;
        let c = bloom_temperature_celsius(f, true).unwrap();
        assert_eq!(bloom_temperature_display(c, true), f, "{f} °F");
    }
    assert_eq!(bloom_temperature_celsius(86.0, true).unwrap(), 30.0);
    assert_eq!(bloom_temperature_celsius(113.0, true).unwrap(), 45.0);
    assert!(bloom_temperature_celsius(85.9, true).is_err());
    assert!(bloom_temperature_celsius(37.0, true).is_err()); // A Celsius value typed as °F.
    assert!(bloom_temperature_celsius(98.6, false).is_err());
    assert!(bloom_temperature_celsius(f64::NAN, false).is_err());
}
#[test]
fn a_start_nine_days_after_the_last_joins_that_period() {
    let mut a = Archive::default();
    for d in ["2026-09-01", "2026-09-29", "2026-10-27", "2026-11-05"] {
        a.days.push(day(d, "medium"));
    }
    let d = insights::dashboard(&a, NOW, "UTC").unwrap();
    assert_eq!(d.cycles.len(), 3);
    assert_eq!(d.lengths, vec![28, 28]);
    let last = d.cycles.last().unwrap();
    assert_eq!(last.start, "2026-10-27");
    assert_eq!(last.joined, vec!["2026-11-05".to_string()]);
    assert_eq!(last.bleeding_days, 2);
    // The early start no longer produces a 9-day cycle that blanks the estimate.
    assert_eq!(d.next_period.as_deref(), Some("2026-11-24"));
    // The stored record stays as entered.
    assert!(
        a.days
            .iter()
            .find(|x| x.day == "2026-11-05")
            .unwrap()
            .period_start
    );
}
#[test]
fn a_start_ten_days_after_the_last_begins_a_cycle() {
    let mut a = Archive::default();
    for d in ["2026-10-27", "2026-11-06"] {
        a.days.push(day(d, "medium"));
    }
    let cycles = insights::cycles(&a).unwrap();
    assert_eq!(cycles.len(), 2);
    assert_eq!(cycles[0].length, Some(10));
    assert!(cycles.iter().all(|c| c.joined.is_empty()));
}
#[test]
fn a_timed_start_joins_and_flow_after_it_continues_the_period() {
    let mut a = Archive::default();
    a.days.push(day("2026-11-01", "light"));
    let mut o = observation("flow", "", NOW - 9 * 86400); // 2026-11-08
    o.value = "medium".into();
    o.level = None;
    o.period_start = true;
    a = apply(&a, "observation", json!(o), true).unwrap();
    let mut next = day("2026-11-09", "light");
    next.period_start = false;
    a.days.push(next);
    let cycles = insights::cycles(&a).unwrap();
    assert_eq!(cycles.len(), 1);
    assert_eq!(cycles[0].joined, vec!["2026-11-08".to_string()]);
    assert_eq!(cycles[0].bleeding_days, 3);
}
#[test]
fn moving_an_earlier_start_separates_or_joins_a_later_one() {
    let mut a = Archive::default();
    a.days.push(day("2026-10-01", "medium"));
    a.days.push(day("2026-10-25", "medium"));
    a.days.push(day("2026-11-03", "medium"));
    let cycles = insights::cycles(&a).unwrap();
    assert_eq!(cycles.len(), 2);
    assert_eq!(cycles[1].joined, vec!["2026-11-03".to_string()]);
    // Correcting the second start a day earlier leaves the third start ten days later: a cycle.
    let mut moved = a.clone();
    moved.days[1].day = "2026-10-24".into();
    let cycles = insights::cycles(&moved).unwrap();
    assert_eq!(cycles.len(), 3);
    assert_eq!(cycles[1].length, Some(10));
    assert!(cycles.iter().all(|c| c.joined.is_empty()));
    // Moving the third start closer joins it again; removing the second makes it a cycle start.
    let mut moved = a.clone();
    moved.days[2].day = "2026-10-30".into();
    assert_eq!(
        insights::cycles(&moved).unwrap()[1].joined,
        vec!["2026-10-30".to_string()]
    );
    let mut removed = a.clone();
    removed.days.remove(1);
    let cycles = insights::cycles(&removed).unwrap();
    assert_eq!(cycles.len(), 2);
    assert_eq!(cycles[0].length, Some(33));
    assert!(cycles.iter().all(|c| c.joined.is_empty()));
}
#[test]
fn reports_print_the_shown_unit_and_csv_stays_celsius() {
    let mut a = Archive::default();
    let mut d = day("2026-11-10", "none");
    d.bbt = Some(36.5);
    a.days.push(d);
    let mut o = observation("temperature", "", NOW - 3600);
    o.number = Some(37.0);
    o.level = None;
    a.observations.push(o);
    let s = serde_json::to_string(&a).unwrap();
    let report = |fahrenheit| {
        bloom_report(
            s.clone(),
            "2026-11-01".into(),
            "2026-11-30".into(),
            false,
            false,
            fahrenheit,
        )
        .unwrap()
    };
    let f = report(true);
    assert!(f.contains("BBT: 97.70 °F"), "{f}");
    assert!(f.contains("measured 98.60 °F"), "{f}");
    assert!(!f.contains("°C"));
    let c = report(false);
    assert!(c.contains("BBT: 36.50 °C") && c.contains("measured 37.00 °C"));
    assert!(!c.contains("°F"));
    let csv = bloom_csv(s, false, false).unwrap();
    let mut lines = csv.lines();
    assert_eq!(
        lines.next(),
        Some("type,id,date_or_time,details,temperature_c,notes")
    );
    let rows: Vec<_> = lines.collect();
    assert!(
        rows[0].starts_with("\"day\"") && rows[0].ends_with(",\"36.5\",\"\""),
        "{}",
        rows[0]
    );
    assert!(rows[1].starts_with("\"observation\"") && rows[1].ends_with(",\"37\",\"\""));
}
#[test]
fn reports_list_a_joined_start_under_its_period() {
    let mut a = Archive::default();
    a.days.push(day("2026-11-01", "medium"));
    a.days.push(day("2026-11-07", "light"));
    let r = bloom_report(
        json!(a).to_string(),
        "2026-11-01".into(),
        "2026-11-30".into(),
        false,
        false,
        false,
    )
    .unwrap();
    assert!(r.contains("2026-11-01: 2 logged bleeding days"), "{r}");
    assert!(r.contains("2026-11-07: marked start counted as part of this period"));
}
