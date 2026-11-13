//! Every FastCore test from the iOS app (Kit/Tests/FastCoreTests), ported one to one, so the
//! crate is held to the behaviour the Swift implementation shipped with. Names match the Swift
//! test functions. Store-level tests (atomic writes, corrupt files) stay with each platform's
//! store and are not here.

use fast_core::*;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn instant(text: &str) -> SystemTime {
    let ts: jiff::Timestamp = text.parse().unwrap();
    UNIX_EPOCH + Duration::from_secs(ts.as_second() as u64)
}
fn at(seconds: f64) -> SystemTime {
    fast_core_test_time(seconds)
}
fn fast_core_test_time(seconds: f64) -> SystemTime {
    if seconds >= 0.0 { UNIX_EPOCH + Duration::from_secs_f64(seconds) } else { UNIX_EPOCH - Duration::from_secs_f64(-seconds) }
}
fn plus(t: SystemTime, seconds: f64) -> SystemTime {
    if seconds >= 0.0 { t + Duration::from_secs_f64(seconds) } else { t - Duration::from_secs_f64(-seconds) }
}
fn now() -> SystemTime {
    instant("2026-11-11T12:00:00Z")
}
fn cal(zone: &str) -> CalendarSpec {
    CalendarSpec { time_zone_id: zone.into(), first_weekday: 2 }
}
fn fast(start: SystemTime) -> FastRecord {
    new_fast_record(start, "UTC".into())
}
fn ended(start: SystemTime, end: SystemTime) -> FastRecord {
    FastRecord { end: Some(end), ..fast(start) }
}
fn journal(date: SystemTime) -> JournalEntry {
    JournalEntry { id: Uuid::new_v4(), date, fast_id: None, text: String::new(), mood: None, energy: None, hunger: None, tags: vec![] }
}
fn metric(date: SystemTime, kind: MetricKind, value: f64) -> MetricEntry {
    MetricEntry { id: Uuid::new_v4(), date, kind, value, external_id: None }
}
fn plan(start: SystemTime, goal: Option<i32>) -> PlannedFast {
    PlannedFast { id: Uuid::new_v4(), start, goal_minutes: goal, eating_minutes: 480, intention: String::new() }
}
fn secs(a: SystemTime, b: SystemTime) -> f64 {
    match a.duration_since(b) {
        Ok(d) => d.as_secs_f64(),
        Err(e) => -e.duration().as_secs_f64(),
    }
}

// MARK: FastCoreTests.swift

#[test]
fn lifecycle_and_multiple_sessions_per_day() {
    let mut archive = new_archive();
    let mut first = FastRecord { goal_minutes: Some(60), ..fast(plus(now(), -36_000.0)) };
    archive = archive_save(&archive, first.clone(), now()).unwrap();
    assert_eq!(archive_active(&archive).map(|f| f.id), Some(first.id));
    first.end = Some(plus(first.start, 7_200.0));
    archive = archive_save(&archive, first.clone(), now()).unwrap();
    assert!(archive_active(&archive).is_none());
    assert_eq!(fast_met_goal(&archive.fasts[0]), Some(true));
    let second = FastRecord { goal_minutes: None, ..ended(first.end.unwrap(), now()) };
    archive = archive_save(&archive, second.clone(), now()).unwrap();
    assert_eq!(archive.fasts.len(), 2);
    assert_eq!(fast_met_goal(&second), None);
}

#[test]
fn invalid_edits_leave_archive_unchanged() {
    let archive = archive_save(&new_archive(), ended(plus(now(), -3_600.0), now()), now()).unwrap();
    let before = archive.clone();
    assert_eq!(archive_save(&archive, ended(plus(now(), -1_800.0), now()), now()), Err(TrackerError::Overlap));
    assert_eq!(archive, before);
    assert_eq!(archive_save(&archive, fast(plus(now(), 1.0)), now()), Err(TrackerError::FutureRecord));
    assert_eq!(archive_save(&archive, ended(now(), now()), now()), Err(TrackerError::InvalidTime));
}

#[test]
fn active_sessions_cannot_overlap_even_with_historical_insertion() {
    let archive = archive_save(&new_archive(), fast(plus(now(), -3_600.0)), now()).unwrap();
    assert_eq!(archive_save(&archive, fast(now()), now()), Err(TrackerError::MultipleActive));
    assert_eq!(archive_save(&archive, ended(plus(now(), -7_200.0), now()), now()), Err(TrackerError::Overlap));
}

