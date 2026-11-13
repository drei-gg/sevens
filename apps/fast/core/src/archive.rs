//! The archive: every record, the settings, and the one validation used by UI mutations, disk
//! reads and imports. No repair by silently dropping data.

use crate::companion::CompanionReceipt;
use crate::model::*;
use crate::text::{characters, trimmed};
use crate::time::{self, upper};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::time::SystemTime;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum TrackerError {
    #[error("invalid time")]
    InvalidTime,
    #[error("record in the future")]
    FutureRecord,
    #[error("invalid goal")]
    InvalidGoal,
    #[error("overlapping fasts")]
    Overlap,
    #[error("more than one running fast")]
    MultipleActive,
    #[error("duplicate identifier")]
    DuplicateId,
    #[error("invalid entry")]
    InvalidEntry,
    #[error("journal entry linked to a missing fast")]
    DanglingJournal,
    #[error("unsupported schema")]
    UnsupportedSchema,
    #[error("unreadable")]
    Unreadable,
    #[error("conflicting record")]
    Conflict,
    #[error("too large")]
    TooLarge,
    #[error("read only")]
    ReadOnly,
}

fn new_revision() -> Uuid {
    Uuid::new_v4()
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct FastArchive {
    pub schema_version: i32,
    pub fasts: Vec<FastRecord>,
    pub journal: Vec<JournalEntry>,
    pub metrics: Vec<MetricEntry>,
    pub plans: Vec<PlannedFast>,
    pub rest_days: Vec<RestDay>,
    pub presets: Vec<FastPreset>,
    pub settings: TrackerSettings,
    #[serde(with = "upper", default = "new_revision")]
    pub companion_revision: Uuid,
    #[serde(default)]
    pub companion_receipts: Vec<CompanionReceipt>,
}

impl Default for FastArchive {
    fn default() -> Self {
        Self {
            schema_version: 1,
            fasts: vec![],
            journal: vec![],
            metrics: vec![],
            plans: vec![],
            rest_days: vec![],
            presets: vec![],
            settings: TrackerSettings::default(),
            companion_revision: Uuid::new_v4(),
            companion_receipts: vec![],
        }
    }
}

#[uniffi::export]
pub fn new_archive() -> FastArchive {
    FastArchive::default()
}

/// A fresh companion revision, set by the store on every committed change.
#[uniffi::export]
pub fn new_companion_revision() -> Uuid {
    Uuid::new_v4()
}

#[uniffi::export]
pub fn archive_active(archive: &FastArchive) -> Option<FastRecord> {
    archive.active().cloned()
}

#[uniffi::export]
pub fn archive_latest_completed(archive: &FastArchive) -> Option<FastRecord> {
    archive.latest_completed().cloned()
}

#[uniffi::export]
pub fn archive_validate(archive: &FastArchive, now: SystemTime) -> Result<(), TrackerError> {
    archive.validate(now)
}

/// Inserts or replaces `fast`, then validates the whole archive. The input is unchanged on failure.
#[uniffi::export]
pub fn archive_save(archive: &FastArchive, fast: FastRecord, now: SystemTime) -> Result<FastArchive, TrackerError> {
    let mut next = archive.clone();
    next.save(fast, now)?;
    Ok(next)
}

/// Removes a fast. Journal entries linked to it are kept and unlinked.
#[uniffi::export]
pub fn archive_delete_fast(archive: &FastArchive, id: Uuid) -> FastArchive {
    let mut next = archive.clone();
    next.delete_fast(id);
    next
}

impl FastArchive {
    pub fn active(&self) -> Option<&FastRecord> {
        self.fasts.iter().find(|f| f.end.is_none())
    }

    pub fn latest_completed(&self) -> Option<&FastRecord> {
        // First of equal ends wins, as Swift's `max(by:)` does.
        self.fasts.iter().filter(|f| f.end.is_some()).fold(None, |best: Option<&FastRecord>, f| match best {
            Some(b) if b.end >= f.end => Some(b),
            _ => Some(f),
        })
    }

    pub fn save(&mut self, fast: FastRecord, now: SystemTime) -> Result<(), TrackerError> {
        let mut next = self.clone();
        next.fasts.retain(|f| f.id != fast.id);
        next.fasts.push(fast);
        next.validate(now)?;
        *self = next;
        Ok(())
    }

    pub fn delete_fast(&mut self, id: Uuid) {
        self.fasts.retain(|f| f.id != id);
        for entry in self.journal.iter_mut().filter(|j| j.fast_id == Some(id)) {
            entry.fast_id = None;
        }
    }

    pub fn validate(&self, now: SystemTime) -> Result<(), TrackerError> {
        use TrackerError::*;
        // JSON milliseconds can move a date by a fraction of a microsecond; a record saved at
        // `now` must still read back as not in the future. Same tolerance as merging.
        let now = time::add_seconds(now, 0.000_001);
        if self.companion_receipts.len() > 2_000 {
            return Err(TooLarge);
        }
        unique(self.companion_receipts.iter().map(|r| r.request.id))?;
        if !self.companion_receipts.iter().all(|r| valid_date(r.saved_at) && valid_date(r.request.sent_at)) {
            return Err(InvalidEntry);
        }
        if self.schema_version != 1 {
            return Err(UnsupportedSchema);
        }
        let total = self.fasts.len() + self.journal.len() + self.metrics.len() + self.plans.len() + self.rest_days.len();
        if total > 100_000 {
            return Err(TooLarge);
        }
        unique(self.fasts.iter().map(|x| x.id))?;
        unique(self.journal.iter().map(|x| x.id))?;
        unique(self.metrics.iter().map(|x| x.id))?;
        unique(self.plans.iter().map(|x| x.id))?;
        unique(self.rest_days.iter().map(|x| x.id))?;
        unique(self.presets.iter().map(|x| x.id))?;
        if self.fasts.iter().filter(|f| f.end.is_none()).count() > 1 {
            return Err(MultipleActive);
        }
        let mut sorted: Vec<&FastRecord> = self.fasts.iter().collect();
        sorted.sort_by(|a, b| a.start.cmp(&b.start));
        for (index, fast) in sorted.iter().enumerate() {
            if !valid_date(fast.start) || !fast.end.map(valid_date).unwrap_or(true) || !fast.end.map(|e| e > fast.start).unwrap_or(true) {
                return Err(InvalidTime);
            }
            if fast.start > now || !fast.end.map(|e| e <= now).unwrap_or(true) {
                return Err(FutureRecord);
            }
            valid_goal(fast.goal_minutes, fast.eating_minutes)?;
            if !time::valid_zone(&fast.time_zone_id) || characters(&fast.intention) > 10_000 || characters(&fast.reflection) > 10_000 {
                return Err(InvalidEntry);
            }
            valid_tags(&fast.tags)?;
            if index > 0 {
                // A running fast is open to the future, so nothing may start after it.
                let previous_end = sorted[index - 1].end;
                if previous_end.map(|e| e > fast.start).unwrap_or(true) {
                    return Err(Overlap);
                }
            }
        }
        let ids: HashSet<Uuid> = self.fasts.iter().map(|f| f.id).collect();
        for entry in &self.journal {
            let ratings_ok = [entry.mood, entry.energy, entry.hunger].iter().all(|r| r.map(|v| (1..=5).contains(&v)).unwrap_or(true));
            if !valid_date(entry.date) || entry.date > now || characters(&entry.text) > 20_000 || !ratings_ok {
                return Err(InvalidEntry);
            }
            if let Some(fast) = entry.fast_id {
                if !ids.contains(&fast) {
                    return Err(DanglingJournal);
                }
            }
            valid_tags(&entry.tags)?;
        }
        for metric in &self.metrics {
            let external_ok = metric.external_id.as_ref().map(|e| characters(e) < 256).unwrap_or(true);
            if !valid_date(metric.date) || metric.date > now || !metric.value.is_finite() || !metric_in_range(metric.kind, metric.value) || !external_ok {
                return Err(InvalidEntry);
            }
        }
        let external: Vec<&String> = self.metrics.iter().filter_map(|m| m.external_id.as_ref()).collect();
        if external.iter().collect::<HashSet<_>>().len() != external.len() {
            return Err(DuplicateId);
        }
        for plan in &self.plans {
            if !valid_date(plan.start) || characters(&plan.intention) > 10_000 {
                return Err(InvalidEntry);
            }
            valid_goal(plan.goal_minutes, plan.eating_minutes)?;
        }
        for day in &self.rest_days {
            if !valid_date(day.date) || characters(&day.note) > 10_000 {
                return Err(InvalidEntry);
            }
        }
        if self.presets.len() > 100 {
            return Err(TooLarge);
        }
        for preset in &self.presets {
            if trimmed(&preset.name).is_empty() || characters(&preset.name) > 80 {
                return Err(InvalidEntry);
            }
            valid_goal(preset.goal_minutes, preset.eating_minutes)?;
        }
        crate::water::validate_water(&self.settings.water)?;
        let s = &self.settings;
        let weekdays_ok = s.reminder_weekdays.iter().all(|d| (1..=7).contains(d))
            && s.reminder_weekdays.iter().collect::<HashSet<_>>().len() == s.reminder_weekdays.len();
        if !(0..=23).contains(&s.reminder_hour) || !(0..=59).contains(&s.reminder_minute) || !weekdays_ok {
            return Err(InvalidEntry);
        }
        Ok(())
    }
}

fn unique(ids: impl Iterator<Item = Uuid>) -> Result<(), TrackerError> {
    let mut seen = HashSet::new();
    for id in ids {
        if !seen.insert(id) {
            return Err(TrackerError::DuplicateId);
        }
    }
    Ok(())
}

/// 1900-01-01 to 2200-01-01.
pub(crate) fn valid_date(date: SystemTime) -> bool {
    let seconds = time::millis(date) / 1_000.0;
    seconds.is_finite() && seconds >= -2_208_988_800.0 && seconds < 7_258_118_400.0
}

/// Recording limits are input protection, not recommended fasting durations.
fn valid_goal(goal: Option<i32>, eating: i32) -> Result<(), TrackerError> {
    if goal.map(|g| (1..=525_600).contains(&g)).unwrap_or(true) && (1..=10_080).contains(&eating) {
        Ok(())
    } else {
        Err(TrackerError::InvalidGoal)
    }
}

fn valid_tags(tags: &[String]) -> Result<(), TrackerError> {
    if tags.len() <= 20 && tags.iter().all(|t| !t.is_empty() && characters(t) <= 80) {
        Ok(())
    } else {
        Err(TrackerError::InvalidEntry)
    }
}
