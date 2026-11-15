//! Every CleanerCore test from the iOS app (Kit/Tests/CleanerCoreTests/PreservationTests.swift),
//! ported one to one, so the crate is held to the behaviour of the Swift implementation. Names
//! match the Swift test functions. The two store tests write and read a real file here; the
//! platforms keep their own tests for the atomic write itself.

use cleaner_core::*;
use std::collections::HashMap;

fn person(id: &str, phones: &[&str], emails: &[&str]) -> ContactRecord {
    new_contact_record(
        id.into(),
        id.into(),
        "local".into(),
        vec![new_contact_field("givenName".into(), id.into(), String::new())],
        phones.iter().map(|s| s.to_string()).collect(),
        emails.iter().map(|s| s.to_string()).collect(),
    )
}
fn field(key: &str, value: &str, label: &str) -> ContactField {
    new_contact_field(key.into(), value.into(), label.into())
}
fn item(id: &str) -> CleanupItem {
    new_cleanup_item(id.into(), id.into(), MediaKind::Photo)
}
fn decisions(pairs: &[(&str, ReviewDecision)]) -> HashMap<String, ReviewDecision> {
    pairs.iter().map(|(k, v)| (k.to_string(), *v)).collect()
}
fn strings(values: &[&str]) -> Vec<String> {
    values.iter().map(|s| s.to_string()).collect()
}

/// `ReviewStore(url:)` on disk: what the platform store does around the core's codec.
struct Store(std::path::PathBuf);
impl Store {
    fn read(&self) -> Result<ReviewState, CleanerError> {
        match std::fs::read(&self.0) {
            Ok(bytes) => review_state_decode(&bytes),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(new_review_state()),
            Err(_) => Err(CleanerError::Unreadable),
        }
    }
    fn decide(&self, d: HashMap<String, ReviewDecision>, r: HashMap<String, String>) -> Result<ReviewState, CleanerError> {
        let state = review_state_decide(&self.read()?, d, r);
        std::fs::create_dir_all(self.0.parent().unwrap()).unwrap();
        let temp = self.0.with_extension("tmp");
        std::fs::write(&temp, review_state_encode(&state)?).unwrap();
        std::fs::rename(&temp, &self.0).unwrap();
        Ok(state)
    }
}
fn temp_path(name: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("cleaner-core-{}-{}", std::process::id(), name))
}

#[test]
fn smaller_is_not_enough() {
    let before = new_image_facts(4000, 3000, 8_000_000);
    let after = new_image_facts(4000, 3000, 1_000_000);
    let bad = new_compression_assessment(before.clone(), after.clone(), 24.0, 12.0, false);
    assert!(!assessment_can_offer_copy(&bad));
    assert!(!assessment_authorizes_source_deletion(&bad));
    let good = new_compression_assessment(before, after, 45.0, 36.0, false);
    assert!(assessment_can_offer_copy(&good));
    assert!(!assessment_authorizes_source_deletion(&good));
}

#[test]
fn local_damage_cannot_hide_in_global_average() {
    let facts = new_image_facts(100, 100, 100_000);
    let output = ImageFacts { bytes: 10_000, ..facts.clone() };
    assert!(!assessment_can_offer_copy(&new_compression_assessment(facts, output, 46.0, 20.0, false)));
}

#[test]
fn transformations_require_disclosure() {
    let source = ImageFacts { orientation: 6, color_space: "P3".into(), has_alpha: true, bits: 10, ..new_image_facts(100, 100, 100_000) };
    let output = new_image_facts(50, 50, 10_000);
    let result = new_compression_assessment(source, output, 50.0, 50.0, false);
    assert_eq!(result.issues.len(), 5);
}

#[test]
fn invalid_metric_and_no_savings_fail() {
    let a = new_image_facts(100, 100, 9000);
    assert!(!assessment_can_offer_copy(&new_compression_assessment(a.clone(), a, f64::NAN, 50.0, false)));
}

#[test]
fn household_and_company_matches_do_not_become_transitive_merge_groups() {
    let a = person("A", &["+12025550100"], &[]);
    let b = person("B", &["+12025550100"], &["b@example.com"]);
    let c = person("C", &[], &["b@example.com"]);
    let matches = contact_candidates(&[a, b, c]);
    assert_eq!(matches.len(), 2);
    assert!(!matches.iter().any(|m| {
        let mut pair = [m.left.as_str(), m.right.as_str()];
        pair.sort();
        pair == ["A", "C"]
    }));
}

