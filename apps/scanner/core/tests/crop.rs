use scanner_core::*;
use std::collections::HashMap;
fn crop() -> PageCrop { PageCrop { left: 0.1, top: 0.2, right: 0.7, bottom: 0.8 } }
#[test]
fn crop_is_versioned_retained_and_clears_only_edited_ocr() {
    let original = new_scan_document("Crop".into(), vec![new_scan_page("old".into()), new_scan_page("keep".into())], 0.0);
    let settings = PageSettings { crop: Some(crop()), ..page_settings_default() };
    let edited = document_with_page_settings(&original, original.pages[0].id, settings).unwrap();
    assert_eq!(edited.version, 2);
    assert_eq!(edited.pages[0].id, original.pages[0].id);
    assert_eq!(edited.pages[0].text, "");
    assert_eq!(edited.pages[1], original.pages[1]);
    let bytes = document_encode(&edited).unwrap();
    assert_eq!(document_decode(&bytes).unwrap(), edited);
    let late = document_apply_recognition(&edited, original.pages.clone(), HashMap::from([(original.pages[0].id, "stale full page".into())]), true);
    assert_eq!(late, edited);
    let reset = document_with_page_settings(&edited, edited.pages[0].id, page_settings_default()).unwrap();
    assert_eq!(reset.version, 2);
    assert_eq!(reset.pages[0].settings(), page_settings_default());
    let mut wrong_version = edited.clone(); wrong_version.version = 1;
    assert!(document_validate(&wrong_version).is_err());
}
#[test]
fn crop_pixels_cover_fractional_edges_and_stay_inside_original() {
    assert_eq!(page_crop_pixels(crop(), 101, 99).unwrap(), CropPixels { x: 10, y: 19, width: 61, height: 61 });
    assert_eq!(page_crop_pixels(crop(), 1, 1).unwrap(), CropPixels { x: 0, y: 0, width: 1, height: 1 });
    assert!(page_crop_pixels(crop(), 0, 5).is_err());
    for bad in [PageCrop{left: -0.01, ..crop()}, PageCrop{right: 1.01, ..crop()}, PageCrop{top: f64::NAN, ..crop()}, PageCrop{left: 0.7, ..crop()}, PageCrop{bottom: 0.1, ..crop()}] {
        assert!(page_crop_pixels(bad, 100, 100).is_err());
        assert!(page_settings_validate(PageSettings{crop: Some(bad), ..page_settings_default()}).is_err());
    }
}
#[test]
fn crop_round_trips_each_display_orientation() {
    for turn in 0..4 {
        let rotated = page_crop_rotated(crop(), turn).unwrap();
        let roundtrip = page_crop_rotated(rotated, 4-turn).unwrap();
        for (a,b) in [(roundtrip.left,crop().left),(roundtrip.top,crop().top),(roundtrip.right,crop().right),(roundtrip.bottom,crop().bottom)] { assert!((a-b).abs() < 1e-12); }
    }
    assert_eq!(page_crop_rotated(PageCrop{left: 0.0, top: 0.0, right: 0.5, bottom: 1.0},1).unwrap(), PageCrop{left: 0.0, top: 0.0, right: 1.0, bottom: 0.5});
    assert!(page_crop_rotated(crop(),5).is_err());
}
