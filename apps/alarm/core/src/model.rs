//! Routines, receipts, history and the archive, with the JSON keys the Swift app's synthesized
//! `Codable` writes. Like Swift's synthesized decoder, every non-optional key is required and
//! optional keys may be missing or null; a nil optional is left out when encoding.

use crate::text::{characters, trimmed};
use crate::time::{as_swift_date, refdate, refdate_opt, upper, upper_opt};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::time::{Duration, SystemTime};
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum LoopError {
    /// Swift `LoopError.invalid(message)`: the message is shown to the person as is.
    #[error("{reason}")]
    Invalid { reason: String },
    /// Swift's `DecodingError`: the bytes are not a well-formed archive.
    #[error("unreadable: {detail}")]
    Unreadable { detail: String },
}

pub(crate) fn invalid(message: &str) -> LoopError {
    LoopError::Invalid { reason: message.to_string() }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum RoutineKind {
    Alarm,
    Loop,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum Delivery {
    Alarm,
    Reminder,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum MissionKind {
    None,
    Arithmetic,
    Typing,
    Code,
    Memory,
    Shake,
    Walk,
    NumberOrder,
    ColorWord,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum AlarmTone {
    System,
    Chime,
    Pulse,
    Bright,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum Timing {
    Weekly,
    Once,
    Window,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum EventKind {
    Completed,
    Dismissed,
    Snoozed,
    EmergencyStop,
    Manual,
    FollowUpScheduled,
}

/// One shared time: minutes after midnight on Foundation weekdays (1 Sunday … 7 Saturday).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct TimeSlot {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub minute: i32,
    pub weekdays: Vec<i32>,
}

/// A device-local asset reference, not an external path or a promise that a backup contains audio.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct ImportedSound {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
}

/// Swift `TimeSlot(minute:weekdays:)` with a fresh identifier.
#[uniffi::export]
pub fn new_time_slot(minute: i32, weekdays: Vec<i32>) -> TimeSlot {
    TimeSlot { id: Uuid::new_v4(), minute, weekdays }
}

/// Swift `TimeSlot()`: 07:00 on weekdays.
#[uniffi::export]
pub fn default_time_slot() -> TimeSlot {
    new_time_slot(420, vec![2, 3, 4, 5, 6])
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct Routine {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub reason: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tone: Option<AlarmTone>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub imported_sound: Option<ImportedSound>,
    pub kind: RoutineKind,
    pub enabled: bool,
    pub delivery: Delivery,
    pub timing: Timing,
    pub slots: Vec<TimeSlot>,
    #[serde(with = "refdate")]
    pub once: SystemTime,
    pub window_start: i32,
    pub window_end: i32,
    pub interval_minutes: i32,
    pub window_days: Vec<i32>,
    pub mission: MissionKind,
    pub phrase: String,
    pub code: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub challenge_level: Option<i32>,
    pub math_count: i32,
    pub snooze_minutes: i32,
    pub snooze_limit: i32,
    pub follow_up_minutes: i32,
}

/// Swift `Routine(kind:)`. `now` stands in for `Date()`: a one-off defaults to an hour from now.
/// A loop is named "Take a break" and delivered as a reminder.
#[uniffi::export]
pub fn new_routine(kind: RoutineKind, now: SystemTime) -> Routine {
    let is_loop = kind == RoutineKind::Loop;
    Routine {
        id: Uuid::new_v4(),
        name: if is_loop { "Take a break" } else { "Wake up" }.to_string(),
        reason: None,
        group: None,
        tone: None,
        imported_sound: None,
        kind,
        enabled: true,
        delivery: if is_loop { Delivery::Reminder } else { Delivery::Alarm },
        timing: Timing::Weekly,
        slots: vec![default_time_slot()],
        once: as_swift_date(now + Duration::from_secs(3600)),
        window_start: 540,
        window_end: 1020,
        interval_minutes: 60,
        window_days: vec![2, 3, 4, 5, 6],
        mission: MissionKind::None,
        phrase: "I am awake".to_string(),
        code: String::new(),
        challenge_level: None,
        math_count: 3,
        snooze_minutes: 5,
        snooze_limit: 3,
        follow_up_minutes: 0,
    }
}

/// Swift `Routine.difficulty`: the challenge level clamped to 1…3; older backups without one get 1.
#[uniffi::export]
pub fn routine_difficulty(routine: &Routine) -> i32 {
    routine.challenge_level.unwrap_or(1).clamp(1, 3)
}

fn distinct_weekdays(days: &[i32]) -> bool {
    !days.is_empty() && days.iter().collect::<HashSet<_>>().len() == days.len() && days.iter().all(|d| (1..=7).contains(d))
}

fn require(value: bool, message: &str) -> Result<(), LoopError> {
    if value { Ok(()) } else { Err(invalid(message)) }
}

/// Swift `Routine.validate()`: the same checks in the same order, so the first message matches.
#[uniffi::export]
pub fn routine_validate(routine: &Routine) -> Result<(), LoopError> {
    let r = routine;
    require(r.imported_sound.as_ref().is_none_or(|sound| !trimmed(&sound.name).is_empty() && characters(&sound.name) <= 120), "Keep the imported sound name between 1 and 120 characters.")?;
    require(
        r.reason.as_deref().map(characters).unwrap_or(0) <= 300 && r.group.as_deref().map(characters).unwrap_or(0) <= 60,
        "Keep the reason under 300 characters and the group under 60.",
    )?;
    require(!trimmed(&r.name).is_empty() && characters(&r.name) <= 100, "Give the routine a name of up to 100 characters.")?;
    require(r.slots.len() <= 48 && !r.slots.is_empty(), "Use between 1 and 48 shared times.")?;
    require(r.slots.iter().map(|s| s.id).collect::<HashSet<_>>().len() == r.slots.len(), "Duplicate time identifiers.")?;
    for slot in &r.slots {
        require(
            (0..1440).contains(&slot.minute) && distinct_weekdays(&slot.weekdays),
            "Choose a valid time and at least one weekday for each shared time.",
        )?;
    }
    require(
        (0..1440).contains(&r.window_start) && (0..1440).contains(&r.window_end) && r.window_start != r.window_end && (5..=720).contains(&r.interval_minutes),
        "Choose different window endpoints and an interval from 5 to 720 minutes.",
    )?;
    require(distinct_weekdays(&r.window_days), "Choose at least one weekday.")?;
    // Swift checks `once` is finite; a SystemTime always is.
    require(
        (1..=10).contains(&r.math_count) && (1..=30).contains(&r.snooze_minutes) && (0..=10).contains(&r.snooze_limit) && (0..=30).contains(&r.follow_up_minutes),
        "Mission, snooze or follow-up settings are out of range.",
    )?;
    require(r.challenge_level.is_none_or(|l| (1..=3).contains(&l)), "Choose a difficulty from 1 to 3.")?;
    require(characters(&r.phrase) <= 200 && r.code.len() <= 4096, "Mission text is too long.")?;
    if r.mission == MissionKind::Typing {
        require(!trimmed(&r.phrase).is_empty(), "Enter a phrase for the typing mission.")?;
    }
    if r.mission == MissionKind::Code {
        require(!r.code.is_empty(), "Scan a code before enabling this mission.")?;
    }
    if r.delivery == Delivery::Reminder {
        require(r.mission == MissionKind::None && r.follow_up_minutes == 0, "Missions and wake-up checks require alarm delivery.")?;
    }
    Ok(())
}

/// A native alarm the app asked the system for: a weekly minute, or a one-off date.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Registration {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub routine: Routine,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub minute: Option<i32>,
    pub weekdays: Vec<i32>,
    #[serde(with = "refdate_opt", default, skip_serializing_if = "Option::is_none")]
    pub date: Option<SystemTime>,
    pub purpose: String,
    #[serde(rename = "occurrenceID", with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub occurrence_id: Option<Uuid>,
    pub snoozes: i32,
}

/// Swift `Registration(id:routine:)` with its defaults: no minute or date, no weekdays, purpose
/// "schedule", no occurrence, no snoozes.
#[uniffi::export]
pub fn new_registration(id: Uuid, routine: Routine) -> Registration {
    Registration { id, routine, minute: None, weekdays: vec![], date: None, purpose: "schedule".into(), occurrence_id: None, snoozes: 0 }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LoopEvent {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(rename = "routineID", with = "upper")]
    pub routine_id: Uuid,
    pub name: String,
    #[serde(with = "refdate")]
    pub date: SystemTime,
    pub kind: EventKind,
    pub note: String,
}

/// Swift `LoopEvent(routine:kind:date:note:)`.
#[uniffi::export]
pub fn new_loop_event(routine: &Routine, kind: EventKind, date: SystemTime, note: String) -> LoopEvent {
    LoopEvent { id: Uuid::new_v4(), routine_id: routine.id, name: routine.name.clone(), date: as_swift_date(date), kind, note }
}

/// Defaults only affect newly created alarms. Existing schedules keep their own settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct AlarmDefaults {
    pub tone: AlarmTone,
    pub snooze_minutes: i32,
    pub snooze_limit: i32,
    pub follow_up_minutes: i32,
}

impl Default for AlarmDefaults {
    fn default() -> Self {
        Self { tone: AlarmTone::System, snooze_minutes: 5, snooze_limit: 3, follow_up_minutes: 0 }
    }
}

/// Swift `AlarmDefaults()`.
#[uniffi::export]
pub fn default_alarm_defaults() -> AlarmDefaults {
    AlarmDefaults::default()
}

#[uniffi::export]
pub fn alarm_defaults_validate(defaults: &AlarmDefaults) -> Result<(), LoopError> {
    require(
        (1..=30).contains(&defaults.snooze_minutes) && (0..=10).contains(&defaults.snooze_limit) && (0..=30).contains(&defaults.follow_up_minutes),
        "Default snooze or wake-up check settings are out of range.",
    )
}

/// Swift `AlarmDefaults.routine(kind:)`: a new routine; only alarms take the defaults.
#[uniffi::export]
pub fn alarm_defaults_routine(defaults: &AlarmDefaults, kind: RoutineKind, now: SystemTime) -> Routine {
    let mut routine = new_routine(kind, now);
    if kind == RoutineKind::Alarm {
        routine.tone = Some(defaults.tone);
        routine.snooze_minutes = defaults.snooze_minutes;
        routine.snooze_limit = defaults.snooze_limit;
        routine.follow_up_minutes = defaults.follow_up_minutes;
    }
    routine
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct LoopArchive {
    pub version: i32,
    /// Optional so archives created before reusable defaults remain readable.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alarm_defaults: Option<AlarmDefaults>,
    pub routines: Vec<Routine>,
    pub events: Vec<LoopEvent>,
    /// Receipts survive relaunch and let reconciliation cancel orphan native alarms.
    pub installed: Vec<Registration>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pending: Option<Registration>,
    /// Progress inside the pending mission, for reopening it (see `progress.rs`). Optional so
    /// archives from before it remain readable; like `pending`, it belongs to this device only.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mission_progress: Option<crate::progress::MissionProgress>,
}

impl Default for LoopArchive {
    fn default() -> Self {
        Self { version: 1, alarm_defaults: None, routines: vec![], events: vec![], installed: vec![], pending: None, mission_progress: None }
    }
}

/// Swift `LoopArchive()`.
#[uniffi::export]
pub fn new_loop_archive() -> LoopArchive {
    LoopArchive::default()
}

fn unique<T: std::hash::Hash + Eq>(ids: impl Iterator<Item = T>, count: usize) -> bool {
    ids.collect::<HashSet<_>>().len() == count
}

/// Swift `LoopArchive.validate()`: used by every save, read and import. Nothing is repaired.
#[uniffi::export]
pub fn loop_archive_validate(archive: &LoopArchive) -> Result<(), LoopError> {
    let a = archive;
    require(a.version == 1, "This backup needs a newer app. Your file has not been changed.")?;
    if let Some(defaults) = &a.alarm_defaults {
        alarm_defaults_validate(defaults)?;
    }
    require(
        a.routines.len() <= 100
            && a.events.len() <= 100_000
            && a.installed.len() <= 1024
            && unique(a.routines.iter().map(|r| r.id), a.routines.len())
            && unique(a.events.iter().map(|e| e.id), a.events.len())
            && unique(a.installed.iter().map(|r| r.id), a.installed.len()),
        "Invalid or oversized archive.",
    )?;
    for r in &a.routines {
        routine_validate(r)?;
    }
    for e in &a.events {
        require(characters(&e.name) <= 100 && characters(&e.note) <= 1000, "Invalid history record.")?;
    }
    for r in a.installed.iter().chain(a.pending.iter()) {
        routine_validate(&r.routine)?;
        require(
            r.minute.is_none_or(|m| (0..1440).contains(&m)) && r.weekdays.iter().all(|d| (1..=7).contains(d)) && (0..=10).contains(&r.snoozes),
            "Invalid alarm receipt.",
        )?;
    }
    if let Some(p) = &a.mission_progress {
        require(
            (0..=10_000).contains(&p.solved) && p.tiles.len() <= 64 && p.tiles.iter().all(|t| (0..=1000).contains(t)),
            "Invalid mission progress.",
        )?;
    }
    Ok(())
}
