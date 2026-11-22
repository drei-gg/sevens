use bloom_core::*;
use serde_json::{json, Value};
fn enabled() -> Value {
    let mut a: Value = serde_json::from_str(&bloom_empty()).unwrap();
    a["profile"]["watch_enabled"] = json!(true);
    a
}
const NOW: i64 = 1_794_960_000;
#[test]
fn watch_opt_in_is_private_and_disabled_projection_is_empty() {
    let a = bloom_empty();
    let snapshot: Value =
        serde_json::from_str(&bloom_watch_snapshot(a.clone(), NOW, "UTC".into(), true).unwrap())
            .unwrap();
    assert_eq!(snapshot["enabled"], false);
    assert_eq!(snapshot["can_add"], false);
    assert_eq!(snapshot["doses"], json!([]));
    assert!(snapshot["cycle_day"].is_null());
    assert!(snapshot.get("contacts").is_none());
    assert!(snapshot.get("notes").is_none());
    assert!(bloom_watch_apply(
        a,
        json!({"version":1,"id":uuid::Uuid::new_v4().to_string()}).to_string(),
        NOW,
        "UTC".into(),
        true
    )
    .is_err());
}
#[test]
fn watch_rejects_unsupported_commands_and_versions() {
    let a = enabled();
    for op in ["erase", "profile", "plan", "contact", "refill"] {
        let request = json!({"version":1,"id":uuid::Uuid::new_v4().to_string(),"command":{"op":op,"request_id":uuid::Uuid::new_v4().to_string(),"created_at":NOW,"record":{}}});
        assert!(
            bloom_watch_apply(a.to_string(), request.to_string(), NOW, "UTC".into(), true).is_err()
        );
    }
    assert!(bloom_watch_apply(
        a.to_string(),
        json!({"version":2,"id":uuid::Uuid::new_v4().to_string()}).to_string(),
        NOW,
        "UTC".into(),
        true
    )
    .is_err());
    assert!(
        bloom_watch_apply(a.to_string(), "x".repeat(512_001), NOW, "UTC".into(), true).is_err()
    );
}
#[test]
fn watch_refresh_does_not_write_a_receipt_or_advance_revision() {
    let a = enabled();
    let refreshed = bloom_watch_apply(
        a.to_string(),
        json!({"version":1,"id":uuid::Uuid::new_v4().to_string()}).to_string(),
        NOW,
        "UTC".into(),
        false,
    )
    .unwrap();
    assert_eq!(serde_json::from_str::<Value>(&refreshed).unwrap(), a);
}

#[test]
fn watch_projection_redacts_and_dose_retry_is_idempotent_after_expiry() {
    let mut a = enabled();
    let plan_id = uuid::Uuid::new_v4().to_string();
    a["plans"] = json!([{"id":plan_id,"name":"Private medication","method":"pill","hormonal":true,"start":"2026-11-01","end":null,"minute":480,"zone":"UTC","fixed_zone":false,"pattern":"daily","active_days":21,"break_days":7,"every_days":1,"weekdays":[1,2,3,4,5,6,7],"remind_break":false,"enabled":true,"initial_stock":21,"notes":"Never transmit private notes"}]);
    a["profile"]["discreet"] = json!(true);
    let projection = bloom_watch_snapshot(a.to_string(), NOW, "UTC".into(), false).unwrap();
    assert!(!projection.contains("Private medication"));
    assert!(!projection.contains("Never transmit"));
    let snapshot: Value = serde_json::from_str(&projection).unwrap();
    assert!(snapshot["cycle_day"].is_null());
    assert!(!snapshot["can_add"].as_bool().unwrap());
    let dose = &snapshot["doses"][0];
    assert_eq!(dose["title"], "Your reminder");
    let id = uuid::Uuid::new_v4().to_string();
    let request = json!({"version":1,"id":id,"command":{"op":"dose","request_id":id,"expected_revision":0,"created_at":NOW,"record":{"id":dose["id"],"plan_id":dose["plan_id"],"day":dose["day"],"scheduled":dose["at"],"status":"taken","recorded":NOW,"snooze_until":null,"notes":""}}});
    let saved =
        bloom_watch_apply(a.to_string(), request.to_string(), NOW, "UTC".into(), false).unwrap();
    let again =
        bloom_watch_apply(saved.clone(), request.to_string(), NOW, "UTC".into(), false).unwrap();
    assert_eq!(saved, again);
    let archive: Value = serde_json::from_str(&saved).unwrap();
    assert_eq!(archive["doses"].as_array().unwrap().len(), 1);
    assert_eq!(archive["doses"][0]["status"], "taken");
    let mut stale = request.clone();
    stale["id"] = json!(uuid::Uuid::new_v4().to_string());
    stale["command"]["request_id"] = stale["id"].clone();
    stale["command"]["record"]["status"] = json!("skipped");
    assert!(bloom_watch_apply(saved.clone(), stale.to_string(), NOW, "UTC".into(), false).is_err());
    let mut off = archive;
    off["profile"]["watch_enabled"] = json!(false);
    let private = bloom_watch_snapshot(off.to_string(), NOW + 1, "UTC".into(), false).unwrap();
    assert!(!private.contains("Your reminder"));
    assert!(bloom_watch_apply(
        off.to_string(),
        request.to_string(),
        NOW + 1,
        "UTC".into(),
        false
    )
    .is_err());
}
