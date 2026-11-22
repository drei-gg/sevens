//! The phone's side of the Watch protocol: requests saved as receipts in the archive and the rules
//! for applying them. The wire codec itself stays with the Watch and phone apps (Swift on both
//! ends); a request arrives here already decoded. Journal, weight and reflections never leave the
//! phone.

use crate::archive::FastArchive;
use crate::model::*;
use crate::time::{self, ms, upper, upper_opt};
use serde::{Deserialize, Serialize};
use std::time::SystemTime;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum CompanionAction {
    Refresh,
    Start,
    End,
    Water,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct CompanionRequest {
    pub version: i32,
    #[serde(with = "upper")]
    pub id: Uuid,
    #[serde(rename = "sentAt", with = "ms")]
    pub sent_at: SystemTime,
    pub action: CompanionAction,
    #[serde(with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub revision: Option<Uuid>,
    #[serde(rename = "activeID", with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    pub active_id: Option<Uuid>,
    #[serde(rename = "presetID", with = "upper_opt", default, skip_serializing_if = "Option::is_none")]
    #[uniffi(default = None)]
    pub preset_id: Option<Uuid>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct CompanionReceipt {
    pub request: CompanionRequest,
    #[serde(rename = "savedAt", with = "ms")]
    pub saved_at: SystemTime,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CompanionFailure {
    #[error("unsupported")]
    Unsupported,
    #[error("malformed")]
    Malformed,
    #[error("expired")]
    Expired,
    #[error("stale")]
    Stale,
    #[error("locked")]
    Locked,
    #[error("unavailable")]
    Unavailable,
    #[error("save failed")]
    SaveFailed,
}

/// Applies a Watch request inside the store's transaction. Receipts survive a phone relaunch: a
/// timed-out request may be retried verbatim, and is never turned into a second water entry.
/// Returns the archive unchanged for a refresh or a replay. A fast started from the Watch records
/// `time_zone_id`, the phone's current zone.
#[uniffi::export]
pub fn watch_apply(archive: &FastArchive, request: CompanionRequest, can_create_records: bool, time_zone_id: String, now: SystemTime) -> Result<FastArchive, CompanionFailure> {
    use CompanionFailure::*;
    if ![1, 2].contains(&request.version) {
        return Err(Unsupported);
    }
    // Version 2 exists only for choosing a saved preset. Older phones reject it instead of
    // silently starting their legacy 16-hour default when they ignore an unknown field.
    if (request.version == 2 && (request.action != CompanionAction::Start || request.preset_id.is_none()))
        || (request.version == 1 && request.preset_id.is_some()) {
        return Err(Malformed);
    }
    let age = time::seconds_between(now, request.sent_at);
    if !age.is_finite() || !(-30.0..=120.0).contains(&age) {
        return Err(Expired);
    }
    if let Some(receipt) = archive.companion_receipts.iter().find(|r| r.request.id == request.id) {
        let mut saved = receipt.request.clone();
        if (time::millis(saved.sent_at) - time::millis(request.sent_at)).abs() <= 0.001 {
            saved.sent_at = request.sent_at;
        }
        return if saved == request { Ok(archive.clone()) } else { Err(Malformed) };
    }
    if request.action == CompanionAction::Refresh {
        return Ok(archive.clone());
    }
    if request.revision != Some(archive.companion_revision) || request.active_id != archive.active().map(|f| f.id) {
        return Err(Stale);
    }
    let mut next = archive.clone();
    match request.action {
        CompanionAction::Refresh => {}
        CompanionAction::Start => {
            if !can_create_records {
                return Err(Locked);
            }
            if archive.active().is_some() {
                return Err(Stale);
            }
            let mut fast = FastRecord { id: request.id, ..new_fast_record(now, time_zone_id) };
            if let Some(preset_id) = request.preset_id {
                let preset = companion_presets(archive).into_iter().find(|preset| preset.id == preset_id).ok_or(Stale)?;
                fast.goal_minutes = preset.goal_minutes;
                fast.eating_minutes = preset.eating_minutes;
            }
            next.save(fast, now).map_err(|_| SaveFailed)?;
        }
        CompanionAction::End => {
            let Some(mut current) = archive.active().cloned() else { return Err(Stale) };
            if now <= current.start {
                return Err(Stale);
            }
            current.end = Some(now);
            next.save(current, now).map_err(|_| SaveFailed)?;
        }
        CompanionAction::Water => {
            if !can_create_records {
                return Err(Locked);
            }
            next.metrics.push(MetricEntry { id: request.id, date: now, kind: MetricKind::Water, value: 250.0, external_id: None });
        }
    }
    next.companion_receipts.retain(|r| time::seconds_between(now, r.saved_at) <= 600.0);
    if next.companion_receipts.len() >= 2_000 {
        return Err(Unavailable);
    }
    next.companion_receipts.push(CompanionReceipt { request, saved_at: now });
    Ok(next)
}


/// Only identifiers and timer durations cross to a watch. User-written preset names stay on phone.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct CompanionPreset {
    pub id: Uuid,
    pub goal_minutes: Option<i32>,
    pub eating_minutes: i32,
}
#[uniffi::export]
pub fn watch_presets(archive: &FastArchive) -> Vec<CompanionPreset> {
    companion_presets(archive).into_iter().map(|preset| CompanionPreset { id: preset.id, goal_minutes: preset.goal_minutes, eating_minutes: preset.eating_minutes }).collect()
}

// The phone offers built-ins plus saved presets. A saved ID takes precedence so the duration
// displayed by the watch and the duration committed by Start always come from the same source.
fn companion_presets(archive: &FastArchive) -> Vec<FastPreset> {
    let mut presets = default_presets();
    for preset in &archive.presets {
        if let Some(existing) = presets.iter_mut().find(|item| item.id == preset.id) {
            *existing = preset.clone();
        } else { presets.push(preset.clone()); }
    }
    presets
}