#[test]
fn daily_totals_split_midnight_without_duplicating_session_count() {
    let archive = archive_save(&new_archive(), ended(instant("2026-11-09T20:00:00Z"), instant("2026-11-10T12:00:00Z")), now()).unwrap();
    let monday = history_day(instant("2026-11-09T00:00:00Z"), &archive, &cal("UTC"), now()).unwrap();
    let tuesday = history_day(instant("2026-11-10T00:00:00Z"), &archive, &cal("UTC"), now()).unwrap();
    assert_eq!(monday.seconds, 4.0 * 3_600.0);
    assert_eq!(tuesday.seconds, 12.0 * 3_600.0);
    let summary = history_summary(monday.date, instant("2026-11-11T00:00:00Z"), &archive, now());
    assert_eq!(summary.completed_count, 1);
    assert_eq!(summary.seconds, 16.0 * 3_600.0);
    assert_eq!(summary.average_duration, Some(57_600.0));
}

#[test]
fn daylight_saving_days_use_actual_elapsed_hours() {
    for (start, end, hours) in [("2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z", 23.0), ("2026-11-01T04:00:00Z", "2026-11-02T05:00:00Z", 25.0)] {
        let archive = archive_save(&new_archive(), ended(instant(start), instant(end)), now()).unwrap();
        let ny = cal("America/New_York");
        assert_eq!(history_day(instant(start), &archive, &ny, now()).unwrap().seconds, hours * 3_600.0);
        assert_eq!(history_day(instant(end), &archive, &ny, now()).unwrap().seconds, 0.0);
    }
}

#[test]
fn month_grid_includes_leap_day_and_matches_first_weekday() {
    let grid = history_month_grid(instant("2024-02-15T00:00:00Z"), &cal("UTC"));
    assert_eq!(grid.len(), 35);
    let first: jiff::Timestamp = jiff::Timestamp::from_second(secs(grid[0], UNIX_EPOCH) as i64).unwrap();
    assert_eq!(first.to_zoned(jiff::tz::TimeZone::UTC).weekday(), jiff::civil::Weekday::Monday);
    assert!(grid.contains(&instant("2024-02-29T00:00:00Z")));
}

#[test]
fn deletion_preserves_journal_and_detaches_link() {
    let f = fast(now());
    let mut archive = archive_save(&new_archive(), f.clone(), now()).unwrap();
    archive.journal = vec![JournalEntry { fast_id: Some(f.id), text: "Noticed a change.".into(), ..journal(now()) }];
    let archive = archive_delete_fast(&archive, f.id);
    assert_eq!(archive.journal.len(), 1);
    assert_eq!(archive.journal[0].fast_id, None);
    archive_validate(&archive, now()).unwrap();
}

#[test]
fn backup_round_trip_preserves_milliseconds_and_all_kinds() {
    let mut archive = new_archive();
    let f = fast(plus(now(), -0.123));
    archive.fasts = vec![f.clone()];
    archive.journal = vec![JournalEntry { fast_id: Some(f.id), text: "Hello\nworld".into(), mood: Some(3), energy: Some(2), hunger: Some(4), ..journal(now()) }];
    archive.metrics = vec![metric(now(), MetricKind::Weight, 72.3), metric(now(), MetricKind::Water, 250.0)];
    archive.plans = vec![plan(plus(now(), 86_400.0), Some(960))];
    archive.rest_days = vec![RestDay { id: Uuid::new_v4(), date: now(), note: String::new() }];
    archive.presets = vec![FastPreset { id: Uuid::new_v4(), name: "My rhythm".into(), goal_minutes: Some(780), eating_minutes: 660 }];
    let decoded = archive_decode_slice(&archive_encode(&archive).unwrap(), now()).unwrap();
    assert!(secs(decoded.fasts[0].start, f.start).abs() < 0.001);
    assert_eq!(decoded.journal, archive.journal);
    assert_eq!(decoded.metrics, archive.metrics);
    assert_eq!(decoded.plans, archive.plans);
    assert_eq!(decoded.presets, archive.presets);
}

#[test]
fn import_is_idempotent_rejects_conflict_and_overlap() {
    let mut archive = new_archive();
    archive.fasts = vec![ended(plus(now(), -3_600.0), now())];
    assert_eq!(archive_merge(&archive, &archive, now()).unwrap().total_added, 0);
    let mut conflict = archive.clone();
    conflict.fasts[0].reflection = "Different".into();
    assert_eq!(archive_merge(&conflict, &archive, now()).err(), Some(TrackerError::Conflict));
    let mut overlap = archive.clone();
    overlap.fasts[0].id = Uuid::new_v4();
    assert_eq!(archive_merge(&overlap, &archive, now()).err(), Some(TrackerError::Overlap));
}

