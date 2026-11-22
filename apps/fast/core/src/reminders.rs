//! Which reminders should exist right now, and when each is next due. Delivering them stays with
//! each platform (SevenKit's schedulers).

use crate::archive::FastArchive;
use crate::model::fast_goal_date;
use crate::time::{CalendarSpec, add_seconds};
use crate::water::water_reminder_hours;
use std::time::SystemTime;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ReminderKind {
    Goal,
    Eating,
    Planned,
    Weekly,
    Water,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReminderEvent {
    pub id: String,
    pub kind: ReminderKind,
    /// A one-off reminder's moment.
    pub date: Option<SystemTime>,
    /// Repeating reminders: weekday 1 Sunday … 7 Saturday, hour and minute.
    pub weekday: Option<i32>,
    pub hour: Option<i32>,
    pub minute: Option<i32>,
}

fn upper(id: &uuid::Uuid) -> String {
    id.hyphenated().to_string().to_uppercase()
}

#[uniffi::export]
pub fn reminder_events(archive: &FastArchive, now: SystemTime) -> Vec<ReminderEvent> {
    let s = &archive.settings;
    let mut result = vec![];
    let one = |id: String, kind, date| ReminderEvent { id, kind, date: Some(date), weekday: None, hour: None, minute: None };
    if s.goal_reminder {
        if let Some(active) = archive.active() {
            if let Some(goal) = fast_goal_date(active).filter(|g| *g > now) {
                result.push(one(format!("fast.goal.{}", upper(&active.id)), ReminderKind::Goal, goal));
            }
        }
    }
    if s.eating_reminder && archive.active().is_none() {
        if let Some(last) = archive.latest_completed() {
            if let Some(end) = last.end {
                let finish = add_seconds(end, last.eating_minutes as f64 * 60.0);
                if finish > now {
                    result.push(one(format!("fast.eating.{}", upper(&last.id)), ReminderKind::Eating, finish));
                }
            }
        }
    }
    let mut weekdays = s.reminder_weekdays.clone();
    weekdays.sort();
    for day in weekdays {
        result.push(ReminderEvent {
            id: format!("fast.weekly.{day}"),
            kind: ReminderKind::Weekly,
            date: None,
            weekday: Some(day),
            hour: Some(s.reminder_hour),
            minute: Some(s.reminder_minute),
        });
    }
    for hour in water_reminder_hours(&s.water) {
        result.push(ReminderEvent { id: format!("fast.water.{hour}"), kind: ReminderKind::Water, date: None, weekday: None, hour: Some(hour), minute: Some(0) });
    }
    // Reserve room for repeating reminders and the current timer. Nearest plans win the remaining
    // slots; later plans are reconsidered whenever the app reconciles.
    if s.schedule_reminders {
        let mut plans: Vec<_> = archive.plans.iter().filter(|p| p.start > now).collect();
        plans.sort_by(|a, b| a.start.cmp(&b.start));
        let room = 40.min(64usize.saturating_sub(result.len()));
        for plan in plans.into_iter().take(room) {
            result.push(one(format!("fast.plan.{}", upper(&plan.id)), ReminderKind::Planned, plan.start));
        }
    }
    result
}

/// A reminder and the next moment it is due.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ReminderTime {
    pub id: String,
    pub kind: ReminderKind,
    pub at: SystemTime,
    /// The latest moment a late delivery may still show it. A water prompt is only useful near its
    /// hour, so it lapses after 30 minutes; everything else after a day.
    pub post_until: SystemTime,
}

/// Every reminder in [`reminder_events`] with the next moment it is due strictly after `now`: a
/// one-off at its date, a repeating one at its next wall-clock time in `calendar`'s zone (weekly on
/// its weekday, water every day). Wall-clock times follow Foundation, as iOS's calendar triggers
/// do: a time skipped by a daylight-saving gap is due when the gap ends, a repeated time the first
/// time.
#[uniffi::export]
pub fn reminder_schedule(archive: &FastArchive, now: SystemTime, calendar: CalendarSpec) -> Vec<ReminderTime> {
    let tz = seven_schedule::zone(&calendar.time_zone_id);
    let Ok(after) = seven_schedule::jiff::Timestamp::try_from(now) else { return vec![] };
    reminder_events(archive, now)
        .into_iter()
        .filter_map(|e| {
            let at = match e.date {
                Some(date) => date,
                None => {
                    let minute = e.hour.unwrap_or(0) * 60 + e.minute.unwrap_or(0);
                    let next = match e.weekday {
                        Some(day) => seven_schedule::next_weekly(after, minute, day, &tz),
                        None => seven_schedule::next_daily(after, minute, &tz),
                    }?;
                    SystemTime::from(next)
                }
            };
            let grace = if e.kind == ReminderKind::Water { 30.0 * 60.0 } else { 86_400.0 };
            Some(ReminderTime { id: e.id, kind: e.kind, at, post_until: add_seconds(at, grace) })
        })
        .collect()
}

/// The reminders that came due after `after` and by `now`: what a late or replayed wake should
/// consider showing. Each still carries its `post_until`; the platform skips ones that lapsed.
#[uniffi::export]
pub fn reminders_due(archive: &FastArchive, after: SystemTime, now: SystemTime, calendar: CalendarSpec) -> Vec<ReminderTime> {
    reminder_schedule(archive, after, calendar).into_iter().filter(|r| r.at <= now).collect()
}
