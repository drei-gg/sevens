use cleaner_core::*;
#[test]
fn explicit_profiles_preserve_ratio_and_never_enlarge() {
    for (profile, width, height) in [
        (ImageSizeProfile::Original, 4000, 3000),
        (ImageSizeProfile::Large, 2400, 1800),
        (ImageSizeProfile::Medium, 1600, 1200),
        (ImageSizeProfile::Small, 800, 600),
    ] {
        assert_eq!(
            image_resize_plan(4000, 3000, profile).unwrap(),
            ImageResizePlan {
                width,
                height,
                resized: width != 4000
            }
        );
        assert_eq!(
            image_resize_plan(3000, 4000, profile).unwrap(),
            ImageResizePlan {
                width: height,
                height: width,
                resized: width != 4000
            }
        );
    }
    assert_eq!(
        image_resize_plan(301, 199, ImageSizeProfile::Small).unwrap(),
        ImageResizePlan {
            width: 301,
            height: 199,
            resized: false
        }
    );
    assert_eq!(
        image_resize_plan(16000, 1, ImageSizeProfile::Small)
            .unwrap()
            .height,
        1
    );
    assert!(image_resize_plan(0, 100, ImageSizeProfile::Original).is_err());
    assert!(image_resize_plan(u32::MAX, u32::MAX, ImageSizeProfile::Small).is_err());
}
#[test]
fn resize_does_not_weaken_default_assessment_or_authorize_deletion() {
    let source = new_image_facts(4000, 3000, 500_000);
    let output = new_image_facts(800, 600, 50_000);
    let original = new_compression_assessment(source.clone(), output.clone(), 45.0, 40.0, false);
    assert!(!assessment_can_offer_copy(&original));
    let resized = new_compression_assessment(source, output, 45.0, 40.0, true);
    assert!(assessment_can_offer_copy(&resized));
    assert!(!assessment_authorizes_source_deletion(&resized));
}
