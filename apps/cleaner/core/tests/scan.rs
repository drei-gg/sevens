use cleaner_core::*;
fn measured() -> CleanupItem {
    let mut item = new_cleanup_item("asset-one".into(), "Original name".into(), MediaKind::Photo);
    item.revision = "1800000000.0:4000x3000:1".into();
    item.fingerprint = Some("1:image/jpeg:hash".into());
    item.bytes = Some(1000);
    item
}
#[test]
fn checkpoints_reuse_only_measurements_and_preserve_current_metadata() {
    let original = measured();
    let checkpoint = scan_checkpoint(&original).unwrap().unwrap();
    assert!(!checkpoint.contains("Original name"));
    let mut current = original.clone();
    current.name = "Current name".into();
    current.favorite = true;
    current.fingerprint = None;
    current.bytes = None;
    let restored = scan_restore(&current, checkpoint.clone()).unwrap();
    assert_eq!(restored.fingerprint, original.fingerprint);
    assert_eq!(restored.bytes, Some(1000));
    assert!(restored.favorite);
    assert_eq!(restored.name, "Current name");
    current.revision = "changed".into();
    assert_eq!(scan_restore(&current, checkpoint.clone()).unwrap(), current);
    current.revision = original.revision;
    current.id = "another asset".into();
    assert_eq!(scan_restore(&current, checkpoint).unwrap(), current);
}
#[test]
fn missing_or_unversioned_measurements_are_not_completed_checkpoints() {
    let mut item = measured();
    item.fingerprint = None;
    assert!(scan_checkpoint(&item).unwrap().is_none());
    item = measured();
    item.bytes = None;
    assert!(!scan_has_measurement(&item));
    item = measured();
    item.revision = "0.0:4000x3000:1".into();
    assert!(scan_checkpoint(&item).unwrap().is_none());
    item.revision = scan_provider_revision("base".into(), "database".into(), -1);
    assert!(scan_checkpoint(&item).unwrap().is_none());
}
#[test]
fn malformed_and_newer_checkpoints_are_refused_without_mutation() {
    let item = measured();
    let text = scan_checkpoint(&item).unwrap().unwrap();
    for bad in [
        "{}".into(),
        text.replace("\"version\":1", "\"version\":2"),
        text.replace("\"bytes\":1000", "\"bytes\":-1"),
        " ".repeat(32769),
    ] {
        assert!(scan_restore(&item, bad).is_err());
    }
    assert_eq!(item.bytes, Some(1000));
}
#[test]
fn provider_epoch_generation_and_base_revision_all_invalidate_cache() {
    let mut item = measured();
    item.revision = scan_provider_revision("base".into(), "database-a".into(), 7);
    let checkpoint = scan_checkpoint(&item).unwrap().unwrap();
    for rev in [
        scan_provider_revision("base".into(), "database-b".into(), 7),
        scan_provider_revision("base".into(), "database-a".into(), 8),
        scan_provider_revision("changed".into(), "database-a".into(), 7),
    ] {
        let mut current = item.clone();
        current.revision = rev;
        current.fingerprint = None;
        assert!(
            scan_restore(&current, checkpoint.clone())
                .unwrap()
                .fingerprint
                .is_none()
        );
    }
}
