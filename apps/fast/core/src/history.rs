//! Calendar days, period summaries and search over the archive.

use crate::archive::FastArchive;
use crate::model::*;
use crate::text::{standard_contains, trimmed};
use crate::time::{self, CalendarSpec, seconds_between};
use std::time::SystemTime;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct DaySummary {
    /// The day's first instant.
    pub date: SystemTime,
    /// Recorded fasting time inside the day, in seconds (actual elapsed time).
    pub seconds: f64,
    pub fast_ids: Vec<Uuid>,
    pub journal_count: i32,
    pub is_rest_day: bool,
    pub plan_count: i32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct PeriodSummary {
    pub seconds: f64,
    pub completed_count: i32,
    pub average_duration: Option<f64>,
    pub goals_met: i32,
    pub goals_count: i32,
    pub goal_fraction: Option<f64>,
}

/// End-exclusive boundaries prevent double-counting records ending exactly at midnight.
#[uniffi::export]
pub fn history_overlap(start: SystemTime, end: SystemTime, interval_start: SystemTime, interval_end: SystemTime) -> f64 {
    seconds_between(end.min(interval_end), start.max(interval_start)).max(0.0)
}

fn day_with(date: SystemTime, archive: &FastArchive, tz: &jiff::tz::TimeZone, now: SystemTime) -> Option<DaySummary> {
    let (start, end) = time::day_interval(date, tz)?;
    let touching: Vec<&FastRecord> =
        archive.fasts.iter().filter(|f| history_overlap(f.start, f.end.unwrap_or(now), start, end) > 0.0).collect();
    Some(DaySummary {
        date: start,
        seconds: touching.iter().map(|f| history_overlap(f.start, f.end.unwrap_or(now), start, end)).sum(),
        fast_ids: touching.iter().map(|f| f.id).collect(),
        journal_count: archive.journal.iter().filter(|j| j.date >= start && j.date < end).count() as i32,
        is_rest_day: archive.rest_days.iter().any(|r| time::same_day(r.date, date, tz)),
        plan_count: archive.plans.iter().filter(|p| p.start >= start && p.start < end).count() as i32,
    })
}

#[uniffi::export]
pub fn history_day(date: SystemTime, archive: &FastArchive, calendar: &CalendarSpec, now: SystemTime) -> Option<DaySummary> {
    day_with(date, archive, &time::zone(calendar), now)
}

/// Every day from the one containing `start` up to `end`, at most 3,700.
#[uniffi::export]
pub fn history_days(start: SystemTime, end: SystemTime, archive: &FastArchive, calendar: &CalendarSpec, now: SystemTime) -> Vec<DaySummary> {
    let tz = time::zone(calendar);
    let mut result = vec![];
    let Some(mut date) = time::start_of_day(start, &tz) else { return result };
    while date < end && result.len() < 3_700 {
        let Some(day) = day_with(date, archive, &tz, now) else { break };
        result.push(day);
        match time::add_days(date, 1, &tz).and_then(|d| time::start_of_day(d, &tz)) {
            Some(next) if next > date => date = next,
            _ => break,
        }
    }
    result
}

#[uniffi::export]
pub fn history_summary(start: SystemTime, end: SystemTime, archive: &FastArchive, now: SystemTime) -> PeriodSummary {
    let completed: Vec<&FastRecord> = archive.fasts.iter().filter(|f| f.end.map(|e| e >= start && e < end).unwrap_or(false)).collect();
    let duration: f64 = completed.iter().map(|f| fast_duration(f, now)).sum();
    let goals_met = completed.iter().filter(|f| fast_met_goal(f) == Some(true)).count() as i32;
    let goals_count = completed.iter().filter(|f| f.goal_minutes.is_some()).count() as i32;
    PeriodSummary {
        seconds: archive.fasts.iter().map(|f| history_overlap(f.start, f.end.unwrap_or(now), start, end)).sum(),
        completed_count: completed.len() as i32,
        average_duration: if completed.is_empty() { None } else { Some(duration / completed.len() as f64) },
        goals_met,
        goals_count,
        goal_fraction: if goals_count == 0 { None } else { Some(goals_met as f64 / goals_count as f64) },
    }
}

/// The weeks covering the month that contains `date`, as each day's first instant, starting on
/// the calendar's first weekday.
#[uniffi::export]
pub fn history_month_grid(date: SystemTime, calendar: &CalendarSpec) -> Vec<SystemTime> {
    let tz = time::zone(calendar);
    let Some(z) = time::zoned(date, &tz) else { return vec![] };
    let first = z.date().first_of_month();
    let Ok(month_start) = first.to_zoned(tz.clone()).and_then(|m| m.start_of_day()) else { return vec![] };
    let weekday = time::foundation_weekday(first.weekday());
    let leading = (weekday - calendar.first_weekday + 7).rem_euclid(7) as i64;
    let length = first.days_in_month() as i64;
    let count = ((length + leading + 6) / 7) * 7;
    let start = time::instant(&month_start);
    (0..count)
        .filter_map(|n| time::add_days(start, n - leading, &tz))
        .collect()
}

/// Empty queries match everything; otherwise intention, reflection and tags, ignoring case and accents.
#[uniffi::export]
pub fn history_matches(fast: &FastRecord, query: String) -> bool {
    trimmed(&query).is_empty() || standard_contains(&fast_searchable_text(fast), &query)
}
