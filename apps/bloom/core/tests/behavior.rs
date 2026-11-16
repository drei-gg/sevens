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
    a.schema = 2;
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
