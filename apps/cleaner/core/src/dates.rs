//! Native calendar/time-zone adapters supply civil dates; the selection rule lives once here.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MediaDateFilter {
    Any,
    Day,
    Month,
    Year,
    Anniversary,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct MediaCalendarDate {
    pub year: i32,
    pub month: u32,
    pub day: u32,
}
#[uniffi::export]
pub fn media_date_matches(
    filter: MediaDateFilter,
    recorded: Option<MediaCalendarDate>,
    reference: MediaCalendarDate,
) -> bool {
    if filter == MediaDateFilter::Any {
        return true;
    }
    let Some(recorded) = recorded else {
        return false;
    };
    match filter {
        MediaDateFilter::Any => true,
        MediaDateFilter::Day => recorded == reference,
        MediaDateFilter::Month => {
            recorded.year == reference.year && recorded.month == reference.month
        }
        MediaDateFilter::Year => recorded.year == reference.year,
        MediaDateFilter::Anniversary => {
            recorded.year < reference.year
                && recorded.month == reference.month
                && recorded.day == reference.day
        }
    }
}
