//! The Sevens' shared schedule rules, written once for Alarm, Fast and Bloom.
//!
//! *When* something happens is a rule and lives here; *how* a phone is made to deliver it (Android's
//! AlarmManager, iOS notifications and AlarmKit) is platform work in SevenKit. This crate has no
//! UniFFI exports of its own: each app's core wraps what it needs, so its records stay its own.
//!
//! Wall-clock times follow Foundation's `nextDate(…, matchingPolicy: .nextTime,
//! repeatedTimePolicy: .first)`, which is what AlarmKit and `UNCalendarNotificationTrigger` do on
//! iOS: a time skipped by a daylight-saving gap happens when the gap ends, and a repeated time
//! happens the first time. Weekdays use Foundation's numbering, 1 Sunday … 7 Saturday.
//!
//! Time zones come from the bundled IANA database, the same on every platform, so a wall-clock time
//! never depends on which tzdata a phone happens to ship.

pub use jiff;
pub use uuid::Uuid;

use jiff::civil::{Date, DateTime, Weekday};
use jiff::tz::{AmbiguousOffset, TimeZone};
use jiff::{Timestamp, ToSpan};
use std::collections::BTreeSet;

fn database() -> &'static jiff::tz::TimeZoneDatabase {
    static DB: std::sync::OnceLock<jiff::tz::TimeZoneDatabase> = std::sync::OnceLock::new();
    DB.get_or_init(jiff::tz::TimeZoneDatabase::bundled)
}

/// The named IANA zone from the bundled database, or UTC when the name is unknown.
pub fn zone(identifier: &str) -> TimeZone {
    database().get(identifier).unwrap_or(TimeZone::UTC)
}

/// Whether the bundled database knows `identifier`.
pub fn zone_known(identifier: &str) -> bool {
    database().get(identifier).is_ok()
}

/// An upper-case hyphenated UUID, as Foundation writes `uuidString`.
pub fn uuid_string(id: &Uuid) -> String {
    id.hyphenated().to_string().to_uppercase()
}

/// A stable UUID derived from `root` and `key`: two 64-bit FNV-1a passes over
/// `"<UPPERCASE ROOT UUID>:<key>"`. A schedule keeps its identifiers across edits, across
/// launches and on both platforms, so a native alarm or notification is replaced in place rather
/// than duplicated.
pub fn identifier(root: Uuid, key: &str) -> Uuid {
    let text = format!("{}:{}", uuid_string(&root), key);
    let hash = |seed: u64| text.bytes().fold(seed, |h, b| (h ^ b as u64).wrapping_mul(1_099_511_628_211));
    let a = hash(14_695_981_039_346_656_037);
    let b = hash(7_809_847_782_465_536_322);
    Uuid::from_u128(((a as u128) << 64) | b as u128)
}

/// The first instant strictly after `after` at `minute` past midnight (0 ..< 1440) on `weekday`
/// (1 Sunday … 7 Saturday), in `tz`.
pub fn next_weekly(after: Timestamp, minute: i32, weekday: i32, tz: &TimeZone) -> Option<Timestamp> {
    next_matching(after, minute, tz, |day| foundation_weekday(day.weekday()) == weekday)
}

/// The first instant strictly after `after` at `minute` past midnight on any day, in `tz`.
pub fn next_daily(after: Timestamp, minute: i32, tz: &TimeZone) -> Option<Timestamp> {
    next_matching(after, minute, tz, |_| true)
}

fn next_matching(after: Timestamp, minute: i32, tz: &TimeZone, matches: impl Fn(Date) -> bool) -> Option<Timestamp> {
    if !(0..1440).contains(&minute) {
        return None;
    }
    let today: Date = after.to_zoned(tz.clone()).date();
    for offset in 0..=8 {
        let day = today.checked_add(offset.days()).ok()?;
        if !matches(day) {
            continue;
        }
        let wall = DateTime::new(day.year(), day.month(), day.day(), (minute / 60) as i8, (minute % 60) as i8, 0, 0).ok()?;
        if let Some(t) = resolve(wall, tz).filter(|t| *t > after) {
            return Some(t);
        }
    }
    None
}

/// Every instant after `start`, up to `days` calendar days later in `tz` (inclusive), at `minute` on
/// any of `weekdays`; sorted and without duplicates.
pub fn weekly_occurrences(minute: i32, weekdays: &[i32], start: Timestamp, days: i32, tz: &TimeZone) -> Vec<Timestamp> {
    let Some(limit) = calendar_limit(start, days, tz) else { return vec![] };
    let mut dates = BTreeSet::new();
    for &weekday in weekdays {
        let mut cursor = start;
        for _ in 0..(days / 7 + 2) {
            match next_weekly(cursor, minute, weekday, tz) {
                Some(next) if next <= limit => {
                    dates.insert(next);
                    cursor = next;
                }
                _ => break,
            }
        }
    }
    dates.into_iter().collect()
}

/// The end of `start` plus `days` calendar days in `tz`, the bound [`weekly_occurrences`] uses.
pub fn calendar_limit(start: Timestamp, days: i32, tz: &TimeZone) -> Option<Timestamp> {
    start.to_zoned(tz.clone()).checked_add(days.days()).ok().map(|z| z.timestamp())
}

/// A wall-clock time in `tz` as an instant, read like Foundation `.nextTime` / `.first`.
pub fn resolve(wall: DateTime, tz: &TimeZone) -> Option<Timestamp> {
    let ambiguous = tz.to_ambiguous_timestamp(wall);
    match ambiguous.offset() {
        AmbiguousOffset::Unambiguous { offset } => offset.to_timestamp(wall).ok(),
        // .first: the earlier of the two instants.
        AmbiguousOffset::Fold { before, .. } => before.to_timestamp(wall).ok(),
        // .nextTime: the first instant after the skipped hour, which is the transition itself.
        AmbiguousOffset::Gap { before, .. } => {
            let past_gap = before.to_timestamp(wall).ok()?.checked_add(1.second()).ok()?;
            tz.preceding(past_gap).next().map(|transition| transition.timestamp())
        }
    }
}

/// Foundation's weekday number for `day`: 1 Sunday … 7 Saturday.
pub fn foundation_weekday(day: Weekday) -> i32 {
    day.to_sunday_one_offset() as i32
}
