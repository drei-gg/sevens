//! Swift `ScheduleCompiler`: routines into native alarm registrations, and the dates they ring.

use crate::model::*;
use crate::time::{CalendarSpec, system_time, timestamp, uuid_string, zone};
use jiff::civil::{Date, DateTime, Weekday};
use jiff::tz::{AmbiguousOffset, TimeZone};
use jiff::{Timestamp, ToSpan};
use std::collections::{BTreeMap, BTreeSet};
use std::time::SystemTime;
use uuid::Uuid;

/// A stable UUID in the routine namespace: two 64-bit FNV-1a passes over
/// `"<UPPERCASE ROUTINE UUID>:<key>"`, so a registration keeps its identifier across edits and
/// on both platforms.
#[uniffi::export]
pub fn schedule_identifier(routine: Uuid, key: String) -> Uuid {
    let text = format!("{}:{}", uuid_string(&routine), key);
    let hash = |seed: u64| text.bytes().fold(seed, |h, b| (h ^ b as u64).wrapping_mul(1_099_511_628_211));
    let a = hash(14_695_981_039_346_656_037);
    let b = hash(7_809_847_782_465_536_322);
    Uuid::from_u128(((a as u128) << 64) | b as u128)
}

/// Swift `ScheduleCompiler.registrations`: validates, then one registration per distinct minute
/// (weekly slots and window loops) or one for a future one-off. Disabled routines and past
/// one-offs produce none. A window past midnight rings on the following weekday.
#[uniffi::export]
pub fn schedule_registrations(routine: &Routine, now: SystemTime) -> Result<Vec<Registration>, LoopError> {
    routine_validate(routine)?;
    if !routine.enabled {
        return Ok(vec![]);
    }
    if routine.timing == Timing::Once {
        if routine.once <= now {
            return Ok(vec![]);
        }
        return Ok(vec![Registration { date: Some(routine.once), ..new_registration(schedule_identifier(routine.id, "once".into()), routine.clone()) }]);
    }
    let mut days_by_minute: BTreeMap<i32, BTreeSet<i32>> = BTreeMap::new();
    if routine.timing == Timing::Weekly {
        for slot in &routine.slots {
            days_by_minute.entry(slot.minute).or_default().extend(slot.weekdays.iter().copied());
        }
    } else {
        let end = if routine.window_end > routine.window_start { routine.window_end } else { routine.window_end + 1440 };
        // End is inclusive only when exactly reached by an interval.
        let mut offset = routine.window_start;
        while offset <= end {
            let shifted = routine.window_days.iter().map(|d| ((d - 1 + offset / 1440) % 7) + 1);
            days_by_minute.entry(offset % 1440).or_default().extend(shifted);
            offset += routine.interval_minutes;
        }
    }
    if days_by_minute.len() > 96 {
        return Err(invalid("This loop creates more than 96 different alarm times. Increase its interval or shorten the window."));
    }
    Ok(days_by_minute
        .into_iter()
        .map(|(minute, days)| Registration {
            minute: Some(minute),
            weekdays: days.into_iter().collect(),
            ..new_registration(schedule_identifier(routine.id, format!("minute:{minute}")), routine.clone())
        })
        .collect())
}

/// Swift `ScheduleCompiler.occurrences`: every ring after `now` up to `days` calendar days later
/// (inclusive), sorted and without duplicates. Wall-clock times follow Foundation's
/// `nextDate(…, matchingPolicy: .nextTime, repeatedTimePolicy: .first)`: a time skipped by a
/// daylight-saving gap rings at the end of the gap, a repeated time rings the first time.
#[uniffi::export]
pub fn schedule_occurrences(routine: &Routine, now: SystemTime, days: i32, calendar: CalendarSpec) -> Result<Vec<SystemTime>, LoopError> {
    let registrations = schedule_registrations(routine, now)?;
    let tz = zone(&calendar);
    let Some(start) = timestamp(now) else { return Ok(vec![]) };
    let Some(limit) = start.to_zoned(tz.clone()).checked_add(days.days()).ok().map(|z| z.timestamp()) else { return Ok(vec![]) };
    let mut dates = BTreeSet::new();
    for registration in &registrations {
        if let Some(date) = registration.date {
            if timestamp(date).is_some_and(|d| d <= limit) {
                dates.insert(date);
            }
            continue;
        }
        let Some(minute) = registration.minute else { continue };
        for &weekday in &registration.weekdays {
            let mut cursor = start;
            for _ in 0..(days / 7 + 2) {
                match next_date(cursor, minute, weekday, &tz) {
                    Some(next) if next <= limit => {
                        dates.insert(system_time(next));
                        cursor = next;
                    }
                    _ => break,
                }
            }
        }
    }
    Ok(dates.into_iter().collect())
}

/// The first instant strictly after `after` at `minute` past midnight on Foundation `weekday`.
fn next_date(after: Timestamp, minute: i32, weekday: i32, tz: &TimeZone) -> Option<Timestamp> {
    let today: Date = after.to_zoned(tz.clone()).date();
    for offset in 0..=8 {
        let day = today.checked_add(offset.days()).ok()?;
        if foundation_weekday(day.weekday()) != weekday {
            continue;
        }
        let wall = DateTime::new(day.year(), day.month(), day.day(), (minute / 60) as i8, (minute % 60) as i8, 0, 0).ok()?;
        if let Some(t) = resolve(wall, tz).filter(|t| *t > after) {
            return Some(t);
        }
    }
    None
}

fn resolve(wall: DateTime, tz: &TimeZone) -> Option<Timestamp> {
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

fn foundation_weekday(day: Weekday) -> i32 {
    day.to_sunday_one_offset() as i32
}