#[test]
fn malformed_and_future_files_are_not_silently_emptied() {
    assert_eq!(archive_decode_slice(b"broken", now()).err(), Some(TrackerError::Unreadable));
    assert_eq!(archive_decode_slice(br#"{"schemaVersion":2}"#, now()).err(), Some(TrackerError::UnsupportedSchema));
}

#[test]
fn invalid_links_ratings_and_metrics_fail_validation() {
    let mut archive = new_archive();
    archive.journal = vec![JournalEntry { fast_id: Some(Uuid::new_v4()), ..journal(now()) }];
    assert_eq!(archive_validate(&archive, now()), Err(TrackerError::DanglingJournal));
    archive.journal = vec![JournalEntry { mood: Some(6), ..journal(now()) }];
    assert_eq!(archive_validate(&archive, now()), Err(TrackerError::InvalidEntry));
    archive.journal = vec![];
    archive.metrics = vec![metric(now(), MetricKind::Water, -1.0)];
    assert_eq!(archive_validate(&archive, now()), Err(TrackerError::InvalidEntry));
}

#[test]
fn csv_includes_all_records_quotes_and_neutralizes_formulas() {
    let mut archive = new_archive();
    archive.journal = vec![JournalEntry { text: "=HYPERLINK(\"bad\")\nsecond, line".into(), ..journal(now()) }];
    archive.metrics = vec![metric(now(), MetricKind::Weight, 70.0)];
    let csv = archive_csv(&archive);
    assert!(csv.contains("\"'=HYPERLINK(\"\"bad\"\")\nsecond, line\""));
    assert!(csv.contains("\"kg\""));
    assert!(csv.contains("\"70.0\""), "Swift's String(Double) keeps the decimal point");
    assert!(csv.contains("\"2026-11-11T12:00:00.000Z\""));
    assert!(csv.ends_with("\r\n"));
}

#[test]
fn units_round_trip() {
    let settings = TrackerSettings { use_pounds: true, use_fluid_ounces: true, ..default_tracker_settings() };
    for kind in [MetricKind::Weight, MetricKind::Water, MetricKind::Waist, MetricKind::Sleep] {
        let result = units_canonical(units_display(70.0, kind, &settings), kind, &settings);
        assert!((result - 70.0).abs() < 0.000001);
    }
}

#[test]
fn goal_reached_does_not_end_active_record_and_early_finish_is_valid() {
    let record = FastRecord { goal_minutes: Some(60), ..fast(plus(now(), -7_200.0)) };
    assert!(record.end.is_none());
    assert_eq!(fast_duration(&record, now()), 7_200.0);
    let archive = archive_save(&new_archive(), FastRecord { goal_minutes: Some(960), ..ended(plus(now(), -3_600.0), now()) }, now()).unwrap();
    assert_eq!(fast_met_goal(&archive.fasts[0]), Some(false));
}

#[test]
fn duplicate_external_measurements_reject_an_import_without_partial_changes() {
    let mut archive = new_archive();
    archive.metrics = vec![MetricEntry { external_id: Some("health.sample".into()), ..metric(now(), MetricKind::Weight, 70.0) }];
    let mut incoming = new_archive();
    incoming.metrics = vec![MetricEntry { external_id: Some("health.sample".into()), ..metric(now(), MetricKind::Weight, 70.0) }];
    incoming.journal = vec![JournalEntry { text: "Must not get imported on failure".into(), ..journal(now()) }];
    assert_eq!(archive_merge(&incoming, &archive, now()).err(), Some(TrackerError::DuplicateId));
    assert!(archive.journal.is_empty());
}

#[test]
fn older_version_one_settings_get_new_defaults() {
    let mut a: serde_json::Value = serde_json::from_slice(&archive_encode(&new_archive()).unwrap()).unwrap();
    a["settings"] = serde_json::json!({"usePounds": true});
    let decoded = archive_decode_slice(&serde_json::to_vec(&a).unwrap(), now()).unwrap();
    let s = decoded.settings;
    assert!(s.use_pounds);
    assert!(!s.live_activity);
    assert!(s.show_eating_window);
    assert!(s.reminder_weekdays.is_empty());
}

#[test]
fn reading_and_editing_does_not_shift_timezones_or_original_goals() {
    let mut f = FastRecord { goal_minutes: Some(840), time_zone_id: "Asia/Manila".into(), ..ended(instant("2026-11-09T20:00:00Z"), instant("2026-11-10T12:00:00Z")) };
    let archive = archive_save(&new_archive(), f.clone(), now()).unwrap();
    let total = |zone: &str| history_days(instant("2026-11-09T00:00:00Z"), now(), &archive, &cal(zone), now()).iter().map(|d| d.seconds).sum::<f64>();
    assert_eq!(total("Asia/Manila"), total("America/New_York"));
    f.reflection = "A corrected note".into();
    let archive = archive_save(&archive, f, now()).unwrap();
    assert_eq!(archive.fasts[0].time_zone_id, "Asia/Manila");
    assert_eq!(archive.fasts[0].goal_minutes, Some(840));
}

// MARK: BackupPrecisionTests.swift

#[test]
fn backup_of_fractional_timestamps_merges_back_into_its_source() {
    let now = at(1_800_000_000.0);
    for fraction in [0.1234567, 0.9876543, 0.0000001, 0.9999999] {
        let mut archive = new_archive();
        archive.fasts = vec![ended(plus(now, -7_200.0 + fraction), plus(now, -3_600.0 + fraction))];
        archive.journal = vec![JournalEntry { text: "Keep precise times".into(), ..journal(plus(now, -3_000.0 + fraction)) }];
        let copy = archive_decode_slice(&archive_encode(&archive).unwrap(), now).unwrap();
        assert_eq!(archive_merge(&copy, &archive, now).unwrap().total_added, 0);
        let mut edited = copy.clone();
        edited.fasts[0].start = plus(edited.fasts[0].start, 0.01);
        assert_eq!(archive_merge(&edited, &archive, now).err(), Some(TrackerError::Conflict));
        let mut edited = copy.clone();
        edited.fasts[0].reflection = "An actual edit".into();
        assert_eq!(archive_merge(&edited, &archive, now).err(), Some(TrackerError::Conflict));
    }
}

// MARK: ReminderTests.swift

#[test]
fn reminders_follow_actual_end_and_cancel_during_next_fast() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.settings.eating_reminder = true;
    archive.settings.goal_reminder = true;
    archive.fasts = vec![FastRecord { eating_minutes: 480, ..ended(plus(now, -16.0 * 3_600.0), now) }];
    let events = reminder_events(&archive, now);
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].date, Some(plus(now, 8.0 * 3_600.0)));
    archive.fasts.push(FastRecord { goal_minutes: Some(60), ..fast(now) });
    let changed = reminder_events(&archive, now);
    assert_eq!(changed.len(), 1);
    assert_eq!(changed[0].kind, ReminderKind::Goal);
    assert!(reminder_events(&archive, plus(now, 3_601.0)).is_empty());
}

