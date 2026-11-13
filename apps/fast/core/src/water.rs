//! Water: settings rules, day totals, the seven-day view and the glass's layers.

use crate::archive::TrackerError;
use crate::model::*;
use crate::time::{self, CalendarSpec};
use std::collections::HashMap;
use std::time::SystemTime;

pub(crate) fn validate_water(s: &WaterSettings) -> Result<(), TrackerError> {
    let goal_ok = s.goal_milliliters.map(|g| g.is_finite() && metric_in_range(MetricKind::Water, g)).unwrap_or(true);
    let ok = goal_ok
        && s.quick_add_milliliters.is_finite()
        && metric_in_range(MetricKind::Water, s.quick_add_milliliters)
        && (0..=23).contains(&s.start_hour)
        && (1..=24).contains(&s.end_hour)
        && s.start_hour < s.end_hour
        && (1..=4).contains(&s.interval_hours);
    if ok { Ok(()) } else { Err(TrackerError::InvalidEntry) }
}

#[uniffi::export]
pub fn water_settings_validate(settings: &WaterSettings) -> Result<(), TrackerError> {
    validate_water(settings)
}

/// The hours a reminder fires, start up to but not including the end.
#[uniffi::export]
pub fn water_reminder_hours(settings: &WaterSettings) -> Vec<i32> {
    if !settings.reminders_enabled || settings.reminders_paused || validate_water(settings).is_err() {
        return vec![];
    }
    (settings.start_hour..settings.end_hour).step_by(settings.interval_hours as usize).collect()
}

/// Water entries on the calendar day containing `day`, newest first.
#[uniffi::export]
pub fn water_entries(metrics: &[MetricEntry], day: SystemTime, calendar: &CalendarSpec) -> Vec<MetricEntry> {
    let tz = time::zone(calendar);
    let Some((start, end)) = time::day_interval(day, &tz) else { return vec![] };
    let mut entries: Vec<MetricEntry> =
        metrics.iter().filter(|m| m.kind == MetricKind::Water && m.date >= start && m.date < end).cloned().collect();
    entries.sort_by(|a, b| {
        if a.date == b.date {
            a.id.hyphenated().to_string().to_uppercase().cmp(&b.id.hyphenated().to_string().to_uppercase())
        } else {
            b.date.cmp(&a.date)
        }
    });
    entries
}

#[uniffi::export]
pub fn water_total(metrics: &[MetricEntry], day: SystemTime, calendar: &CalendarSpec) -> f64 {
    let tz = time::zone(calendar);
    let Some((start, end)) = time::day_interval(day, &tz) else { return 0.0 };
    metrics.iter().filter(|m| m.kind == MetricKind::Water && m.date >= start && m.date < end).map(|m| m.value).sum()
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WaterDay {
    pub date: SystemTime,
    pub milliliters: f64,
    /// Zero means nothing was logged, which is not the same as logging zero.
    pub entry_count: i32,
}

/// `count` days ending with the day containing `day`, empty days included.
#[uniffi::export]
pub fn water_days(metrics: &[MetricEntry], day: SystemTime, count: i32, calendar: &CalendarSpec) -> Vec<WaterDay> {
    let tz = time::zone(calendar);
    if !(1..=366).contains(&count) {
        return vec![];
    }
    let Some((_, end)) = time::day_interval(day, &tz) else { return vec![] };
    let Some(start) = time::add_days(end, -(count as i64), &tz) else { return vec![] };
    let mut grouped: HashMap<i128, (f64, i32)> = HashMap::new();
    for m in metrics.iter().filter(|m| m.kind == MetricKind::Water && m.date >= start && m.date < end) {
        if let Some(d) = time::start_of_day(m.date, &tz) {
            let slot = grouped.entry(key(d)).or_insert((0.0, 0));
            slot.0 += m.value;
            slot.1 += 1;
        }
    }
    (0..count as i64)
        .filter_map(|offset| time::add_days(start, offset, &tz))
        .map(|date| {
            let (milliliters, entry_count) = grouped.get(&key(date)).copied().unwrap_or((0.0, 0));
            WaterDay { date, milliliters, entry_count }
        })
        .collect()
}

fn key(t: SystemTime) -> i128 {
    (time::millis(t) * 1_000.0).round() as i128
}

// MARK: the glass

/// How a layer of water is painted. `Light` is the water colour at 45% laid on the card colour,
/// so it reads light even over `Deep`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WaterTone {
    /// The white crest just above the surface, at 55%.
    Crest,
    /// Water below the goal: the water colour at 85%.
    Shallow,
    Light,
    Deep,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct WaterLayer {
    /// Share of the circle's area below the surface, 0…1.
    pub fraction: f64,
    pub tone: WaterTone,
    /// Which wave the surface follows (0 crest, 1 water, 2 the rising lap), so layers move apart.
    pub wave: u8,
    /// The crest sits a little above the surface (5 points / dp).
    pub lifted: bool,
}

/// The glass at `level` (the day's total over the goal), bottom layer first. Below the goal the
/// water rises with its crest. Past it the glass counts laps: a full glass in one tone and the
/// current lap rising over it in the other, deep over light from 1×, light over deep from 2×, and
/// so on, so every drink moves the water however far past the goal.
#[uniffi::export]
pub fn water_layers(level: f64) -> Vec<WaterLayer> {
    if !level.is_finite() || level <= 0.0 {
        return vec![];
    }
    let lap = if level < 1.0 { 0 } else { level.floor() as i64 };
    let rise = (level - lap as f64).clamp(0.0, 1.0);
    if lap == 0 {
        return vec![
            WaterLayer { fraction: rise, tone: WaterTone::Crest, wave: 0, lifted: true },
            WaterLayer { fraction: rise, tone: WaterTone::Shallow, wave: 1, lifted: false },
        ];
    }
    let (under, over) = if lap % 2 == 1 { (WaterTone::Light, WaterTone::Deep) } else { (WaterTone::Deep, WaterTone::Light) };
    let mut layers = vec![WaterLayer { fraction: 1.0, tone: under, wave: 1, lifted: false }];
    if rise > 0.0 {
        layers.push(WaterLayer { fraction: rise, tone: over, wave: 2, lifted: false });
    }
    layers
}
