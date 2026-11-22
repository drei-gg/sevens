use scanner_core::*;
fn points() -> Vec<InkPoint> {
    vec![
        InkPoint { x: 0.15, y: 0.2 },
        InkPoint { x: 0.5, y: 0.7 },
        InkPoint { x: 0.85, y: 0.25 },
    ]
}
fn stroke(settings: PageSettings) -> PageInk {
    ink_from_display(points(), 4.0, 400.0, 600.0, settings, InkColor::Blue).unwrap()
}
#[test]
fn ink_round_trips_points_and_width_for_every_crop_rotation() {
    for turns in 0..4 {
        let settings = PageSettings {
            quarter_turns: turns,
            crop: Some(PageCrop {
                left: 0.1,
                top: 0.2,
                right: 0.9,
                bottom: 0.8,
            }),
            ..page_settings_default()
        };
        let s = stroke(settings);
        let d = ink_display(s, settings, 400.0, 600.0).unwrap();
        assert!((d.width - 4.0).abs() < 1e-10);
        for (actual, expected) in d.points.iter().zip(points()) {
            assert!((actual.x - expected.x * 400.0).abs() < 1e-10);
            assert!((actual.y - expected.y * 600.0).abs() < 1e-10);
        }
        let scaled = ink_display(stroke(settings), settings, 800.0, 1200.0).unwrap();
        assert!((scaled.width - 8.0).abs() < 1e-10);
    }
}
#[test]
fn uncrop_preserves_creation_clip_and_unclamped_path() {
    let settings = PageSettings {
        crop: Some(PageCrop {
            left: 0.25,
            top: 0.25,
            right: 0.75,
            bottom: 0.75,
        }),
        ..page_settings_default()
    };
    let s = ink_from_display(
        vec![InkPoint { x: -0.2, y: 0.3 }, InkPoint { x: 1.2, y: 0.8 }],
        4.0,
        400.0,
        400.0,
        settings,
        InkColor::Red,
    )
    .unwrap();
    let shown = ink_display(s, page_settings_default(), 800.0, 800.0).unwrap();
    assert_eq!(shown.clip.x, 200.0);
    assert_eq!(shown.clip.width, 400.0);
    assert!(shown.points[0].x < shown.clip.x);
    assert!(shown.points[1].x > 600.0);
    assert_eq!(shown.width, 4.0);
}
#[test]
fn v4_persists_and_legacy_edits_do_not_drop_ink_or_downgrade() {
    let d = new_scan_document(
        "Ink".into(),
        vec![new_scan_page("Searchable original".into())],
        0.0,
    );
    let s = stroke(page_settings_default());
    let saved = document_with_page_marks(&d, d.pages[0].clone(), vec![], vec![s.clone()]).unwrap();
    assert_eq!(saved.version, 4);
    assert_eq!(
        manifest_read(
            &manifest_encode(&saved).unwrap(),
            document_folder_name(saved.id)
        )
        .unwrap(),
        saved
    );
    let edited = document_with_annotations(
        &saved,
        saved.pages[0].clone(),
        vec![PageAnnotation {
            bounds: s.clip,
            style: AnnotationStyle::Highlight,
        }],
    )
    .unwrap();
    assert_eq!(edited.version, 4);
    assert_eq!(edited.pages[0].ink, vec![s]);
    assert!(document_with_annotations(&saved, d.pages[0].clone(), vec![]).is_err());
    let cleared = document_with_page_marks(&saved, saved.pages[0].clone(), vec![], vec![]).unwrap();
    assert_eq!(cleared.version, 4);
    for version in [1, 2, 3, 5] {
        let mut bad = saved.clone();
        bad.version = version;
        assert!(bad.validate().is_err());
    }
    assert!(
        document_decode(&document_encode(&d).unwrap())
            .unwrap()
            .pages[0]
            .ink
            .is_empty()
    );
}
#[test]
fn ink_rejects_nonfinite_excessive_and_empty_geometry() {
    let s = stroke(page_settings_default());
    for width in [0.0, -1.0, f64::NAN, f64::INFINITY, 0.11] {
        let mut bad = s.clone();
        bad.width = width;
        assert!(ink_validate(&[bad]).is_err());
    }
    for x in [f64::NAN, f64::INFINITY, -17.0, 18.0] {
        let mut bad = s.clone();
        bad.points[0].x = x;
        assert!(ink_validate(&[bad]).is_err());
    }
    let mut bad = s.clone();
    bad.points.clear();
    assert!(ink_validate(&[bad]).is_err());
    let mut bad = s.clone();
    bad.points = vec![points()[0]; 8193];
    assert!(ink_validate(&[bad]).is_err());
    assert!(ink_validate(&vec![s; 101]).is_err());
    assert!(
        ink_from_display(
            points(),
            4.0,
            0.0,
            600.0,
            page_settings_default(),
            InkColor::Black
        )
        .is_err()
    );
}
#[test]
fn ink_commit_merges_metadata_but_rejects_geometry_and_parallel_ink() {
    let original = new_scan_document("Ink".into(), vec![new_scan_page("OCR".into())], 0.0);
    let mut current = original.clone();
    current.title = "Renamed".into();
    current.pages[0].text = "Corrected".into();
    let saved = document_with_page_marks(
        &current,
        original.pages[0].clone(),
        vec![],
        vec![stroke(page_settings_default())],
    )
    .unwrap();
    assert_eq!(saved.pages[0].text, "Corrected");
    assert_eq!(saved.title, "Renamed");
    assert!(document_with_page_marks(&saved, original.pages[0].clone(), vec![], vec![]).is_err());
    current.pages[0].adjustments = Some(PageSettings {
        quarter_turns: 1,
        ..page_settings_default()
    });
    assert!(document_with_page_marks(&current, original.pages[0].clone(), vec![], vec![]).is_err());
}

#[test]
fn aggregate_point_limits_bound_page_and_document_work() {
    let mut s = stroke(page_settings_default());
    s.points = vec![InkPoint { x: 0.4, y: 0.5 }; 8192];
    assert!(ink_validate(&vec![s.clone(); 4]).is_ok());
    assert!(ink_validate(&vec![s.clone(); 5]).is_err());
    let mut document = new_scan_document(
        "Bounded".into(),
        (0..4).map(|_| new_scan_page(String::new())).collect(),
        0.0,
    );
    document.version = 4;
    for page in &mut document.pages {
        page.ink = vec![s.clone(); 4];
    }
    assert!(document.validate().is_err());
    document.pages.pop();
    assert!(document.validate().is_ok());
}
