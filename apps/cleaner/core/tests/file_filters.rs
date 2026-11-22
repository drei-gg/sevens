use cleaner_core::*;
#[test]
fn size_scopes_preserve_unknown_and_zero_and_use_decimal_boundaries() {
    for (scope, limit) in [
        (FileSizeFilter::AtLeast1Mb, 1_000_000),
        (FileSizeFilter::AtLeast10Mb, 10_000_000),
        (FileSizeFilter::AtLeast100Mb, 100_000_000),
        (FileSizeFilter::AtLeast1Gb, 1_000_000_000),
    ] {
        assert!(!file_size_matches(Some(limit - 1), scope));
        assert!(file_size_matches(Some(limit), scope));
        assert!(file_size_matches(Some(i64::MAX), scope));
        for missing in [None, Some(-1), Some(0)] {
            assert!(!file_size_matches(missing, scope));
        }
    }
    for missing in [None, Some(-1)] {
        assert!(file_size_matches(missing, FileSizeFilter::Unknown));
        assert!(file_size_matches(missing, FileSizeFilter::Any));
    }
    assert!(!file_size_matches(Some(0), FileSizeFilter::Unknown));
    assert!(file_size_matches(Some(0), FileSizeFilter::Any));
}
#[test]
fn extensions_ignore_parent_names_and_normalize_case_without_guessing_content() {
    for (name, ext) in [
        ("folder.with.dot/report.PDF", "pdf"),
        ("archive.tar.gz", "gz"),
        ("folder.PDF/LICENSE", ""),
        (".hidden", ""),
        ("trailing.", ""),
        ("photo.not-an-image", "not-an-image"),
    ] {
        assert_eq!(file_name_extension(name.into()), ext);
    }
}