#[test]
fn schedule_never_auto_starts_and_stays_below_notification_limit() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.settings.schedule_reminders = true;
    archive.settings.reminder_weekdays = (1..=7).collect();
    archive.plans = (1..=100).map(|n| plan(plus(now, n as f64 * 86_400.0), Some(960))).collect();
    let events = reminder_events(&archive, now);
    assert_eq!(events.len(), 47);
    assert!(archive_active(&archive).is_none());
    assert!(events.iter().filter(|e| e.kind == ReminderKind::Weekly).all(|e| e.hour == Some(20)));
}

// MARK: DayArcTests.swift

fn arcs_on(day: &str, archive: &FastArchive, now: SystemTime, zone: &str) -> Vec<DayArc> {
    day_arcs(instant(day), archive, &cal(zone), now)
}
fn with_fasts(fasts: Vec<FastRecord>, plans: Vec<PlannedFast>) -> FastArchive {
    FastArchive { fasts, plans, ..new_archive() }
}
fn arc(id: Uuid, kind: DayArcKind, from: f64, to: f64, previous: bool, next: bool) -> DayArc {
    DayArc { fast_id: id, kind, from, to, from_previous_day: previous, into_next_day: next }
}

#[test]
fn overnight_fast_leaves_one_day_at_the_top_and_enters_the_next() {
    let f = ended(instant("2026-11-09T20:00:00Z"), instant("2026-11-10T12:00:00Z"));
    let records = with_fasts(vec![f.clone()], vec![]);
    assert_eq!(arcs_on("2026-11-09T00:00:00Z", &records, now(), "UTC"), vec![arc(f.id, DayArcKind::Actual, 20.0 / 24.0, 1.0, false, true)]);
    assert_eq!(arcs_on("2026-11-10T00:00:00Z", &records, now(), "UTC"), vec![arc(f.id, DayArcKind::Actual, 0.0, 0.5, true, false)]);
}

#[test]
fn daytime_fast_stays_inside_its_day() {
    let f = ended(instant("2026-11-12T07:00:00Z"), instant("2026-11-12T19:00:00Z"));
    assert_eq!(
        arcs_on("2026-11-12T00:00:00Z", &with_fasts(vec![f.clone()], vec![]), instant("2026-11-13T00:00:00Z"), "UTC"),
        vec![arc(f.id, DayArcKind::Actual, 7.0 / 24.0, 19.0 / 24.0, false, false)]
    );
}

#[test]
fn ending_exactly_at_midnight_does_not_pass_it() {
    let records = with_fasts(vec![ended(instant("2026-11-09T20:00:00Z"), instant("2026-11-10T00:00:00Z"))], vec![]);
    let monday = arcs_on("2026-11-09T00:00:00Z", &records, now(), "UTC");
    assert_eq!(monday.iter().map(|a| a.to).collect::<Vec<_>>(), vec![1.0]);
    assert_eq!(monday.iter().map(|a| a.into_next_day).collect::<Vec<_>>(), vec![false]);
    assert!(arcs_on("2026-11-10T00:00:00Z", &records, now(), "UTC").is_empty());
}

