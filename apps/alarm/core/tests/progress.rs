//! Progress inside a pending mission survives reopening it, belongs to that one ringing, and is
//! cleared when the mission ends (src/progress.rs).

use alarm_core::*;
use std::time::SystemTime;
use uuid::Uuid;

fn row(mission: MissionKind) -> Registration {
    let mut routine = new_routine(RoutineKind::Alarm, SystemTime::now());
    routine.mission = mission;
    routine.code = "kitchen".into();
    let mut row = new_registration(Uuid::new_v4(), routine);
    row.minute = Some(420);
    row.weekdays = vec![2];
    row.occurrence_id = Some(Uuid::new_v4());
    row
}

fn opened(row: &Registration) -> LoopArchive {
    archive_open_mission(new_loop_archive(), row.clone())
}

#[test]
fn reopening_resumes_mid_way_and_survives_the_file() {
    let wake = row(MissionKind::Arithmetic);
    let mut progress = mission_progress_start(&wake);
    assert_eq!(progress.occurrence_id, wake.occurrence_id.unwrap());
    progress.solved = 2;
    let archive = archive_with_progress(opened(&wake), progress.clone());
    // Written and read back, as a relaunch does.
    let reread = backup_decode(&store_encode(&archive).unwrap()).unwrap();
    assert_eq!(mission_progress(&reread), Some(progress.clone()));
    // Open again on the same ringing (the Open button pressed twice): still there.
    let again = archive_open_mission(reread, wake.clone());
    assert_eq!(mission_progress(&again).map(|p| p.solved), Some(2));
}

#[test]
fn every_kind_of_progress_round_trips() {
    for (mission, solved, tiles, matched) in [
        (MissionKind::Walk, 17, vec![], false),
        (MissionKind::Shake, 9, vec![], false),
        (MissionKind::NumberOrder, 4, vec![3, 1, 6, 2, 5, 4], false),
        (MissionKind::Memory, 0, vec![8, 0, 4], false),
        (MissionKind::ColorWord, 3, vec![], false),
        (MissionKind::Code, 0, vec![], true),
    ] {
        let r = row(mission);
        let progress = MissionProgress { solved, tiles, matched, ..mission_progress_start(&r) };
        let archive = archive_with_progress(opened(&r), progress.clone());
        let reread = backup_decode(&store_encode(&archive).unwrap()).unwrap();
        assert_eq!(mission_progress(&reread), Some(progress), "{mission:?}");
    }
}

#[test]
fn a_different_alarm_does_not_inherit_progress() {
    let first = row(MissionKind::Arithmetic);
    let second = row(MissionKind::Arithmetic);
    let progress = MissionProgress { solved: 2, ..mission_progress_start(&first) };
    let archive = archive_with_progress(opened(&first), progress.clone());
    // Saving progress for an alarm that is not pending changes nothing.
    let stray = MissionProgress { solved: 1, ..mission_progress_start(&second) };
    assert_eq!(archive_with_progress(archive.clone(), stray), archive);
    // Opening the second supersedes the first's progress.
    let superseded = archive_open_mission(archive, second.clone());
    assert_eq!(mission_progress(&superseded), None);
    assert_eq!(superseded.mission_progress, None);
    // Next week's ringing of the same registration is a new occurrence.
    let mut next_week = first.clone();
    next_week.occurrence_id = Some(Uuid::new_v4());
    let archive = archive_with_progress(opened(&first), progress);
    assert_eq!(mission_progress(&archive_open_mission(archive, next_week)), None);
}

#[test]
fn progress_never_crosses_to_a_different_mission() {
    let wake = row(MissionKind::Walk);
    let wrong = MissionProgress { mission: MissionKind::Shake, solved: 5, ..mission_progress_start(&wake) };
    assert_eq!(opened(&wake), archive_with_progress(opened(&wake), wrong.clone()));
    let mut archive = opened(&wake);
    archive.mission_progress = Some(wrong);
    assert_eq!(mission_progress(&archive), None);
}

#[test]
fn completing_or_an_emergency_stop_clears_it() {
    for kind in [EventKind::Completed, EventKind::EmergencyStop, EventKind::Dismissed] {
        let wake = row(MissionKind::NumberOrder);
        let progress = MissionProgress { solved: 3, tiles: vec![2, 1, 3, 4, 5, 6], ..mission_progress_start(&wake) };
        let ended = archive_end_mission(archive_with_progress(opened(&wake), progress), &wake, kind);
        assert_eq!(ended.pending, None, "{kind:?}");
        assert_eq!(ended.mission_progress, None, "{kind:?}");
        // Reopened later (the same registration's receipt), nothing comes back.
        assert_eq!(mission_progress(&archive_open_mission(ended, wake.clone())), None, "{kind:?}");
    }
}

#[test]
fn a_snooze_keeps_it_for_the_same_ringing() {
    let wake = row(MissionKind::Arithmetic);
    let progress = MissionProgress { solved: 1, ..mission_progress_start(&wake) };
    let snoozed = archive_end_mission(archive_with_progress(opened(&wake), progress), &wake, EventKind::Snoozed);
    assert_eq!(snoozed.pending, None);
    assert_eq!(mission_progress(&snoozed), None, "nothing is pending while the snooze waits");
    let snooze = follow_on_registration(&wake, 5, "snooze".into(), SystemTime::now());
    let reopened = archive_open_mission(snoozed, snooze);
    assert_eq!(mission_progress(&reopened).map(|p| p.solved), Some(1));
}

#[test]
fn a_stop_for_another_alarm_leaves_the_pending_mission_alone() {
    let wake = row(MissionKind::Shake);
    let other = row(MissionKind::None);
    let progress = MissionProgress { solved: 12, ..mission_progress_start(&wake) };
    let archive = archive_with_progress(opened(&wake), progress.clone());
    let after = archive_end_mission(archive, &other, EventKind::Dismissed);
    assert_eq!(after.pending.as_ref().map(|p| p.id), Some(wake.id));
    assert_eq!(mission_progress(&after), Some(progress));
}

#[test]
fn old_archives_read_and_progress_is_validated() {
    // An archive written before progress existed has no key and writes none back.
    let archive = opened(&row(MissionKind::Memory));
    let text = String::from_utf8(store_encode(&archive).unwrap()).unwrap();
    assert!(!text.contains("missionProgress"));
    let wake = row(MissionKind::Memory);
    let mut bad = opened(&wake);
    bad.mission_progress = Some(MissionProgress { tiles: vec![0; 65], ..mission_progress_start(&wake) });
    assert!(matches!(store_encode(&bad), Err(LoopError::Invalid { reason }) if reason == "Invalid mission progress."));
}

#[test]
fn imports_never_bring_progress() {
    let wake = row(MissionKind::Arithmetic);
    let incoming = archive_with_progress(opened(&wake), MissionProgress { solved: 2, ..mission_progress_start(&wake) });
    let merged = backup_merge(&incoming, &new_loop_archive()).unwrap();
    assert_eq!(merged.pending, None);
    assert_eq!(merged.mission_progress, None);
}
