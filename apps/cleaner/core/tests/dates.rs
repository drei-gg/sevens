use cleaner_core::*;
fn date(y: i32, m: u32, d: u32) -> MediaCalendarDate {
    MediaCalendarDate {
        year: y,
        month: m,
        day: d,
    }
}
#[test]
fn unknown_dates_are_not_inferred_from_scan_time() {
    let anchor = date(2026, 11, 18);
    assert!(media_date_matches(MediaDateFilter::Any, None, anchor));
    for mode in [
        MediaDateFilter::Day,
        MediaDateFilter::Month,
        MediaDateFilter::Year,
        MediaDateFilter::Anniversary,
    ] {
        assert!(!media_date_matches(mode, None, anchor));
    }
}
#[test]
fn day_month_year_scopes_do_not_cross_calendar_boundaries() {
    let anchor = date(2026, 1, 1);
    assert!(media_date_matches(
        MediaDateFilter::Day,
        Some(anchor),
        anchor
    ));
    assert!(!media_date_matches(
        MediaDateFilter::Day,
        Some(date(2026, 1, 2)),
        anchor
    ));
    assert!(media_date_matches(
        MediaDateFilter::Month,
        Some(date(2026, 1, 31)),
        anchor
    ));
    assert!(!media_date_matches(
        MediaDateFilter::Month,
        Some(date(2025, 1, 1)),
        anchor
    ));
    assert!(media_date_matches(
        MediaDateFilter::Year,
        Some(date(2026, 12, 31)),
        anchor
    ));
    assert!(!media_date_matches(
        MediaDateFilter::Year,
        Some(date(2025, 12, 31)),
        anchor
    ));
}
#[test]
fn anniversary_is_same_day_in_earlier_years_without_leap_day_substitution() {
    let anchor = date(2024, 2, 29);
    assert!(media_date_matches(
        MediaDateFilter::Anniversary,
        Some(date(2020, 2, 29)),
        anchor
    ));
    for candidate in [
        anchor,
        date(2028, 2, 29),
        date(2023, 2, 28),
        date(2023, 3, 1),
    ] {
        assert!(!media_date_matches(
            MediaDateFilter::Anniversary,
            Some(candidate),
            anchor
        ));
    }
}
