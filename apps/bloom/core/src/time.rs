use crate::{Error, Result, ensure};
use jiff::{Timestamp, ToSpan, civil::Date};
pub fn date(s: &str) -> Result<Date> {
    ensure(s.len() == 10, "Dates must use YYYY-MM-DD.")?;
    let d: Date = s
        .parse()
        .map_err(|_| Error::invalid("Invalid calendar date."))?;
    ensure(
        (1900..=2200).contains(&d.year()),
        "Date is outside the supported range.",
    )?;
    Ok(d)
}
pub fn add(s: &str, n: i32) -> Result<String> {
    date(s)?
        .checked_add(n.days())
        .map(|d| d.to_string())
        .map_err(|_| Error::invalid("Date calculation overflow."))
}
pub fn distance(a: &str, b: &str) -> Result<i32> {
    Ok(date(a)?
        .until(date(b)?)
        .map_err(|_| Error::invalid("Date calculation failed."))?
        .get_days())
}
pub fn today(now: i64, zone: &str) -> Result<String> {
    let z = jiff::tz::TimeZone::get(zone).map_err(|_| Error::invalid("Unknown time zone."))?;
    Ok(Timestamp::from_second(now)
        .map_err(|_| Error::invalid("Invalid timestamp."))?
        .to_zoned(z)
        .date()
        .to_string())
}
