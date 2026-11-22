use vault_core::*;
fn note(name: &str, bytes: i64) -> VaultItem {
    new_vault_item(
        name.into(),
        "public.plain-text".into(),
        bytes,
        "a".repeat(64),
        None,
        0,
    )
}
#[test]
fn searches_body_with_shared_unicode_rules_and_strict_utf8() {
    assert!(
        note_search_matches(
            "Café STRAẞE\nsecret invoice".as_bytes().to_vec(),
            "cafe strasse".into()
        )
        .unwrap()
    );
    assert!(note_search_matches(b"Hidden phrase".to_vec(), "hidden".into()).unwrap());
    assert!(!note_search_matches(b"Hidden phrase".to_vec(), "   ".into()).unwrap());
    assert!(note_search_matches(vec![255], "x".into()).is_err());
    assert!(note_search_matches(vec![0; 5_000_001], "x".into()).is_err());
}
#[test]
fn scope_respects_trash_favorites_and_text_type() {
    let a = note("a", 20);
    let mut b = note("b", 20);
    b.favorite = true;
    let mut c = note("c", 20);
    c.deleted = Some(std::time::SystemTime::now());
    let mut d = note("d", 20);
    d.content_type = "public.pdf".into();
    let items = vec![a.clone(), b.clone(), c.clone(), d];
    assert_eq!(
        note_search_plan(items.clone(), "text".into(), false, false).ids,
        vec![a.id, b.id]
    );
    assert_eq!(
        note_search_plan(items.clone(), "text".into(), false, true).ids,
        vec![b.id]
    );
    assert_eq!(
        note_search_plan(items, "text".into(), true, false).ids,
        vec![c.id]
    );
}
#[test]
fn bounded_plan_reports_omissions_and_rejects_long_queries() {
    let small: Vec<_> = (0..502).map(|_| note("a", 1)).collect();
    let plan = note_search_plan(small.clone(), "text".into(), false, false);
    assert_eq!(plan.ids.len(), 500);
    assert_eq!(plan.omitted, 2);
    assert!(
        note_search_plan(small, "a".repeat(201), false, false)
            .issue
            .is_some()
    );
    let big: Vec<_> = (0..10).map(|_| note("b", 4_000_000)).collect();
    let plan = note_search_plan(big, "text".into(), false, false);
    assert_eq!(plan.ids.len(), 8);
    assert_eq!(plan.omitted, 2);
    assert!(
        note_search_plan(vec![note("a", 1)], "".into(), false, false)
            .ids
            .is_empty()
    );
}
