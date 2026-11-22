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
