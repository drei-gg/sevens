//! Values pinned from alarm-core before these rules moved here (2026-11-22). Native alarms and
//! notifications already on users' phones carry these identifiers and times: they must not change.

use seven_schedule::jiff::Timestamp;
use seven_schedule::*;

fn root() -> Uuid {
    Uuid::parse_str("3F2504E0-4F89-11D3-9A0C-0305E82C3301").unwrap()
}

fn at(seconds: i64) -> Timestamp {
    Timestamp::from_second(seconds).unwrap()
}

#[test]
fn identifiers_are_unchanged() {
    for (key, want) in [
        ("once", "279442b0-4665-994b-bc17-f79a25c1cd86"),
        ("minute:420", "5a4ad36c-48ed-123e-3245-91ccebf9ff5f"),
        ("weekday:2", "aca0b8b9-28e6-c114-12b9-ee9f1c2cf667"),
        ("snooze:0", "d7f1672b-401d-780e-7d33-c3f6f09572d3"),
        ("followUp:1", "0cd0f90a-1e31-7ef7-eed2-f6d4779b00d6"),
        ("bloom-dose:2026-03-08", "41669b41-51b5-081e-a737-3d8a87ea1129"),
    ] {
        assert_eq!(identifier(root(), key).to_string(), want, "{key}");
    }
}

#[test]
fn weekly_times_are_unchanged() {
    for (zone_id, minute, weekday, now, want) in [
        // 02:30 on the spring-forward Sunday rings when the gap ends, 03:00 EDT.
        ("America/New_York", 150, 1, 1_772_946_000, 1_772_953_200),
        // 01:30 on the fall-back Sunday rings the first time, 01:30 EDT.
        ("America/New_York", 90, 1, 1_793_419_200, 1_793_511_000),
        ("Asia/Manila", 420, 6, 1_794_787_200, 1_795_129_200),
        // London's gap: 01:00 rings at 02:00 BST.
        ("Europe/London", 60, 1, 1_774_742_400, 1_774_746_000),
        // Lord Howe moves by half an hour.
        ("Australia/Lord_Howe", 135, 1, 1_775_307_600, 1_775_317_500),
        ("UTC", 0, 3, 1_794_787_200, 1_794_873_600),
    ] {
        assert_eq!(next_weekly(at(now), minute, weekday, &zone(zone_id)), Some(at(want)), "{zone_id} {minute} {weekday}");
    }
}

#[test]
fn daily_is_the_earliest_weekday() {
    let tz = zone("America/New_York");
    for now in [1_772_946_000, 1_793_419_200, 1_794_787_200] {
        for minute in [0, 90, 150, 1439] {
            let weekly = (1..=7).filter_map(|d| next_weekly(at(now), minute, d, &tz)).min();
            assert_eq!(next_daily(at(now), minute, &tz), weekly);
        }
    }
}

#[test]
fn out_of_range_minutes_never_ring() {
    let tz = zone("UTC");
    assert_eq!(next_daily(at(0), -1, &tz), None);
    assert_eq!(next_daily(at(0), 1440, &tz), None);
}

#[test]
fn unknown_zones_read_as_utc() {
    assert!(!zone_known("Mars/Olympus"));
    assert!(zone_known("Asia/Manila"));
    assert_eq!(next_daily(at(0), 60, &zone("Mars/Olympus")), Some(at(3600)));
}

#[test]
fn occurrences_are_sorted_unique_and_bounded() {
    let tz = zone("UTC");
    // Monday 2026-11-09 00:00 UTC; 07:00 on Monday, Wednesday and Friday for a week.
    let dates = weekly_occurrences(420, &[2, 4, 6, 4], at(1_794_182_400), 7, &tz);
    assert_eq!(dates, vec![at(1_794_207_600), at(1_794_380_400), at(1_794_553_200)]);
}
