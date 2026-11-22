//! Swift `ScheduleCompiler`: routines into native alarm registrations, and the dates they ring.

use crate::model::*;
use crate::time::{CalendarSpec, system_time, timestamp, zone};
use jiff::Timestamp;
use jiff::tz::TimeZone;
use std::collections::{BTreeMap, BTreeSet};
use std::time::SystemTime;
use uuid::Uuid;

/// A stable UUID in the routine namespace (`seven_schedule::identifier`), so a registration keeps
/// its identifier across edits and on both platforms.
#[uniffi::export]
pub fn schedule_identifier(routine: Uuid, key: String) -> Uuid {
    seven_schedule::identifier(routine, &key)
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
    let Some(limit) = seven_schedule::calendar_limit(start, days, &tz) else { return Ok(vec![]) };
    let mut dates = BTreeSet::new();
    for registration in &registrations {
        if let Some(date) = registration.date {
            if timestamp(date).is_some_and(|d| d <= limit) {
                dates.insert(date);
            }
            continue;
        }
        let Some(minute) = registration.minute else { continue };
        dates.extend(seven_schedule::weekly_occurrences(minute, &registration.weekdays, start, days, &tz).into_iter().map(system_time));
    }
    Ok(dates.into_iter().collect())
}

/// The first instant strictly after `after` at `minute` past midnight on Foundation `weekday`.
pub(crate) fn next_date(after: Timestamp, minute: i32, weekday: i32, tz: &TimeZone) -> Option<Timestamp> {
    seven_schedule::next_weekly(after, minute, weekday, tz)
}
