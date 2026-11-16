//! The rules iOS keeps in its app model (`AlarmModel` in AlarmUI) around native delivery: which
//! registrations the device should hold, the one-off extras (snooze, wake-up check), when a
//! receipt may be pruned, naps and duplicates. Added for the Android app, which has no AlarmKit
//! to repeat weekly alarms or answer "when does this ring next", so it asks here. The Swift app
//! keeps its own copy until it moves onto these; behaviour is the same, line for line.

use crate::model::*;
use crate::schedule::{next_date, schedule_identifier, schedule_registrations};
use crate::text::characters;
use crate::time::{CalendarSpec, as_swift_date, system_time, timestamp, zone};
use std::collections::HashSet;
use std::time::{Duration, SystemTime};
use unicode_segmentation::UnicodeSegmentation;
use uuid::Uuid;

/// Quiet reminders a device may hold at once (iOS: the notification queue's bound).
pub const MAX_REMINDERS: usize = 60;

/// Swift `AlarmModel.desired()`: every routine's registrations, with weekly quiet reminders split
/// into one registration per weekday (each its own native notification, keyed
/// `weekday:<day>` under the registration's ID). Refuses more than 60 reminders rather than
/// silently dropping some.
#[uniffi::export]
pub fn desired_registrations(archive: &LoopArchive, now: SystemTime) -> Result<Vec<Registration>, LoopError> {
    let mut rows = Vec::new();
    for routine in &archive.routines {
        for row in schedule_registrations(routine, now)? {
            if routine.delivery == Delivery::Reminder && row.date.is_none() {
                for day in &row.weekdays {
                    let mut copy = row.clone();
                    copy.id = schedule_identifier(row.id, format!("weekday:{day}"));
                    copy.weekdays = vec![*day];
                    rows.push(copy);
                }
            } else {
                rows.push(row);
            }
        }
    }
    if rows.iter().filter(|r| r.routine.delivery == Delivery::Reminder).count() > MAX_REMINDERS {
        return Err(invalid(
            "These routines need more than 60 quiet reminders. Reduce their times or weekdays; no reminders were silently dropped.",
        ));
    }
    Ok(rows)
}

/// The next time a registration rings strictly after `now`: its date for a one-off, otherwise the
/// earliest of its weekly minute on its weekdays in `calendar`, read like Foundation's
/// `nextDate(.nextTime, .first)` (a time in a daylight-saving gap rings when the gap ends, a
/// repeated time the first time). `None` once a one-off has passed.
#[uniffi::export]
pub fn registration_next(registration: &Registration, now: SystemTime, calendar: CalendarSpec) -> Option<SystemTime> {
    if let Some(date) = registration.date {
        return (date > now).then_some(date);
    }
    let minute = registration.minute?;
    let tz = zone(&calendar);
    let after = timestamp(now)?;
    registration.weekdays.iter().filter_map(|day| next_date(after, minute, *day, &tz)).min().map(system_time)
}

/// Swift `AlarmModel.extra(_:minutes:purpose:)`'s registration: a one-off `minutes` from `now`
/// belonging to the ringing that started it, so snoozes and wake-up checks from different days
/// never share an ID. A snooze counts itself.
#[uniffi::export]
pub fn follow_on_registration(row: &Registration, minutes: i32, purpose: String, now: SystemTime) -> Registration {
    let occurrence = row.occurrence_id.unwrap_or(row.id);
    let mut extra = row.clone();
    extra.id = schedule_identifier(occurrence, format!("{purpose}:{}", row.snoozes));
    extra.date = Some(as_swift_date(now + Duration::from_secs(minutes.max(0) as u64 * 60)));
    extra.minute = None;
    extra.weekdays = vec![];
    extra.occurrence_id = Some(occurrence);
    extra.snoozes = if purpose == "snooze" { row.snoozes + 1 } else { row.snoozes };
    extra.purpose = purpose;
    extra
}

/// Swift `AlarmModel.synchronize()`'s pruning rule for a receipt no longer wanted: keep a live
/// extra (snooze, check, test) of an enabled routine, the open mission's row, and anything that
/// rang within the hour, so the Open and Stop actions that may run right after can still find it.
#[uniffi::export]
pub fn receipt_keep(row: &Registration, archive: &LoopArchive, live: bool, now: SystemTime) -> bool {
    let enabled = archive.routines.iter().any(|r| r.id == row.routine.id && r.enabled) || row.purpose == "test";
    let future_extra = row.purpose != "schedule" && row.date.is_some_and(|d| d > now);
    let active_extra = enabled && (future_extra || (row.date.is_some() && live));
    let just_rang = row.date.is_some_and(|d| d <= now && now.duration_since(d).map(|e| e.as_secs() < 3600).unwrap_or(true));
    active_extra || archive.pending.as_ref().is_some_and(|p| p.id == row.id) || just_rang
}

/// Swift `synchronize()`'s first step: a one-off that has rung is finished, so it reads as off
/// rather than enabled with a past date. Returns the routines with those turned off.
#[uniffi::export]
pub fn routines_finish_past_once(routines: Vec<Routine>, now: SystemTime) -> Vec<Routine> {
    routines
        .into_iter()
        .map(|mut r| {
            if r.timing == Timing::Once && r.enabled && r.once <= now {
                r.enabled = false;
            }
            r
        })
        .collect()
}

/// Swift `AlarmModel.nap(minutes:)`'s routine: a one-off "N-minute nap" from the alarm defaults.
#[uniffi::export]
pub fn nap_routine(defaults: Option<AlarmDefaults>, minutes: i32, now: SystemTime) -> Result<Routine, LoopError> {
    if !(1..=720).contains(&minutes) {
        return Err(invalid("Choose a nap between 1 and 720 minutes."));
    }
    let mut r = crate::model::alarm_defaults_routine(&defaults.unwrap_or_default(), RoutineKind::Alarm, now);
    r.name = format!("{minutes}-minute nap");
    r.timing = Timing::Once;
    r.once = as_swift_date(now + Duration::from_secs(minutes as u64 * 60));
    Ok(r)
}

/// Swift `AlarmModel.duplicate(_:)`: a new ID, " copy" on the name (kept to 100 characters), and
/// turned off.
#[uniffi::export]
pub fn duplicate_routine(routine: &Routine) -> Routine {
    let mut copy = routine.clone();
    copy.id = Uuid::new_v4();
    let name = format!("{} copy", routine.name);
    copy.name = if characters(&name) > 100 { name.graphemes(true).take(100).collect() } else { name };
    copy.enabled = false;
    copy
}

/// Swift `AlarmModel.save(_:)`'s checks before anything is written: the routine validates, it
/// compiles, and an enabled one-off is in the future.
#[uniffi::export]
pub fn routine_check_save(routine: &Routine, now: SystemTime) -> Result<(), LoopError> {
    routine_validate(routine)?;
    schedule_registrations(routine, now)?;
    if routine.timing == Timing::Once && routine.enabled && routine.once <= now {
        return Err(invalid("Choose a future date for this alarm."));
    }
    Ok(())
}

/// The distinct names of the routines' groups, trimmed and sorted (RoutinesScreen `groups`).
#[uniffi::export]
pub fn routine_groups(routines: &[Routine]) -> Vec<String> {
    let set: HashSet<String> =
        routines.iter().filter_map(|r| r.group.as_deref().map(|g| g.trim().to_string())).filter(|g| !g.is_empty()).collect();
    let mut groups: Vec<String> = set.into_iter().collect();
    groups.sort();
    groups
}
