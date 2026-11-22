//! When each Fast reminder is next due (reminder_schedule) and what a late wake may still show
//! (reminders_due). These replaced Kotlin's ReminderPlan.next on 2026-11-22; wall-clock times now
//! follow Foundation on both platforms.

use fast_core::*;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn at(seconds: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(seconds)
}

fn cal(zone: &str) -> CalendarSpec {
    CalendarSpec { time_zone_id: zone.into(), first_weekday: 1 }
}

fn weekly(day: i32, hour: i32, minute: i32) -> FastArchive {
    let mut archive = new_archive();
    archive.settings.reminder_weekdays = vec![day];
    archive.settings.reminder_hour = hour;
    archive.settings.reminder_minute = minute;
    archive
}

#[test]
fn weekly_reminders_are_due_at_their_next_wall_clock_time() {
    // Monday 2026-11-09 00:00 UTC; the Wednesday 20:00 reminder in Manila (UTC+8) is 12:00 UTC.
    let times = reminder_schedule(&weekly(4, 20, 0), at(1_794_182_400), cal("Asia/Manila"));
    assert_eq!(times.len(), 1);
    assert_eq!(times[0].id, "fast.weekly.4");
    assert_eq!(times[0].at, at(1_794_398_400));
    assert_eq!(times[0].post_until, at(1_794_398_400 + 86_400));
}

#[test]
fn a_time_in_the_spring_gap_is_due_when_the_gap_ends() {
    // Sunday 2026-03-08, New York skips 02:00–03:00. 02:30 is due at 03:00 EDT (07:00 UTC), as iOS's
    // calendar trigger fires it; java.time would have said 03:30.
    let times = reminder_schedule(&weekly(1, 2, 30), at(1_772_946_000), cal("America/New_York"));
    assert_eq!(times[0].at, at(1_772_953_200));
}

#[test]
fn water_is_daily_and_lapses_after_half_an_hour() {
    let mut archive = new_archive();
    archive.settings.water.reminders_enabled = true;
    let now = at(1_794_182_400); // 00:00 UTC
    let times = reminder_schedule(&archive, now, cal("UTC"));
    let water: Vec<_> = times.iter().filter(|t| t.kind == ReminderKind::Water).collect();
    assert_eq!(water.len(), 6);
    let nine = water.iter().find(|t| t.id == "fast.water.9").unwrap();
    assert_eq!(nine.at, at(1_794_182_400 + 9 * 3_600));
    assert_eq!(nine.post_until, at(1_794_182_400 + 9 * 3_600 + 1_800));
    // Just after 09:00 the next 09:00 is tomorrow.
    let later = reminder_schedule(&archive, at(1_794_182_400 + 9 * 3_600), cal("UTC"));
    assert_eq!(later.iter().find(|t| t.id == "fast.water.9").unwrap().at, at(1_794_182_400 + 33 * 3_600));
}

#[test]
fn due_lists_what_came_due_in_the_window() {
    let mut archive = weekly(2, 7, 0); // Monday 07:00
    archive.settings.water.reminders_enabled = true;
    let monday = 1_794_182_400;
    let due = reminders_due(&archive, at(monday + 6 * 3_600), at(monday + 10 * 3_600), cal("UTC"));
    let ids: Vec<_> = due.iter().map(|t| t.id.as_str()).collect();
    assert_eq!(ids, vec!["fast.weekly.2", "fast.water.9"]);
    assert!(reminders_due(&archive, at(monday + 10 * 3_600), at(monday + 10 * 3_600 + 60), cal("UTC")).is_empty());
}

#[test]
fn one_offs_keep_their_moment() {
    let now = at(1_790_000_000);
    let mut archive = new_archive();
    archive.settings.goal_reminder = true;
    let mut record = new_fast_record(now, "UTC".into());
    record.goal_minutes = Some(60);
    archive.fasts = vec![record];
    let times = reminder_schedule(&archive, now, cal("UTC"));
    assert_eq!(times.len(), 1);
    assert_eq!(times[0].kind, ReminderKind::Goal);
    assert_eq!(times[0].at, at(1_790_003_600));
    // Replayed from just before it, it is due; from after it, gone.
    assert_eq!(reminders_due(&archive, at(1_790_003_599), at(1_790_003_700), cal("UTC")).len(), 1);
    assert!(reminders_due(&archive, at(1_790_003_600), at(1_790_003_700), cal("UTC")).is_empty());
}
