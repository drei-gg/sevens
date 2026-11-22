use scanner_core::*;
use std::collections::HashMap;

#[test]
fn text_corrections_survive_late_ocr_and_keep_other_metadata() {
    let initial = new_scan_document(
        "Scan".into(),
        vec![new_scan_page("mistkae".into())],
        800_000_000.0,
    );
    let expected = initial.pages[0].clone();
    let mut current = initial.clone();
    current.title = "Renamed while editing".into();
    let corrected = document_with_page_text(&current, expected.clone(), "mistake".into()).unwrap();
    assert_eq!(corrected.title, current.title);
    assert_eq!(corrected.pages[0].adjustments, expected.adjustments);
    let late = document_apply_recognition(
        &corrected,
        initial.pages.clone(),
        HashMap::from([(expected.id, "late result".into())]),
        true,
    );
    assert_eq!(late, corrected);
    assert!(document_with_page_text(&corrected, expected, "stale correction".into()).is_err());
    let fresh = document_apply_recognition(
        &corrected,
        corrected.pages.clone(),
        HashMap::from([(corrected.pages[0].id, "explicit later retry".into())]),
        true,
    );
    assert_eq!(fresh.pages[0].text, "explicit later retry");
}

#[test]
fn text_corrections_reject_oversize_and_changed_images() {
    let initial = new_scan_document(
        "Scan".into(),
        vec![new_scan_page("OCR".into())],
        800_000_000.0,
    );
    let expected = initial.pages[0].clone();
    assert!(document_with_page_text(&initial, expected.clone(), "x".repeat(100_001)).is_err());
    let changed = document_with_page_settings(
        &initial,
        expected.id,
        PageSettings {
            quarter_turns: 1,
            ..page_settings_default()
        },
    )
    .unwrap();
    assert!(document_with_page_text(&changed, expected, "old orientation".into()).is_err());
}
