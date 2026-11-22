//! The manually reviewed Health boundary contains flow only, never journal notes or contacts.
use crate::{Result, ensure, model::*};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FlowSample {
    pub day: String,
    pub flow: String,
    pub period_start: bool,
}

#[derive(Serialize)]
pub struct FlowExport {
    pub sync_id: String,
    pub version: i64,
    pub at: Option<i64>,
    pub zone: Option<String>,
    #[serde(flatten)]
    pub sample: FlowSample,
}

pub fn export(a: &Archive) -> Vec<FlowExport> {
    // Preserve the original daily identifiers. Timed entries have their own stable IDs and
    // actual instants, so editing/removing/reordering another observation cannot duplicate them.
    let mut out: Vec<_> = a
        .days
        .iter()
        .filter(|d| level(&d.flow).is_some())
        .map(|d| FlowExport {
            sync_id: format!("bloom.flow.{}", d.id),
            version: a.revision as i64 + 1,
            at: None,
            zone: None,
            sample: FlowSample {
                day: d.day.clone(),
                flow: d.flow.clone(),
                period_start: d.period_start,
            },
        })
        .chain(
            a.observations
                .iter()
                .filter(|o| o.kind == "flow" && level(&o.value).is_some())
                .map(|o| FlowExport {
                    sync_id: format!("bloom.observation.{}", o.id),
                    version: a.revision as i64 + 1,
                    at: Some(o.at),
                    zone: Some(o.zone.clone()),
                    sample: FlowSample {
                        day: o.day.clone(),
                        flow: o.value.clone(),
                        period_start: o.period_start,
                    },
                }),
        )
        .collect();
    out.sort_by(|a, b| (&a.sample.day, a.at, &a.sync_id).cmp(&(&b.sample.day, b.at, &b.sync_id)));
    out
}

fn level(flow: &str) -> Option<u8> {
    match flow {
        "light" => Some(1),
        "medium" => Some(2),
        "heavy" => Some(3),
        _ => None,
    }
}

pub fn review(a: &Archive, samples: Vec<FlowSample>) -> Result<Archive> {
    ensure(
        samples.len() <= MAX_RECORDS,
        "Too many Health samples to review at once.",
    )?;
    // A timed observation also owns its date. Import must never overwrite or supplement a
    // journal day behind the user's back, including when the preview became stale.
    let occupied: HashSet<_> = a
        .days
        .iter()
        .map(|d| &d.day)
        .chain(a.observations.iter().map(|o| &o.day))
        .collect();
    let mut days: BTreeMap<String, FlowSample> = BTreeMap::new();
    for sample in samples {
        crate::time::date(&sample.day)?;
        ensure(
            level(&sample.flow).is_some(),
            "Only light, medium and heavy flow can be transferred.",
        )?;
        if occupied.contains(&sample.day) {
            continue;
        }
        let day = days
            .entry(sample.day.clone())
            .or_insert_with(|| sample.clone());
        if level(&sample.flow) > level(&day.flow) {
            day.flow = sample.flow;
        }
        day.period_start |= sample.period_start;
    }
    let mut out = Archive::default();
    out.days = days
        .into_values()
        .map(|s| {
            // A deterministic journal ID keeps repeated reviews and cross-platform backups stable.
            let hash = Sha256::digest(format!("bloom.health.day.{}", s.day).as_bytes());
            let id =
                uuid::Uuid::from_bytes(hash[..16].try_into().expect("SHA-256 prefix")).to_string();
            DayLog {
                id,
                day: s.day,
                flow: s.flow,
                period_start: s.period_start,
                symptoms: BTreeMap::new(),
                tags: vec![],
                notes: String::new(),
                bbt: None,
                mucus: "none".into(),
                ovulation_test: "none".into(),
                pregnancy_test: "none".into(),
            }
        })
        .collect();
    validate(&out)?;
    Ok(out)
}