#[test]
fn unavailable_notes_block_destructive_merge_even_with_verified_archive() {
    let plan = new_contact_merge_plan(&[person("A", &[], &[]), person("B", &[], &[])], true, true);
    assert!(!merge_plan_can_delete_sources(&plan));
    assert!(!plan.blockers.is_empty());
    assert_eq!(plan.conflicts, strings(&["givenName"]));
}

#[test]
fn labels_and_multiple_values_are_preserved() {
    let mut a = person("A", &[], &[]);
    let mut b = person("B", &[], &[]);
    a.fields = vec![field("phone", "123", "home")];
    b.fields = vec![field("phone", "123", "work"), field("email", "b@example.com", "")];
    let plan = new_contact_merge_plan(&[a, b], true, true);
    assert_eq!(plan.fields.len(), 3);
    assert!(plan.conflicts.is_empty());
}

#[test]
fn account_and_stale_source_blockers_are_independent() {
    let mut a = person("A", &[], &[]);
    let mut b = person("B", &[], &[]);
    a.inaccessible_fields = vec![];
    b.inaccessible_fields = vec![];
    b.container = "work".into();
    let plan = new_contact_merge_plan(&[a, b], false, false);
    assert_eq!(plan.blockers.len(), 3);
}

#[test]
fn unknown_bytes_are_not_zero() {
    let value = storage_summary(&[item("a"), CleanupItem { bytes: Some(42), ..item("b") }]);
    assert_eq!(value.known_bytes, 42);
    assert_eq!(value.unknown_count, 1);
}

#[test]
fn smart_selection_respects_favorites_keep_and_later() {
    let items: Vec<CleanupItem> = (0..5)
        .map(|i| CleanupItem {
            name: "x".into(),
            favorite: i == 0,
            fingerprint: Some("identical".into()),
            ..item(&i.to_string())
        })
        .collect();
    let d = decisions(&[("1", ReviewDecision::Keep), ("2", ReviewDecision::Later), ("3", ReviewDecision::Remove)]);
    let proposed = matching_proposed_removal(&matching_exact(&items), &items, &d);
    assert_eq!(proposed, strings(&["4"]));
}

#[test]
fn similarity_never_preselects() {
    let items = [item("a"), item("b")];
    let group = MatchGroup { id: "s".into(), kind: MatchKind::Similar, members: strings(&["a", "b"]), reason: "similar".into() };
    assert!(matching_proposed_removal(&[group], &items, &HashMap::new()).is_empty());
}

#[test]
fn durable_decisions_survive_restart_and_stale_deletion_fails() {
    let folder = temp_path("durable");
    let url = folder.join("review.json");
    let store = Store(url.clone());
    store
        .decide(decisions(&[("a", ReviewDecision::Remove)]), HashMap::from([("a".to_string(), "v1".to_string())]))
        .unwrap();
    let reopened = Store(url).read().unwrap();
    assert_eq!(reopened.decisions.get("a"), Some(&ReviewDecision::Remove));
    let stale = CleanupItem { revision: "v2".into(), ..item("a") };
    assert!(matches!(validate_removal(strings(&["a"]), &[stale], &reopened), Err(CleanerError::Blocked { .. })));
    let current = CleanupItem { revision: "v1".into(), ..item("a") };
    validate_removal(strings(&["a"]), &[current], &reopened).unwrap();
    std::fs::remove_dir_all(folder).unwrap();
}

#[test]
fn corrupt_ledger_cannot_silently_reset() {
    let url = temp_path("corrupt");
    std::fs::write(&url, b"bad").unwrap();
    assert!(Store(url.clone()).read().is_err());
    std::fs::remove_file(url).unwrap();
}

#[test]
fn smart_selection_never_queues_the_last_unqueued_keeper() {
    let items = [
        CleanupItem { fingerprint: Some("same".into()), ..item("a") },
        CleanupItem { fingerprint: Some("same".into()), ..item("b") },
    ];
    let d = decisions(&[("a", ReviewDecision::Remove)]);
    assert!(matching_proposed_removal(&matching_exact(&items), &items, &d).is_empty());
}

#[test]
fn unnormalized_national_number_is_not_missing() {
    let record = new_contact_record("a".into(), "A".into(), "local".into(), vec![field("phone", "020 7946 0018", "")], vec![], vec![]);
    assert!(!contact_is_incomplete(&record));
}