#[test]
fn a_day_inside_a_long_fast_is_a_whole_ring_linked_both_ways() {
    let f = ended(instant("2026-11-05T21:00:00Z"), instant("2026-11-07T09:00:00Z"));
    assert_eq!(arcs_on("2026-11-06T00:00:00Z", &with_fasts(vec![f.clone()], vec![]), now(), "UTC"), vec![arc(f.id, DayArcKind::Actual, 0.0, 1.0, true, true)]);
}

#[test]
fn back_to_back_fasts_share_a_day_as_two_separate_pieces() {
    let first = ended(instant("2026-11-09T20:00:00Z"), instant("2026-11-10T12:00:00Z"));
    let second = ended(instant("2026-11-10T20:00:00Z"), instant("2026-11-11T12:00:00Z"));
    let tuesday = arcs_on("2026-11-10T00:00:00Z", &with_fasts(vec![second.clone(), first.clone()], vec![]), now(), "UTC");
    assert_eq!(tuesday.iter().map(|a| a.fast_id).collect::<Vec<_>>(), vec![first.id, second.id]);
    assert_eq!(tuesday.iter().map(|a| a.from_previous_day).collect::<Vec<_>>(), vec![true, false]);
    assert_eq!(tuesday.iter().map(|a| a.into_next_day).collect::<Vec<_>>(), vec![false, true]);
}

#[test]
fn running_fast_ends_at_now_and_links_only_the_past_midnight() {
    let running = fast(instant("2026-11-10T20:00:00Z"));
    let records = with_fasts(vec![running.clone()], vec![]);
    let current = instant("2026-11-11T10:00:00Z");
    assert_eq!(arcs_on("2026-11-10T00:00:00Z", &records, current, "UTC").iter().map(|a| a.into_next_day).collect::<Vec<_>>(), vec![true]);
    assert_eq!(arcs_on("2026-11-11T00:00:00Z", &records, current, "UTC"), vec![arc(running.id, DayArcKind::Running, 0.0, 10.0 / 24.0, true, false)]);
}

#[test]
fn only_upcoming_plans_with_a_goal_are_drawn() {
    let ahead = plan(instant("2026-11-12T20:00:00Z"), Some(960));
    let open_ended = plan(instant("2026-11-12T08:00:00Z"), None);
    let past = plan(instant("2026-11-10T20:00:00Z"), Some(960));
    let records = with_fasts(vec![], vec![ahead.clone(), open_ended, past]);
    let thursday = arcs_on("2026-11-12T00:00:00Z", &records, now(), "UTC");
    assert_eq!(thursday.iter().map(|a| a.fast_id).collect::<Vec<_>>(), vec![ahead.id]);
    assert_eq!(thursday.iter().map(|a| a.kind).collect::<Vec<_>>(), vec![DayArcKind::Planned]);
    assert_eq!(arcs_on("2026-11-13T00:00:00Z", &records, now(), "UTC").iter().map(|a| a.to).collect::<Vec<_>>(), vec![0.5]);
    assert!(arcs_on("2026-11-10T00:00:00Z", &records, now(), "UTC").is_empty());
}

#[test]
fn noon_is_at_the_bottom_on_a_daylight_saving_day() {
    let f = ended(instant("2026-03-08T03:00:00Z"), instant("2026-03-08T16:00:00Z"));
    let pieces = arcs_on("2026-03-08T12:00:00Z", &with_fasts(vec![f], vec![]), now(), "America/New_York");
    assert_eq!(pieces.iter().map(|a| a.to).collect::<Vec<_>>(), vec![0.5]);
    assert_eq!(pieces.iter().map(|a| a.from_previous_day).collect::<Vec<_>>(), vec![true]);
}

// MARK: LiquidTests.swift

#[test]
fn gyro_moves_in_the_corrected_direction() {
    for direction in [-1.0, 1.0] {
        let simulation = LiquidSimulation::new();
        simulation.gyroscope(direction);
        simulation.step(1.0 / 60.0);
        assert!(simulation.state().angle * direction > 0.0);
    }
}

#[test]
fn gravity_follows_two_complete_turns_without_a_seam() {
    use std::f64::consts::PI;
    for landscape in [false, true] {
        for direction in [-1.0, 1.0] {
            let simulation = LiquidSimulation::new();
            let mut previous = 0.0;
            for degrees in (0..=720).step_by(10) {
                let angle = degrees as f64 * PI / 180.0 * direction;
                for _ in 0..20 {
                    let (x, y) = if landscape { (9.8 * angle.cos(), 9.8 * -angle.sin()) } else { (9.8 * angle.sin(), 9.8 * angle.cos()) };
                    simulation.accelerometer(x, y, landscape);
                    simulation.step(0.02);
                    assert!((simulation.state().angle - previous).abs() < 0.15);
                    previous = simulation.state().angle;
                }
            }
            for _ in 0..150 {
                simulation.accelerometer(if landscape { 9.8 } else { 0.0 }, if landscape { 0.0 } else { 9.8 }, landscape);
                simulation.step(0.02);
            }
            assert!((simulation.state().angle - direction * PI * 4.0).abs() < 0.01);
            assert!(!simulation.is_moving());
        }
    }
}

