//! Minimal, read-only widget projection. Receipts alone do not prove native installation, and a
//! native registration never proves delivery. No mission text, history or receipt IDs leave here.
use crate::model::invalid;
use crate::time::refdate;
use crate::*;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::time::{Duration, SystemTime};

const LIMIT: usize = 64;
const MAX_BYTES: usize = 65_536;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct AlarmWidgetItem {
    pub name: String,
    pub kind: RoutineKind,
    pub delivery: Delivery,
    pub purpose: String,
    #[serde(with = "refdate")]
    pub date: SystemTime,
    /// Both the saved receipt and the native identifier matched at snapshot time, not a promise
    /// of audible delivery. A failed replacement must be excluded from the supplied identifiers.
    pub registered: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct AlarmWidgetSnapshot {
    pub version: u32,
    #[serde(with = "refdate")]
    pub updated: SystemTime,
    #[serde(with = "refdate")]
    pub valid_until: SystemTime,
    pub time_zone: String,
    pub needs_attention: bool,
    pub items: Vec<AlarmWidgetItem>,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct AlarmWidgetView {
    pub stale: bool,
    pub needs_attention: bool,
    pub items: Vec<AlarmWidgetItem>,
}

#[uniffi::export]
pub fn alarm_widget_snapshot(
    archive: &LoopArchive,
    verified_ids: Vec<Uuid>,
    now: SystemTime,
    calendar: CalendarSpec,
) -> Result<AlarmWidgetSnapshot, LoopError> {
    loop_archive_validate(archive)?;
    let verified: HashSet<_> = verified_ids.into_iter().collect();
    let desired = desired_registrations(archive, now)?;
    let mut attention = false;
    let mut items = Vec::new();
    let mut until = now + Duration::from_secs(86_400);
    let rows = desired.iter().chain(archive.installed.iter().filter(|r| {
        matches!(r.purpose.as_str(), "snooze" | "followUp")
            && archive
                .routines
                .iter()
                .any(|routine| routine.id == r.routine.id && routine.enabled)
    }));
    for row in rows {
        let registered = verified.contains(&row.id) && archive.installed.contains(row);
        attention |= !registered;
        let mut cursor = now;
        // The first occurrence may be beyond today's validity window; still show its full date.
        // Subsequent occurrences within that window allow a widget timeline to advance offline.
        for _ in 0..9 {
            let Some(date) = registration_next(row, cursor, calendar.clone()) else {
                break;
            };
            if cursor != now && date >= until {
                break;
            }
            items.push(AlarmWidgetItem {
                name: row.routine.name.clone(),
                kind: row.routine.kind,
                delivery: row.routine.delivery,
                purpose: row.purpose.clone(),
                date,
                registered,
            });
            cursor = date;
        }
    }
    // A failed cancellation can leave an old native alarm behind. Never show an all-clear state.
    attention |= archive.installed.iter().any(|r| {
        verified.contains(&r.id)
            && r.purpose != "test"
            && !desired.contains(r)
            && !(matches!(r.purpose.as_str(), "snooze" | "followUp")
                && archive
                    .routines
                    .iter()
                    .any(|routine| routine.id == r.routine.id && routine.enabled))
            && registration_next(r, now, calendar.clone()).is_some()
    });
    items.sort_by(|a, b| a.date.cmp(&b.date).then_with(|| a.name.cmp(&b.name)));
    if items.len() > LIMIT {
        until = until.min(items[LIMIT].date);
        items.truncate(LIMIT);
    }
    Ok(AlarmWidgetSnapshot {
        version: 1,
        updated: now,
        valid_until: until,
        time_zone: calendar.time_zone_id,
        needs_attention: attention,
        items,
    })
}

#[uniffi::export]
pub fn alarm_widget_view(
    snapshot: &AlarmWidgetSnapshot,
    now: SystemTime,
    time_zone: String,
) -> AlarmWidgetView {
    let stale = snapshot.version != 1
        || now < snapshot.updated
        || now >= snapshot.valid_until
        || time_zone != snapshot.time_zone;
    AlarmWidgetView {
        stale,
        needs_attention: snapshot.needs_attention,
        items: if stale {
            vec![]
        } else {
            snapshot
                .items
                .iter()
                .filter(|i| i.date > now)
                .take(3)
                .cloned()
                .collect()
        },
    }
}

fn validate(snapshot: &AlarmWidgetSnapshot) -> Result<(), LoopError> {
    if snapshot.version != 1
        || snapshot.items.len() > LIMIT
        || snapshot.valid_until < snapshot.updated
        || snapshot
            .valid_until
            .duration_since(snapshot.updated)
            .unwrap_or_default()
            > Duration::from_secs(86_400)
        || snapshot.time_zone.len() > 128
        || snapshot.items.iter().any(|i| {
            crate::text::characters(&i.name) > 100
                || i.date <= snapshot.updated
                || !matches!(i.purpose.as_str(), "schedule" | "snooze" | "followUp")
        })
        || snapshot.items.windows(2).any(|w| w[0].date > w[1].date)
    {
        return Err(invalid("Open the app to refresh the widget."));
    }
    Ok(())
}

#[uniffi::export]
pub fn alarm_widget_encode(snapshot: &AlarmWidgetSnapshot) -> Result<String, LoopError> {
    validate(snapshot)?;
    let text =
        serde_json::to_string(snapshot).map_err(|_| invalid("Could not prepare the widget."))?;
    if text.len() > MAX_BYTES {
        return Err(invalid("Widget snapshot is too large."));
    }
    Ok(text)
}

#[uniffi::export]
pub fn alarm_widget_decode(text: String) -> Result<AlarmWidgetSnapshot, LoopError> {
    if text.len() > MAX_BYTES {
        return Err(invalid("Widget snapshot is too large."));
    }
    let snapshot =
        serde_json::from_str(&text).map_err(|_| invalid("Open the app to refresh the widget."))?;
    validate(&snapshot)?;
    Ok(snapshot)
}
