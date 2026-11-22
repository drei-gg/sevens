//! Filename metadata filters. Extension matching does not inspect or claim a file's content type.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FileSizeFilter {
    Any,
    AtLeast1Mb,
    AtLeast10Mb,
    AtLeast100Mb,
    AtLeast1Gb,
    Unknown,
}
#[uniffi::export]
pub fn file_size_matches(bytes: Option<i64>, filter: FileSizeFilter) -> bool {
    let known = bytes.filter(|value| *value >= 0);
    let minimum = match filter {
        FileSizeFilter::Any => return true,
        FileSizeFilter::Unknown => return known.is_none(),
        FileSizeFilter::AtLeast1Mb => 1_000_000,
        FileSizeFilter::AtLeast10Mb => 10_000_000,
        FileSizeFilter::AtLeast100Mb => 100_000_000,
        FileSizeFilter::AtLeast1Gb => 1_000_000_000,
    };
    known.is_some_and(|value| value >= minimum)
}
#[uniffi::export]
pub fn file_name_extension(name: String) -> String {
    std::path::Path::new(&name)
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default()
        .to_lowercase()
}
