//! Progress inside an open mission, kept with the pending mission so leaving it (a call, a
//! mis-swipe, the system ending the app) and opening it again picks up where the person was.
//! Leaving never completes a mission: progress only restores steps already done, and the mission
//! still finishes through its own check. It belongs to one ringing (the pending row's occurrence):
//! completing, an emergency stop or the system Stop clear it, and opening a different alarm
//! supersedes it. A snooze keeps it, because the snooze belongs to the same ringing.

use crate::model::{EventKind, LoopArchive, MissionKind, Registration};
use crate::time::upper;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

/// What the person has done so far in the pending mission. Which fields count depends on the
/// mission:
/// - arithmetic: `solved` questions answered;
/// - Color clash: `solved` rounds answered;
/// - number order: `tiles` the board's layout, `solved` numbers already tapped in order;
/// - memory: `tiles` the sequence to repeat (a reopen replays the same tiles, and a replay starts
///   the repetition over, as the "Show sequence again" control does);
/// - shake and walk: `solved` shakes or steps counted;
/// - code: `matched` once the saved code was scanned, so a completion that could not be recorded
///   does not send the person back to the code.
///
/// Typing and "I'm up" are a single step and keep no progress.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct MissionProgress {
    /// The ringing this belongs to: the pending row's occurrence (its own ID before it has one).
    #[serde(rename = "occurrenceID", with = "upper")]
    pub occurrence_id: Uuid,
    /// The mission it was made for; progress never carries over to a different kind.
    pub mission: MissionKind,
    pub solved: i32,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tiles: Vec<i32>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub matched: bool,
}

fn occurrence(row: &Registration) -> Uuid {
    row.occurrence_id.unwrap_or(row.id)
}

/// No progress yet for `row`'s mission.
#[uniffi::export]
pub fn mission_progress_start(row: &Registration) -> MissionProgress {
    MissionProgress { occurrence_id: occurrence(row), mission: row.routine.mission, solved: 0, tiles: vec![], matched: false }
}

/// The saved progress of the pending mission, or `None` when nothing is pending or what is saved
/// belongs to another ringing or mission.
#[uniffi::export]
pub fn mission_progress(archive: &LoopArchive) -> Option<MissionProgress> {
    let pending = archive.pending.as_ref()?;
    archive.mission_progress.clone().filter(|p| p.occurrence_id == occurrence(pending) && p.mission == pending.routine.mission)
}

/// Saves `progress` for the pending mission. Progress for anything but the pending ringing (a
/// mission that has already ended, a different alarm) is ignored, so a late save cannot revive
/// it.
#[uniffi::export]
pub fn archive_with_progress(archive: LoopArchive, progress: MissionProgress) -> LoopArchive {
    let mut a = archive;
    let belongs = a.pending.as_ref().is_some_and(|p| occurrence(p) == progress.occurrence_id && p.routine.mission == progress.mission);
    if belongs {
        a.mission_progress = Some(progress);
    }
    a
}

/// Makes `row` the pending mission (Open). Progress of the same ringing is kept (reopening, or a
/// snooze of it); anything else is superseded.
#[uniffi::export]
pub fn archive_open_mission(archive: LoopArchive, row: Registration) -> LoopArchive {
    let mut a = archive;
    if a.mission_progress.as_ref().is_some_and(|p| p.occurrence_id != occurrence(&row) || p.mission != row.routine.mission) {
        a.mission_progress = None;
    }
    a.pending = Some(row);
    a
}

/// Ends `row`'s mission with `kind`: it is no longer pending, and its progress is cleared unless
/// it was snoozed (the snooze belongs to the same ringing and opens where the person left it).
/// A Stop for an alarm that is not the pending one leaves the pending mission alone.
#[uniffi::export]
pub fn archive_end_mission(archive: LoopArchive, row: &Registration, kind: EventKind) -> LoopArchive {
    let mut a = archive;
    let was_pending = a.pending.as_ref().is_some_and(|p| p.id == row.id);
    let same_ringing = a.mission_progress.as_ref().is_some_and(|p| p.occurrence_id == occurrence(row));
    if was_pending {
        a.pending = None;
    }
    if kind != EventKind::Snoozed && (was_pending || same_ringing) {
        a.mission_progress = None;
    }
    a
}
