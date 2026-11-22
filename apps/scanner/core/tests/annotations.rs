use scanner_core::*;
fn rect(l: f64, t: f64, r: f64, b: f64) -> PageCrop {
    PageCrop {
        left: l,
        top: t,
        right: r,
        bottom: b,
    }
}
fn mark() -> PageAnnotation {
    PageAnnotation {
        bounds: rect(0.2, 0.3, 0.7, 0.8),
        style: AnnotationStyle::Highlight,
    }
}
fn doc() -> ScanDocument {
    new_scan_document(
        "Marked scan".into(),
        vec![new_scan_page("Keep recognized text".into())],
        0.0,
    )
}
#[test]
fn display_geometry_round_trips_every_rotation_and_crop() {
    for turns in 0..4 {
        let settings = PageSettings {
            quarter_turns: turns,
            crop: Some(rect(0.1, 0.2, 0.9, 0.95)),
            ..page_settings_default()
        };
        let input = rect(0.05, 0.15, 0.85, 0.9);
        let original =
            annotation_from_display(input, settings, AnnotationStyle::BlueOutline).unwrap();
        let shown = annotation_display_bounds(original.bounds, settings).unwrap();
        for (a, b) in [
            (shown.x, input.left),
            (shown.y, input.top),
            (shown.width, input.right - input.left),
            (shown.height, input.bottom - input.top),
        ] {
            assert!((a - b).abs() < 1e-12);
        }
    }
}
#[test]
fn crop_hides_marks_without_clamping_or_destroying_them() {
    let original = mark();
    let settings = PageSettings {
        crop: Some(rect(0.5, 0.5, 1.0, 1.0)),
        ..page_settings_default()
    };
    let shown = annotation_display_bounds(original.bounds, settings).unwrap();
    assert!(shown.x < 0.0 && shown.y < 0.0);
    let restored = annotation_display_bounds(original.bounds, page_settings_default()).unwrap();
    assert_eq!(restored.x, original.bounds.left);
}
#[test]
fn annotations_upgrade_fail_closed_and_survive_settings_and_json() {
    let original = doc();
    let changed =
        document_with_annotations(&original, original.pages[0].clone(), vec![mark()]).unwrap();
    assert_eq!(changed.version, 3);
    assert_eq!(changed.pages[0].text, original.pages[0].text);
    let encoded = manifest_encode(&changed).unwrap();
    let restored = manifest_read(&encoded, document_folder_name(changed.id)).unwrap();
    assert_eq!(restored, changed);
    let edited = document_with_page_settings(
        &changed,
        changed.pages[0].id,
        PageSettings {
            crop: Some(rect(0.1, 0.1, 0.9, 0.9)),
            ..page_settings_default()
        },
    )
    .unwrap();
    assert_eq!(edited.version, 3);
    assert_eq!(edited.pages[0].annotations, vec![mark()]);
    for version in [1, 2] {
        let mut bad = changed.clone();
        bad.version = version;
        assert!(document_validate(&bad).is_err());
    }
    assert_eq!(
        document_decode(&document_encode(&original).unwrap())
            .unwrap()
            .pages[0]
            .annotations,
        vec![]
    );
}
#[test]
fn annotation_commits_merge_text_but_reject_stale_geometry_and_marks() {
    let original = doc();
    let mut concurrent = original.clone();
    concurrent.pages[0].text = "Corrected".into();
    concurrent.title = "Renamed".into();
    let committed =
        document_with_annotations(&concurrent, original.pages[0].clone(), vec![mark()]).unwrap();
    assert_eq!(committed.pages[0].text, "Corrected");
    assert_eq!(committed.title, "Renamed");
    assert!(document_with_annotations(&committed, original.pages[0].clone(), vec![]).is_err());
    concurrent.pages[0].adjustments = Some(PageSettings {
        quarter_turns: 1,
        ..page_settings_default()
    });
    assert!(
        document_with_annotations(&concurrent, original.pages[0].clone(), vec![mark()]).is_err()
    );
}
#[test]
fn invalid_and_oversized_annotations_fail_before_publication() {
    assert!(annotations_validate(&vec![mark(); 101]).is_err());
    for bounds in [
        rect(-0.1, 0.0, 0.5, 0.5),
        rect(0.5, 0.0, 0.4, 0.5),
        rect(0.0, 0.0, f64::NAN, 1.0),
    ] {
        assert!(annotations_validate(&[PageAnnotation { bounds, ..mark() }]).is_err());
    }
    let marks = vec![mark(); 100];
    assert_eq!(
        annotations_decode(annotations_encode(marks.clone()).unwrap()).unwrap(),
        marks
    );
    assert!(annotations_decode("[".repeat(100001)).is_err());
}
#[test]
fn display_edges_survive_float_rounding_at_every_crop_and_rotation() {
    // Dragging to a crop's far edge must never fail validation on a value just past 1.0 or 0.0.
    let steps = [0.0, 0.1, 0.2, 0.3, 0.33, 0.45, 0.6, 0.7];
    let mut checked = 0;
    for turns in 0..4 {
        for &l in &steps {
            for &t in &steps {
                for &w in &[0.3, 0.55, 0.7, 0.9] {
                    let (r, b) = (l + w, t + w);
                    if r > 1.0 || b > 1.0 {
                        continue;
                    }
                    let settings = PageSettings {
                        quarter_turns: turns,
                        crop: Some(rect(l, t, r, b)),
                        ..page_settings_default()
                    };
                    let mark =
                        annotation_from_display(rect(0.0, 0.0, 1.0, 1.0), settings, AnnotationStyle::Highlight)
                            .unwrap();
                    for v in [mark.bounds.left, mark.bounds.top, mark.bounds.right, mark.bounds.bottom] {
                        assert!((0.0..=1.0).contains(&v));
                    }
                    annotations_validate(&[mark]).unwrap();
                    checked += 1;
                }
            }
        }
    }
    assert!(checked > 100);
}
#[test]
fn capture_plan_appends_what_fits_and_never_drops_pages() {
    assert_eq!(append_page_allowance(0), 50);
    assert_eq!(append_page_allowance(48), 2);
    assert_eq!(append_page_allowance(50), 0);
    assert_eq!(append_page_allowance(70), 0);
    let plan = capture_plan(Some(45), 12);
    assert_eq!((plan.append, plan.new_documents.clone()), (5, vec![7]));
    let plan = capture_plan(Some(50), 3);
    assert_eq!((plan.append, plan.new_documents.clone()), (0, vec![3]));
    let plan = capture_plan(None, 120);
    assert_eq!((plan.append, plan.new_documents.clone()), (0, vec![50, 50, 20]));
    let plan = capture_plan(Some(10), 4);
    assert_eq!((plan.append, plan.new_documents.clone()), (4, vec![]));
    assert_eq!(capture_plan(None, 0).new_documents, Vec::<u64>::new());
    for existing in [None, Some(0), Some(30), Some(50)] {
        for captured in 0..130 {
            let plan = capture_plan(existing, captured);
            assert_eq!(plan.append + plan.new_documents.iter().sum::<u64>(), captured);
            assert!(plan.new_documents.iter().all(|n| (1..=50).contains(n)));
        }
    }
}
#[test]
fn rounding_just_past_an_edge_snaps_but_real_overflow_is_rejected() {
    for turns in 0..4 {
        let settings = PageSettings {
            quarter_turns: turns,
            crop: Some(rect(0.1, 0.2, 0.9, 0.95)),
            ..page_settings_default()
        };
        let edge = rect(-1e-12, -4e-16, 1.0 + 1e-12, 1.0 + 2e-16);
        let mark = annotation_from_display(edge, settings, AnnotationStyle::RedOutline).unwrap();
        annotations_validate(&[mark]).unwrap();
        let whole = annotation_from_display(rect(0.0, 0.0, 1.0, 1.0), settings, AnnotationStyle::RedOutline).unwrap();
        assert_eq!(mark, whole);
        assert!(annotation_from_display(rect(0.0, 0.0, 1.001, 1.0), settings, AnnotationStyle::RedOutline).is_err());
    }
}