#[test]
fn idle_waves_keep_the_resting_tilt_and_respond_to_new_motion() {
    let simulation = LiquidSimulation::new();
    for _ in 0..500 {
        simulation.accelerometer(4.0, 9.0, false);
        simulation.step(1.0 / 60.0);
    }
    assert!(!simulation.is_moving());
    let before = simulation.state();
    simulation.step(1.0 / 60.0);
    assert_eq!(simulation.state().angle, before.angle);
    assert_ne!(simulation.state().phase, before.phase);
    simulation.gyroscope(2.0);
    simulation.step(1.0 / 60.0);
    assert!(simulation.is_moving());
    assert!(simulation.state().ripple > 0.0);
}

#[test]
fn motion_is_bounded_ignores_invalid_samples_and_settles() {
    let simulation = LiquidSimulation::new();
    simulation.accelerometer(f64::NAN, 9.8, false);
    simulation.gyroscope(f64::INFINITY);
    assert!(!simulation.is_moving());
    for _ in 0..100 {
        simulation.accelerometer(100.0, -100.0, false);
        simulation.gyroscope(100.0);
        simulation.step(1.0 / 60.0);
        assert!(simulation.state().angle.is_finite());
        assert!(simulation.state().ripple <= 9.0);
    }
    for _ in 0..600 {
        if !simulation.is_moving() {
            break;
        }
        simulation.step(1.0 / 60.0);
    }
    assert!(!simulation.is_moving());
    let fresh = LiquidSimulation::new();
    fresh.accelerometer(0.01, 9.8, false);
    fresh.gyroscope(0.02);
    assert!(!fresh.is_moving());
}

#[test]
fn circle_offset_leaves_the_filled_area_below_it() {
    for fraction in [0.0, 0.15, 0.5, 0.85, 1.0] {
        let offset = circle_surface_offset(fraction);
        let (mut inside, mut filled) = (0, 0);
        for row in 0..200 {
            for column in 0..200 {
                let x = (column as f64 + 0.5) / 100.0 - 1.0;
                let y = (row as f64 + 0.5) / 100.0 - 1.0;
                if x * x + y * y > 1.0 {
                    continue;
                }
                inside += 1;
                if y >= offset {
                    filled += 1;
                }
            }
        }
        assert!((filled as f64 / inside as f64 - fraction).abs() < 0.01);
        if fraction == 0.5 {
            assert!(offset.abs() < 0.0001);
        }
    }
}

// MARK: WaterTests.swift

#[test]
fn old_archives_keep_water_logs_with_reminders_off() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.metrics = vec![metric(now, MetricKind::Water, 250.0)];
    let mut json: serde_json::Value = serde_json::from_slice(&archive_encode(&archive).unwrap()).unwrap();
    json["settings"].as_object_mut().unwrap().remove("water");
    let restored = archive_decode_slice(&serde_json::to_vec(&json).unwrap(), now).unwrap();
    assert_eq!(restored.settings.water, default_water_settings());
    assert_eq!(restored.settings.water.goal_milliliters, None);
    assert_eq!(water_total(&restored.metrics, now, &cal("UTC")), 250.0);
    assert!(reminder_events(&restored, now).is_empty());
}

#[test]
fn water_respects_dst_midnight_and_time_zone_boundaries() {
    let ny = cal("America/New_York");
    for (date, hours) in [("2026-03-08T12:00:00-04:00", 23.0), ("2026-11-01T12:00:00-05:00", 25.0)] {
        let date = instant(date);
        let first = history_month_grid(date, &ny); // any day start helper: use history_day
        let _ = first;
        let day = history_day(date, &new_archive(), &ny, date).unwrap();
        let start = day.date;
        let end = plus(start, hours * 3_600.0);
        assert_eq!(history_days(start, plus(start, 1.0), &new_archive(), &ny, date).len(), 1);
        let metrics = vec![
            metric(plus(start, -1.0), MetricKind::Water, 500.0),
            metric(start, MetricKind::Water, 150.0),
            metric(plus(end, -1.0), MetricKind::Water, 250.0),
            metric(end, MetricKind::Water, 500.0),
            metric(date, MetricKind::Weight, 70.0),
        ];
        assert_eq!(water_total(&metrics, date, &ny), 400.0);
        assert_eq!(water_total(&metrics, date, &cal("UTC")), 650.0);
        // The next day starts exactly `hours` later.
        assert_eq!(history_day(end, &new_archive(), &ny, date).unwrap().date, end);
    }
}

