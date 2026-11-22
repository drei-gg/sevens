use vault_core::*;
#[test]
fn page_ranges_are_explicit_and_bounded() {
    assert_eq!(recognition_plan(0, 1, 1).issue, Some(VaultIssue::NoReadablePages));
    for (first,last,issue) in [(0,1,VaultIssue::PageRange{page_count:21}),(2,1,VaultIssue::PageRange{page_count:21}),(1,22,VaultIssue::PageRange{page_count:21}),(1,21,VaultIssue::TooManyPages{maximum:20})] {
        let plan = recognition_plan(21,first,last);
        assert_eq!(plan.issue, Some(issue)); assert!(plan.pages.is_empty());
    }
    let plan = recognition_plan(100, 81, 100);
    assert_eq!(plan.pages, (80..100).collect::<Vec<_>>());
    assert_eq!(plan.maximum_pixel_edge,2400);
    assert_eq!(recognition_plan(u32::MAX, u32::MAX, u32::MAX).pages, vec![u32::MAX-1]);
}
#[test]
fn rendering_is_bounded_for_landscape_portrait_and_invalid_geometry() {
    assert_eq!(recognition_page_size(612.,792.).unwrap(),RecognitionPageSize{width:1855,height:2400});
    assert_eq!(recognition_page_size(792.,612.).unwrap(),RecognitionPageSize{width:2400,height:1855});
    for value in [0.,-1.,f64::NAN,f64::INFINITY] { assert!(recognition_page_size(value,100.).is_err()); }
    assert_eq!(recognition_page_size(1e-320,1e-320).unwrap(),RecognitionPageSize{width:2400,height:2400});
    let thin = recognition_page_size(1.,1e100).unwrap(); assert_eq!(thin.width,1); assert_eq!(thin.height,2400);
}
#[test]
fn recognized_pages_preserve_order_and_bound_utf8_bytes() {
    assert_eq!(recognition_append("".into(),2,"  Second page\n ".into()).unwrap(),"Page 2\nSecond page");
    assert_eq!(recognition_append("Page 2\nSecond page".into(),3,"Third".into()).unwrap(),"Page 2\nSecond page\n\nPage 3\nThird");
    assert_eq!(recognition_append("previous".into(),4," \n".into()).unwrap(),"previous");
    assert!(recognition_append("".into(),0,"text".into()).is_err());
    assert!(recognition_append("".into(),1,"é".repeat(2_500_000)).is_err());
    assert!(recognition_append("x".repeat(5_000_001),1,"".into()).is_err());
}

fn image() -> VaultItem { new_vault_item("scan.png".into(), "public.png".into(), 128, "a".repeat(64), None, 0) }
#[test]
fn batch_plan_keeps_library_order_and_never_silently_truncates() {
    let a=image(); let b=image();
    let plan=recognition_batch_plan(vec![a.clone(),b.clone()],vec![b.id,a.id,b.id],vec![a.id,b.id]);
    assert_eq!(plan.items,vec![a,b]); assert!(plan.issue.is_none());
    let items:Vec<_>=(0..11).map(|_|image()).collect(); let ids=items.iter().map(|i|i.id).collect::<Vec<_>>();
    let plan=recognition_batch_plan(items,ids.clone(),ids); assert!(plan.items.is_empty()); assert_eq!(plan.issue, Some(VaultIssue::TooManyFiles{maximum:10}));
}
#[test]
fn batch_plan_rejects_missing_trashed_unsupported_and_empty_selection() {
    let mut a=image();
    for (items,ids,supported,issue) in [(vec![a.clone()],vec![],vec![a.id],VaultIssue::NothingSelected),(vec![],vec![a.id],vec![a.id],VaultIssue::SelectionChanged),(vec![a.clone()],vec![a.id],vec![],VaultIssue::UnsupportedSelection)] {
        let plan=recognition_batch_plan(items,ids,supported);assert!(plan.items.is_empty());assert_eq!(plan.issue,Some(issue));
    }
    a.deleted=Some(std::time::SystemTime::now()); assert_eq!(recognition_batch_plan(vec![a.clone()],vec![a.id],vec![a.id]).issue,Some(VaultIssue::SelectionChanged));
    assert_eq!(recognition_review_text("café".into()).unwrap(),"café");
    assert!(recognition_review_text("é".repeat(2_500_001)).is_err());
}
