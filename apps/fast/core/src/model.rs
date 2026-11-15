//! Records and settings, with the JSON keys and defaults the Swift app has always written.

use crate::time::{self, ms, ms_opt, upper, upper_opt};
use serde::{Deserialize, Serialize};
use std::time::SystemTime;
use uuid::Uuid;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct FastRecord {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "ms")]
    pub start: SystemTime,
    #[serde(with = "ms_opt", default, skip_serializing_if = "Option::is_none")]
    pub end: Option<SystemTime>,
    #[serde(rename = "goalMinutes", default, skip_serializing_if = "Option::is_none")]
    pub goal_minutes: Option<i32>,
    #[serde(rename = "eatingMinutes")]
    pub eating_minutes: i32,
    #[serde(rename = "timeZoneID")]
    pub time_zone_id: String,
    pub intention: String,
    pub reflection: String,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct JournalEntry {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "ms")]
    pub date: SystemTime,
    #[serde(rename = "fastID", with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub fast_id: Option<Uuid>,
    pub text: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mood: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub energy: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hunger: Option<i32>,
    pub tags: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum MetricKind {
    Weight,
    Water,
    Waist,
    Sleep,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct MetricEntry {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "ms")]
    pub date: SystemTime,
    pub kind: MetricKind,
    /// Always canonical units; display conversions never rewrite history.
    pub value: f64,
    #[serde(rename = "externalID", default, skip_serializing_if = "Option::is_none")]
    pub external_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct FastPreset {
    #[serde(with = "upper")]
    pub id: Uuid,
    pub name: String,
    #[serde(rename = "goalMinutes", default, skip_serializing_if = "Option::is_none")]
    pub goal_minutes: Option<i32>,
    #[serde(rename = "eatingMinutes")]
    pub eating_minutes: i32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PlannedFast {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "ms")]
    pub start: SystemTime,
    #[serde(rename = "goalMinutes", default, skip_serializing_if = "Option::is_none")]
    pub goal_minutes: Option<i32>,
    #[serde(rename = "eatingMinutes")]
    pub eating_minutes: i32,
    pub intention: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct RestDay {
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(with = "ms")]
    pub date: SystemTime,
    pub note: String,
}

/// Preferences, not intake recommendations. Canonical mL keeps unit changes lossless.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(default, rename_all = "camelCase")]
pub struct WaterSettings {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub goal_milliliters: Option<f64>,
    pub quick_add_milliliters: f64,
    pub reminders_enabled: bool,
    pub reminders_paused: bool,
    pub start_hour: i32,
    /// Exclusive end; 24 means midnight. A single daytime window, no overnight wrap.
    pub end_hour: i32,
    pub interval_hours: i32,
}