#[test]
fn water_reminders_respect_window_and_notification_budget() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.settings.water.reminders_enabled = true;
    let water = reminder_events(&archive, now);
    assert_eq!(water.iter().map(|e| e.hour.unwrap()).collect::<Vec<_>>(), vec![9, 11, 13, 15, 17, 19]);
    assert!(water.iter().all(|e| e.date.is_none() && e.weekday.is_none() && e.minute == Some(0) && e.kind == ReminderKind::Water));
    archive.settings.water.start_hour = 0;
    archive.settings.water.end_hour = 24;
    archive.settings.water.interval_hours = 1;
    archive.settings.goal_reminder = true;
    archive.fasts = vec![fast(now)];
    archive.settings.reminder_weekdays = (1..=7).collect();
    archive.settings.schedule_reminders = true;
    archive.plans = (1..=100).map(|n| plan(plus(now, n as f64 * 3_600.0), Some(960))).collect();
    let all = reminder_events(&archive, now);
    assert_eq!(all.len(), 64);
    assert_eq!(all.iter().map(|e| e.id.clone()).collect::<std::collections::HashSet<_>>().len(), 64);
    assert_eq!(all.iter().filter(|e| e.kind == ReminderKind::Water).count(), 24);
    assert_eq!(all.iter().filter(|e| e.kind == ReminderKind::Planned).last().unwrap().date, Some(plus(now, 32.0 * 3_600.0)));
    archive.settings.water.reminders_enabled = false;
    assert!(reminder_events(&archive, now).iter().all(|e| e.kind != ReminderKind::Water));
    archive.settings.water.reminders_enabled = true;
    archive.settings.water.start_hour = 21;
    archive.settings.water.end_hour = 9;
    assert_eq!(archive_validate(&archive, now), Err(TrackerError::InvalidEntry));
}

#[test]
fn water_preferences_round_trip_and_merge_keeps_local_choices() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.settings.use_fluid_ounces = true;
    archive.settings.water.goal_milliliters = Some(units_canonical(64.0, MetricKind::Water, &archive.settings));
    archive.settings.water.quick_add_milliliters = units_canonical(8.0, MetricKind::Water, &archive.settings);
    archive.settings.water.reminders_enabled = true;
    archive.metrics = vec![metric(now, MetricKind::Water, archive.settings.water.quick_add_milliliters)];
    let copy = archive_decode_slice(&archive_encode(&archive).unwrap(), now).unwrap();
    assert_eq!(copy.settings, archive.settings);
    assert!((units_display(copy.metrics[0].value, MetricKind::Water, &copy.settings) - 8.0).abs() < 0.000001);
    let merged = archive_merge(&copy, &new_archive(), now).unwrap();
    assert_eq!(merged.archive.metrics.len(), 1);
    assert!(!merged.archive.settings.water.reminders_enabled);
    assert!(archive_csv(&copy).contains("\"water\""));
    for invalid in [0.0, -1.0, 10_001.0, f64::INFINITY, f64::NAN] {
        let mut bad = archive.clone();
        bad.settings.water.goal_milliliters = Some(invalid);
        assert_eq!(archive_validate(&bad, now), Err(TrackerError::InvalidEntry));
    }
}

#[test]
fn water_pause_persists_without_erasing_reminder_window() {
    let now = at(1_790_000_000.0);
    let mut archive = new_archive();
    archive.settings.water.reminders_enabled = true;
    archive.settings.goal_reminder = true;
    archive.fasts = vec![fast(now)];
    archive.settings.water.reminders_paused = true;
    let restored = archive_decode_slice(&archive_encode(&archive).unwrap(), now).unwrap();
    let events = reminder_events(&restored, now);
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].kind, ReminderKind::Goal);
    archive.settings.water.reminders_paused = false;
    assert_eq!(reminder_events(&archive, now).iter().filter(|e| e.kind == ReminderKind::Water).count(), 6);
}

#[test]
fn water_trend_days_include_missing_logs_and_respect_selected_period() {
    let utc = cal("UTC");
    let today = instant("2026-11-12T12:00:00Z");
    let yesterday = plus(today, -86_400.0);
    let metrics = vec![
        metric(today, MetricKind::Water, 250.0),
        metric(today, MetricKind::Water, 150.0),
        metric(yesterday, MetricKind::Water, 500.0),
        metric(plus(today, -8.0 * 86_400.0), MetricKind::Water, 1_000.0),
    ];
    let days = water_days(&metrics, today, 7, &utc);
    assert_eq!(days.len(), 7);
    assert_eq!(days.last().unwrap().milliliters, 400.0);
    assert_eq!(days.last().unwrap().entry_count, 2);
    assert_eq!(days.iter().map(|d| d.milliliters).sum::<f64>(), 900.0);
    assert_eq!(days.iter().filter(|d| d.entry_count == 0).count(), 5);
    assert!(water_days(&metrics, today, 0, &utc).is_empty());
}

