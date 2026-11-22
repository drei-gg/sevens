use cleaner_core::*;
fn photo(id: &str) -> CleanupItem {
    let mut item = new_cleanup_item(id.into(), id.into(), MediaKind::Photo);
    item.revision = "r1".into();
    item
}
#[test]
fn review_plan_preserves_snapshots_and_rejects_whole_invalid_selection() {
    let a = photo("a");
    let b = photo("b");
    let items = vec![a.clone(), b.clone()];
    let state = new_review_state();
    assert_eq!(
        compression_review_plan(vec!["b".into(), "a".into()], &items, &state).unwrap(),
        vec![b, a]
    );
    for ids in [
        vec![],
        vec!["a".into(), "a".into()],
        vec!["a".into(), "missing".into()],
        vec!["a".into(); 11],
    ] {
        assert!(compression_review_plan(ids, &items, &state).is_err());
    }
    let many: Vec<_> = (0..11).map(|i| photo(&format!("photo-{i}"))).collect();
    assert!(
        compression_review_plan(
            many[..10].iter().map(|p| p.id.clone()).collect(),
            &many,
            &state
        )
        .is_ok()
    );
    assert!(
        compression_review_plan(many.iter().map(|p| p.id.clone()).collect(), &many, &state)
            .is_err()
    );
    assert!(
        compression_review_plan(
            vec!["a".into()],
            &[items[0].clone(), items[0].clone()],
            &state
        )
        .is_err()
    );
    let mut video = photo("v");
    video.kind = MediaKind::Video;
    assert!(
        compression_review_plan(
            vec!["a".into(), "v".into()],
            &[items[0].clone(), video],
            &state
        )
        .is_err()
    );
}
#[test]
fn recorded_copy_is_atomic_idempotent_and_blocks_source_and_output() {
    let a = photo("a");
    let original = new_review_state();
    let next = review_state_record_copy(
        &original,
        &a,
        "copy".into(),
        new_history_entry("Saved".into()),
    )
    .unwrap();
    assert_eq!(next.processed_sources, vec!["a|r1", "output:copy"]);
    assert_eq!(next.history.len(), 1);
    assert_eq!(
        next,
        review_state_record_copy(
            &next,
            &a,
            "copy".into(),
            new_history_entry("Repeated".into())
        )
        .unwrap()
    );
    assert!(compression_review_plan(vec!["a".into()], &[a.clone()], &next).is_err());
    assert!(compression_review_plan(vec!["copy".into()], &[photo("copy")], &next).is_err());
    let mut changed = a.clone();
    changed.revision = "r2".into();
    assert!(compression_review_plan(vec!["a".into()], &[changed], &next).is_ok());
    assert!(original.processed_sources.is_empty());
    let encoded = review_state_encode(&next).unwrap();
    let decoded = review_state_decode(&encoded).unwrap();
    assert_eq!(decoded.processed_sources, next.processed_sources);
    assert_eq!(decoded.history[0].id, next.history[0].id);
    assert_eq!(decoded.history[0].message, "Saved");
    for id in ["", "a"] {
        assert!(
            review_state_record_copy(&original, &a, id.into(), new_history_entry("Bad".into()))
                .is_err()
        );
    }
}
