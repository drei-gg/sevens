//! Minimal companion projection and the permitted command boundary. Native transports persist first.
use crate::{commands, ensure, insights, model::Archive, schedule::Occurrence, Error, Result};
use serde::{Deserialize, Serialize};
#[derive(Serialize)]
pub struct WatchSnapshot {
    version: u32,
    generated: i64,
    revision: u64,
    enabled: bool,
    cycle_day: Option<i32>,
    doses: Vec<Occurrence>,
    can_add: bool,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct WatchRequest {
    version: u32,
    id: String,
    command: Option<commands::Command>,
}
pub fn snapshot(a: &Archive, now: i64, zone: &str, paid: bool) -> Result<WatchSnapshot> {
    let enabled = a.profile.watch_enabled;
    let mut result = WatchSnapshot {
        version: 1,
        generated: now,
        revision: a.revision,
        enabled,
        cycle_day: None,
        doses: vec![],
        can_add: enabled && paid,
    };
    if enabled {
        let dashboard = insights::dashboard(a, now, zone)?;
        result.doses = dashboard.today_doses;
        if a.profile.discreet {
            for dose in &mut result.doses {
                dose.title = "Your reminder".into();
            }
        } else {
            result.cycle_day = dashboard.cycle_day;
        }
    }
    Ok(result)
}
pub fn apply(a: &mut Archive, request: &str, now: i64, zone: &str, paid: bool) -> Result<()> {
    ensure(request.len() <= 512_000, "The watch request is too large.")?;
    let request: WatchRequest =
        serde_json::from_str(request).map_err(|_| Error::invalid("Unreadable watch request."))?;
    ensure(
        request.version == 1 && uuid::Uuid::parse_str(&request.id).is_ok(),
        "Unsupported watch request.",
    )?;
    ensure(
        a.profile.watch_enabled,
        "Enable the watch companion on your phone first.",
    )?;
    if let Some(command) = request.command {
        ensure(
            ["dose", "day"].contains(&command.op.as_str()),
            "This action is not available from the watch.",
        )?;
        commands::apply(a, command, now, zone, paid)?;
    }
    Ok(())
}
