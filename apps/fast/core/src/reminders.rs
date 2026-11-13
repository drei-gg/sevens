//! Which reminders should exist right now. Scheduling them stays with each platform.

use crate::archive::FastArchive;
use crate::model::fast_goal_date;
use crate::time::add_seconds;
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
