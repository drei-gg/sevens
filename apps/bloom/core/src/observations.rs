//! A derived daily view. Raw daily summaries and timed observations remain independently editable.
use crate::{Result, model::*};
use std::collections::BTreeMap;

pub fn days(a: &Archive) -> Vec<DayLog> {
    let mut rows: BTreeMap<String, DayLog> =
        a.days.iter().map(|d| (d.day.clone(), d.clone())).collect();
    let mut events: Vec<_> = a.observations.iter().collect();
    events.sort_by_key(|o| (o.at, &o.id));
    for o in events {
        let d = rows.entry(o.day.clone()).or_insert_with(|| DayLog {
            id: o.id.clone(),
            day: o.day.clone(),
            flow: "none".into(),
            period_start: false,
            symptoms: BTreeMap::new(),
            tags: vec![],
            notes: String::new(),
            bbt: None,
            mucus: "none".into(),
            ovulation_test: "none".into(),
            pregnancy_test: "none".into(),
        });
        match o.kind.as_str() {
            "flow" => {
                let levels = ["none", "spotting", "light", "medium", "heavy"];
                if levels.iter().position(|v| *v == o.value)
                    > levels.iter().position(|v| *v == d.flow)
                {
                    d.flow = o.value.clone();
                }
                d.period_start |= o.period_start;
            }
            "symptom" => {
                let value = d.symptoms.entry(o.label.clone()).or_insert(0);
                *value = (*value).max(o.level.unwrap_or(0));
            }
            // Daily overview shows the latest timed test; the event timeline keeps every result.
            "ovulation_test" => d.ovulation_test = o.value.clone(),
            "pregnancy_test" => d.pregnancy_test = o.value.clone(),
            _ => {}
        }
    }
    rows.into_values().collect()
}

pub fn exclusion(a: &Archive, start: &str, end: &str) -> Option<String> {
    let overlaps = |s: &str, e: Option<&str>| s <= end && e.is_none_or(|e| e >= start);
    if a.pregnancies
        .iter()
        .any(|p| overlaps(&p.start, p.ended.as_deref()))
    {
        return Some("Pregnancy interval".into());
    }
    if let Some(e) = a
        .exclusions
        .iter()
        .find(|e| overlaps(&e.start, e.end.as_deref()))
    {
        return Some(e.reason.clone());
    }
    if a.plans.iter().any(|p| {
        p.hormonal && (p.enabled || p.end.is_some()) && overlaps(&p.start, p.end.as_deref())
    }) {
        return Some("Hormonal contraception".into());
    }
    None
}

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct TemperaturePoint {
    pub id: String,
    pub day: String,
    pub at: Option<i64>,
    pub value: f64,
}
#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct PlanPosition {
    pub id: String,
    pub day: i32,
    pub total: i32,
    pub active: i32,
    pub phase: String,
}
pub fn positions(a: &Archive, now: i64, zone: &str) -> Result<Vec<PlanPosition>> {
    let mut out = vec![];
    for p in a.plans.iter().filter(|p| p.enabled && p.pattern == "pack") {
        let today = crate::time::today(now, if p.fixed_zone { &p.zone } else { zone })?;
        let n = crate::time::distance(&p.start, &today)?;
        if n < 0 || p.end.as_ref().is_some_and(|end| end < &today) {
            continue;
        }
        let total = p.active_days + p.break_days;
        let day = n % total + 1;
        out.push(PlanPosition {
            id: p.id.clone(),
            day,
            total,
            active: p.active_days,
            phase: if day <= p.active_days {
                "Active".into()
            } else {
                "Break / placebo".into()
            },
        });
    }
    Ok(out)
}