impl Default for WaterSettings {
    fn default() -> Self {
        Self {
            goal_milliliters: None,
            quick_add_milliliters: 250.0,
            reminders_enabled: false,
            reminders_paused: false,
            start_hour: 9,
            end_hour: 21,
            interval_hours: 2,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(default, rename_all = "camelCase")]
pub struct TrackerSettings {
    pub water: WaterSettings,
    pub use_pounds: bool,
    pub use_fluid_ounces: bool,
    pub show_weight: bool,
    pub show_eating_window: bool,
    pub live_activity: bool,
    pub goal_reminder: bool,
    pub eating_reminder: bool,
    pub schedule_reminders: bool,
    /// Calendar weekdays: 1 Sunday … 7 Saturday. Empty means no repeating reminder.
    pub reminder_weekdays: Vec<i32>,
    pub reminder_hour: i32,
    pub reminder_minute: i32,
}

impl Default for TrackerSettings {
    fn default() -> Self {
        Self {
            water: WaterSettings::default(),
            use_pounds: false,
            use_fluid_ounces: false,
            show_weight: true,
            show_eating_window: true,
            live_activity: false,
            goal_reminder: false,
            eating_reminder: false,
            schedule_reminders: false,
            reminder_weekdays: vec![],
            reminder_hour: 20,
            reminder_minute: 0,
        }
    }
}

// MARK: constructors with the Swift initialisers' defaults

#[uniffi::export]
pub fn new_fast_record(start: SystemTime, time_zone_id: String) -> FastRecord {
    FastRecord {
        id: Uuid::new_v4(),
        start,
        end: None,
        goal_minutes: Some(960),
        eating_minutes: 480,
        time_zone_id,
        intention: String::new(),
        reflection: String::new(),
        tags: vec![],
    }
}

#[uniffi::export]
pub fn default_tracker_settings() -> TrackerSettings {
    TrackerSettings::default()
}

#[uniffi::export]
pub fn default_water_settings() -> WaterSettings {
    WaterSettings::default()
}

/// 12:12, 14:10, 16:8, 18:6, 20:4 and Open ended, with fixed identifiers.
#[uniffi::export]
pub fn default_presets() -> Vec<FastPreset> {
    let mut presets: Vec<FastPreset> = [12, 14, 16, 18, 20]
        .iter()
        .enumerate()
        .map(|(index, hours)| FastPreset {
            id: Uuid::parse_str(&format!("00000000-0000-0000-0000-{:012}", index + 1)).unwrap(),
            name: format!("{}:{}", hours, 24 - hours),
            goal_minutes: Some(hours * 60),
            eating_minutes: (24 - hours) * 60,
        })
        .collect();
    presets.push(FastPreset {
        id: Uuid::parse_str("00000000-0000-0000-0000-000000000006").unwrap(),
        name: "Open ended".into(),
        goal_minutes: None,
        eating_minutes: 480,
    });
    presets
}

// MARK: fast helpers

#[uniffi::export]
pub fn fast_duration(fast: &FastRecord, now: SystemTime) -> f64 {
    time::seconds_between(fast.end.unwrap_or(now), fast.start).max(0.0)
}

#[uniffi::export]
pub fn fast_goal_date(fast: &FastRecord) -> Option<SystemTime> {
    fast.goal_minutes.map(|m| time::add_seconds(fast.start, m as f64 * 60.0))
}

#[uniffi::export]
pub fn fast_met_goal(fast: &FastRecord) -> Option<bool> {
    let end = fast.end?;
    let goal = fast_goal_date(fast)?;
    Some(end >= goal)
}

#[uniffi::export]
pub fn fast_searchable_text(fast: &FastRecord) -> String {
    let mut parts = vec![fast.intention.clone(), fast.reflection.clone()];
    parts.extend(fast.tags.iter().cloned());
    parts.join(" ")
}

// MARK: metric kinds and units

#[uniffi::export]
pub fn metric_canonical_unit(kind: MetricKind) -> String {
    match kind {
        MetricKind::Weight => "kg",
        MetricKind::Water => "mL",
        MetricKind::Waist => "cm",
        MetricKind::Sleep => "h",
    }
    .into()
}

pub(crate) fn valid_range(kind: MetricKind) -> (f64, f64) {
    match kind {
        MetricKind::Weight => (1.0, 700.0),
        MetricKind::Water => (1.0, 10_000.0),
        MetricKind::Waist => (1.0, 400.0),
        MetricKind::Sleep => (0.01, 24.0),
    }
}

/// The accepted range of a measurement, in canonical units.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct MetricRange {
    pub low: f64,
    pub high: f64,
}

#[uniffi::export]
pub fn metric_range(kind: MetricKind) -> MetricRange {
    let (low, high) = valid_range(kind);
    MetricRange { low, high }
}

#[uniffi::export]
pub fn metric_in_range(kind: MetricKind, value: f64) -> bool {
    let (low, high) = valid_range(kind);
    value >= low && value <= high
}

pub const POUNDS_PER_KILOGRAM: f64 = 2.2046226218487757;
pub const MILLILITERS_PER_FLUID_OUNCE: f64 = 29.5735295625;

#[uniffi::export]
pub fn units_display(value: f64, kind: MetricKind, settings: &TrackerSettings) -> f64 {
    match kind {
        MetricKind::Weight if settings.use_pounds => value * POUNDS_PER_KILOGRAM,
        MetricKind::Water if settings.use_fluid_ounces => value / MILLILITERS_PER_FLUID_OUNCE,
        _ => value,
    }
}

#[uniffi::export]
pub fn units_canonical(value: f64, kind: MetricKind, settings: &TrackerSettings) -> f64 {
    match kind {
        MetricKind::Weight if settings.use_pounds => value / POUNDS_PER_KILOGRAM,
        MetricKind::Water if settings.use_fluid_ounces => value * MILLILITERS_PER_FLUID_OUNCE,
        _ => value,
    }
}
