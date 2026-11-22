use bloom_core::model::*;
use bloom_core::*;
use serde_json::{Value, json};

fn encode<T: serde::Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap()
}

#[test]
fn unrepresentable_health_revision_is_rejected_without_overflow() {
    let mut archive = reviewed();
    archive.revision = u64::MAX;
    assert!(bloom_health_export(encode(&archive)).is_err());
}
fn samples() -> String {
    json!([
        {"day":"2026-11-17","flow":"light","period_start":true},
        {"day":"2026-11-17","flow":"heavy","period_start":false},
        {"day":"2026-11-16","flow":"medium","period_start":false}
    ])
    .to_string()
}
fn reviewed() -> Archive {
    serde_json::from_str(&bloom_health_review(bloom_empty(), samples()).unwrap()).unwrap()
}

#[test]
fn review_is_deterministic_and_restricts_private_fields() {
    let a = reviewed();
    assert_eq!(a.days.len(), 2);
    assert_eq!(a.days[1].flow, "heavy");
    assert!(a.days[1].period_start);
    assert!(
        a.days
            .iter()
            .all(|d| d.notes.is_empty() && d.symptoms.is_empty())
    );
    let mut reverse: Vec<Value> = serde_json::from_str(&samples()).unwrap();
    reverse.reverse();
    assert_eq!(
        encode(&a),
        bloom_health_review(bloom_empty(), encode(&reverse)).unwrap()
    );
    for invalid in [
        json!([{"day":"2026-11-17","flow":"spotting","period_start":false}]),
        json!([{"day":"invalid","flow":"light","period_start":false}]),
        json!([{"day":"2026-11-17","flow":"light","period_start":false,"notes":"secret"}]),
    ] {
        assert!(bloom_health_review(bloom_empty(), invalid.to_string()).is_err());
    }
}

#[test]
fn commit_rechecks_new_journal_entries_and_reimport_is_noop() {
    let incoming = reviewed();
    let mut current = Archive::default();
    let mut local = incoming.days[0].clone();
    local.flow = "none".into();
    local.notes = "Keep this local edit".into();
    current.days.push(local.clone());
    let merged: Archive =
        serde_json::from_str(&bloom_health_import(encode(&current), encode(&incoming)).unwrap())
            .unwrap();
    assert_eq!(merged.days.len(), 2);
    assert_eq!(merged.days[0], local);
    assert_eq!(
        bloom_health_import(encode(&merged), encode(&incoming)).unwrap(),
        encode(&merged)
    );
    assert_eq!(merged.revision, 1);
}

#[test]
fn timed_observations_own_import_dates_and_keep_stable_export_ids() {
    let mut current = Archive::default();
    let observation = Observation {
        id: uuid::Uuid::new_v4().to_string(),
        day: "2026-11-17".into(),
        at: 1794873600,
        zone: "UTC".into(),
        kind: "flow".into(),
        label: "".into(),
        value: "medium".into(),
        level: None,
        number: None,
        period_start: true,
        notes: "Must not leave journal".into(),
    };
    current.observations.push(observation.clone());
    let preview: Archive =
        serde_json::from_str(&bloom_health_review(encode(&current), samples()).unwrap()).unwrap();
    assert_eq!(preview.days.len(), 1);
    assert_eq!(preview.days[0].day, "2026-11-16");
    let first: Value =
        serde_json::from_str(&bloom_health_export(encode(&current)).unwrap()).unwrap();
    assert_eq!(first[0]["at"], observation.at);
    assert_eq!(
        first[0]["sync_id"],
        format!("bloom.observation.{}", observation.id)
    );
    assert!(first[0].get("notes").is_none());
    current.days = reviewed().days;
    current.days[0].notes = "Also private".into();
    let exported = bloom_health_export(encode(&current)).unwrap();
    let rows: Vec<Value> = serde_json::from_str(&exported).unwrap();
    assert!(rows.iter().any(|r| r == &first[0]));
    assert_eq!(
        rows[0]["sync_id"],
        format!("bloom.flow.{}", current.days[0].id)
    );
    assert!(!exported.contains("private"));
    assert!(!exported.contains("journal"));
}