// MARK: CompanionTests.swift

fn request(sent_at: SystemTime, action: FastWatchAction, revision: Option<Uuid>, active: Option<Uuid>) -> FastWatchRequest {
    FastWatchRequest { version: 1, id: Uuid::new_v4(), sent_at, action, revision, active_id: active }
}

#[test]
fn watch_commands_are_idempotent_and_reject_stale_state() {
    let now = at(1_800_000_000.0);
    let original = new_archive();
    let water = request(now, FastWatchAction::Water, Some(original.companion_revision), None);
    // The store gives every committed change a new revision.
    let mut first = watch_apply(&original, water.clone(), true, "UTC".into(), now).unwrap();
    first.companion_revision = new_companion_revision();
    let repeat = watch_apply(&first, water.clone(), true, "UTC".into(), now).unwrap();
    assert_eq!(first, repeat);
    assert_eq!(repeat.metrics.len(), 1);
    let stale = FastWatchRequest { id: Uuid::new_v4(), ..water.clone() };
    assert_eq!(watch_apply(&first, stale, true, "UTC".into(), now), Err(FastWatchFailure::Stale));
    let restored = archive_decode_slice(&archive_encode(&first).unwrap(), now).unwrap();
    let replayed = watch_apply(&restored, water, false, "UTC".into(), now).unwrap();
    assert_eq!(replayed.metrics.len(), 1);
}

#[test]
fn watch_cannot_create_after_expiry_but_can_finish() {
    let now = at(1_800_000_000.0);
    let archive = new_archive();
    let start = request(now, FastWatchAction::Start, Some(archive.companion_revision), None);
    assert_eq!(watch_apply(&archive, start.clone(), false, "UTC".into(), now), Err(FastWatchFailure::Locked));
    let archive = watch_apply(&archive, start, true, "UTC".into(), now).unwrap();
    let end = request(now, FastWatchAction::End, Some(archive.companion_revision), archive_active(&archive).map(|f| f.id));
    let archive = watch_apply(&archive, end, false, "UTC".into(), plus(now, 1.0)).unwrap();
    assert!(archive_active(&archive).is_none());
    assert_eq!(archive.fasts.len(), 1);
}

#[test]
fn watch_rejects_expired_malformed_and_unsupported_requests() {
    let now = at(1_800_000_000.0);
    let archive = new_archive();
    let mut r = request(plus(now, -121.0), FastWatchAction::Water, Some(archive.companion_revision), None);
    assert_eq!(watch_apply(&archive, r.clone(), true, "UTC".into(), now), Err(FastWatchFailure::Expired));
    r.sent_at = plus(now, 31.0);
    assert_eq!(watch_apply(&archive, r.clone(), true, "UTC".into(), now), Err(FastWatchFailure::Expired));
    r.sent_at = now;
    r.version = 2;
    assert_eq!(watch_apply(&archive, r.clone(), true, "UTC".into(), now), Err(FastWatchFailure::Unsupported));
    r.version = 1;
    let archive = watch_apply(&archive, r.clone(), true, "UTC".into(), now).unwrap();
    r.action = FastWatchAction::Start;
    assert_eq!(watch_apply(&archive, r, true, "UTC".into(), now), Err(FastWatchFailure::Malformed));
}

#[test]
fn archives_before_companion_support_still_decode() {
    let now = at(1_800_000_000.0);
    let mut json: serde_json::Value = serde_json::from_slice(&archive_encode(&new_archive()).unwrap()).unwrap();
    let object = json.as_object_mut().unwrap();
    object.remove("companionReceipts");
    object.remove("companionRevision");
    let decoded = archive_decode_slice(&serde_json::to_vec(&json).unwrap(), now).unwrap();
    assert!(decoded.companion_receipts.is_empty());
    assert!(decoded.fasts.is_empty());
}

#[test]
fn receipt_replay_survives_fractional_date_backup_precision() {
    // Swift: Date(timeIntervalSinceReferenceDate: 815_465_950.776_553_2)
    let now = at(978_307_200.0 + 815_465_950.776_553_2);
    let archive = new_archive();
    let r = request(now, FastWatchAction::Water, Some(archive.companion_revision), None);
    let archive = watch_apply(&archive, r.clone(), true, "UTC".into(), now).unwrap();
    let restored = archive_decode_slice(&archive_encode(&archive).unwrap(), now).unwrap();
    let replayed = watch_apply(&restored, r, true, "UTC".into(), plus(now, 1.0)).unwrap();
    assert_eq!(replayed.metrics.len(), 1);
}
