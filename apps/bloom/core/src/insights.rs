use crate::{
    Result,
    model::*,
    schedule::{self, Occurrence, Reminder},
    time,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Cycle {
    pub start: String,
    pub length: Option<i32>,
    pub bleeding_days: i32,
    pub excluded_reason: Option<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct SymptomStat {
    pub name: String,
    pub count: i32,
    pub logged_days: i32,
    pub average: f64,
    pub preperiod_count: i32,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Stock {
    pub plan_id: String,
    pub name: String,
    pub remaining: i64,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Dashboard {
    pub today: String,
    pub cycle_day: Option<i32>,
    pub cycles: Vec<Cycle>,
    pub lengths: Vec<i32>,
    pub next_period: Option<String>,
    pub earliest: Option<String>,
    pub latest: Option<String>,
    pub prediction_note: String,
    pub fertile_start: Option<String>,
    pub fertile_end: Option<String>,
    pub symptoms: Vec<SymptomStat>,
    pub today_doses: Vec<Occurrence>,
    pub reminders: Vec<Reminder>,
    pub coverage_until: Option<i64>,
    pub reminder_total: i32,
    pub taken: i32,
    pub skipped: i32,
    pub stock: Vec<Stock>,
    pub pregnancy_week: Option<i32>,
    pub pregnancy_day: Option<i32>,
    pub observed_days: Vec<DayLog>,
    pub temperatures: Vec<crate::observations::TemperaturePoint>,
    pub plan_positions: Vec<crate::observations::PlanPosition>,
    pub sample_size: usize,
    pub estimate_days: Option<i32>,
    pub typical_length: i32,
    pub history_note: String,
}
pub fn cycles(a: &Archive) -> Result<Vec<Cycle>> {
    let observed = crate::observations::days(a);
    let mut days: Vec<_> = observed
        .iter()
        .filter(|d| ["light", "medium", "heavy"].contains(&d.flow.as_str()))
        .collect();
    days.sort_by_key(|d| d.day.clone());
    let mut out: Vec<Cycle> = vec![];
    let mut prev: Option<&str> = None;
    for d in days {
        if prev.is_none() || d.period_start || time::distance(prev.unwrap(), &d.day)? > 2 {
            if let Some(last) = out.last_mut() {
                last.length = Some(time::distance(&last.start, &d.day)?);
            }
            out.push(Cycle {
                start: d.day.clone(),
                length: None,
                bleeding_days: 1,
                excluded_reason: None,
            });
        } else if let Some(last) = out.last_mut() {
            last.bleeding_days += 1;
        }
        prev = Some(&d.day);
    }
    for i in 0..out.len() {
        let end = out
            .get(i + 1)
            .map(|c| time::add(&c.start, -1))
            .transpose()?
            .unwrap_or_else(|| out[i].start.clone());
        out[i].excluded_reason = crate::observations::exclusion(a, &out[i].start, &end);
    }
    Ok(out)
}
pub fn dashboard(a: &Archive, now: i64, zone: &str) -> Result<Dashboard> {
    let mut d = full_dashboard(a, now, zone)?;
    d.reminders.truncate(56);
    d.coverage_until = d.reminders.last().map(|r| r.at);
    Ok(d)
}
pub fn full_dashboard(a: &Archive, now: i64, zone: &str) -> Result<Dashboard> {
    let today = time::today(now, zone)?;
    let cycles = cycles(a)?;
    let lengths: Vec<i32> = cycles
        .iter()
        .filter(|c| c.excluded_reason.is_none())
        .filter_map(|c| c.length)
        .collect();
    let recent: Vec<_> = lengths.iter().rev().take(6).copied().collect();
    let hormonal = a.plans.iter().any(|p| {
        p.hormonal && p.enabled && p.start <= today && p.end.as_ref().is_none_or(|e| e >= &today)
    });
    let suppressed = a.profile.mode == "pregnancy"
        || a.profile.mode == "postpartum"
        || a.profile.lactating
        || hormonal;
    let last = cycles.last();
    let anchor_exclusion = last.and_then(|c| crate::observations::exclusion(a, &c.start, &today));
    let cycle_day = last
        .map(|c| time::distance(&c.start, &today).map(|n| n + 1))
        .transpose()?;
    let mut note =
        "Log a period to begin. Predictions are estimates, not contraception.".to_string();
    let (mut next, mut earliest, mut latest, mut fertile_start, mut fertile_end) =
        (None, None, None, None, None);
    if suppressed || anchor_exclusion.is_some() {
        note="Cycle predictions are paused for your current mode or contraception. Keep logging what you observe.".into();
    } else if let Some(last) = last {
        let plausible = recent.iter().all(|n| (15..=90).contains(n));
        if !plausible {
            note="Your recent records vary widely. Review dates and explicit period starts; a calendar estimate would be misleading.".into();
        } else {
            let mean = if recent.is_empty() {
                a.profile.typical_cycle
            } else {
                (recent.iter().sum::<i32>() as f64 / recent.len() as f64).round() as i32
            };
            let predicted = time::add(&last.start, mean)?;
            if time::distance(&predicted, &today)? > 7 {
                note="The previous estimate has passed. We do not keep moving it forward. Log a new period or review your history.".into();
            } else {
                next = Some(predicted.clone());
                if recent.len() >= 2 {
                    earliest = Some(time::add(&last.start, *recent.iter().min().unwrap())?);
                    latest = Some(time::add(&last.start, *recent.iter().max().unwrap())?);
                }
                note = if recent.len() < 2 {
                    "Based on limited history and your typical cycle setting. More logs may change this estimate.".into()
                } else {
                    "The range reflects your recent cycle lengths, not a confidence interval. Missing logs can change the estimate.".into()
                };
                if a.profile.mode == "conceive"
                    && recent.len() >= 3
                    && recent.iter().all(|n| (21..=35).contains(n))
                    && recent.iter().max().unwrap() - recent.iter().min().unwrap() <= 8
                {
                    fertile_start = Some(time::add(&predicted, -19)?);
                    fertile_end = Some(time::add(&predicted, -14)?);
                }
            }
        }
    }
    let mut stats: BTreeMap<String, (i32, i32, i32, i32)> = BTreeMap::new();
    let observed_days = crate::observations::days(a);
    for d in &observed_days {
        if time::distance(&d.day, &today)? < 0 || time::distance(&d.day, &today)? > 180 {
            continue;
        }
        for (name, severity) in &d.symptoms {
            let e = stats.entry(name.clone()).or_default();
            e.1 += 1;
            if *severity > 0 {
                e.0 += 1;
                e.2 += severity;
                if cycles
                    .iter()
                    .any(|c| time::distance(&d.day, &c.start).is_ok_and(|n| (1..=7).contains(&n)))
                {
                    e.3 += 1;
                }
            }
        }
    }
    let mut symptoms: Vec<_> = stats
        .into_iter()
        .map(
            |(name, (count, logged_days, sum, preperiod_count))| SymptomStat {
                name,
                count,
                logged_days,
                average: if count == 0 {
                    0.0
                } else {
                    sum as f64 / count as f64
                },
                preperiod_count,
            },
        )
        .collect();
    symptoms.sort_by(|a, b| b.count.cmp(&a.count).then(a.name.cmp(&b.name)));
    let all = schedule::occurrences(a, now, zone, 2)?;
    let today_doses = all
        .into_iter()
        .filter(|o| time::today(o.at, zone).is_ok_and(|d| d == today))
        .collect();
    let mut reminders = schedule::reminders(a, now, zone)?;
    if a.profile.period_reminder {
        if let Some(n) = &next {
            let d = time::date(&time::add(n, -1)?)?;
            if let Ok(z) = d.at(9, 0, 0, 0).in_tz(zone) {
                let at = z.timestamp().as_second();
                if at > now {
                    reminders.push(Reminder {
                        id: "bloom.period".into(),
                        at,
                        title: if a.profile.discreet {
                            "Your reminder".into()
                        } else {
                            "Period estimate".into()
                        },
                        body: "Your period may begin soon. This is an estimate.".into(),
                        dose: None,
                        // 09:00 on the phone's clock, wherever it is.
                        floating: true,
                    });
                }
            }
        }
    }
    reminders.sort_by_key(|r| r.at);
    let reminder_total = reminders.len() as i32;
    let coverage_until = reminders.last().map(|r| r.at);
    let stock = a
        .plans
        .iter()
        .filter_map(|p| {
            p.initial_stock.map(|n| {
                let added: i64 = a
                    .refills
                    .iter()
                    .filter(|r| r.plan_id == p.id && r.day <= today)
                    .map(|r| i64::from(r.quantity))
                    .sum();
                let used = a
                    .doses
                    .iter()
                    .filter(|d| d.plan_id == p.id && d.status == "taken" && d.day <= today)
                    .count() as i64;
                Stock {
                    plan_id: p.id.clone(),
                    name: p.name.clone(),
                    remaining: (i64::from(n) + added - used).max(0),
                }
            })
        })
        .collect();
    let pregnancy = a.pregnancies.iter().find(|p| p.ended.is_none());
    let gestation = if a.profile.mode == "pregnancy" {
        pregnancy
            .map(|p| time::distance(&time::add(&p.due, -280)?, &today))
            .transpose()?
            .filter(|d| *d >= 0)
    } else {
        None
    };
    let mut temperatures: Vec<_> = a
        .days
        .iter()
        .filter_map(|d| {
            d.bbt.map(|value| crate::observations::TemperaturePoint {
                id: d.id.clone(),
                day: d.day.clone(),
                at: None,
                value,
            })
        })
        .collect();
    temperatures.extend(
        a.observations
            .iter()
            .filter(|o| o.kind == "temperature")
            .filter_map(|o| {
                o.number.map(|value| crate::observations::TemperaturePoint {
                    id: o.id.clone(),
                    day: o.day.clone(),
                    at: Some(o.at),
                    value,
                })
            }),
    );
    temperatures.sort_by_key(|p| (p.day.clone(), p.at));
    let excluded = cycles
        .iter()
        .filter(|c| c.length.is_some() && c.excluded_reason.is_some())
        .count();
    let estimate_days = next
        .as_deref()
        .map(|n| time::distance(&today, n))
        .transpose()?;
    let typical_length = if recent.is_empty() {
        a.profile.typical_cycle
    } else {
        (recent.iter().sum::<i32>() as f64 / recent.len() as f64).round() as i32
    };
    Ok(Dashboard {
        observed_days,
        temperatures,
        plan_positions: crate::observations::positions(a, now, zone)?,
        sample_size: recent.len(),
        estimate_days,
        typical_length,
        history_note: format!(
            "{} completed cycles included; {} excluded. Daily flow and symptom summaries use the highest recorded level; timed entries remain separate.",
            lengths.len(),
            excluded
        ),
        today,
        cycle_day,
        cycles,
        lengths,
        next_period: next,
        earliest,
        latest,
        prediction_note: note,
        fertile_start,
        fertile_end,
        symptoms,
        today_doses,
        reminders,
        coverage_until,
        reminder_total,
        taken: a.doses.iter().filter(|d| d.status == "taken").count() as i32,
        skipped: a.doses.iter().filter(|d| d.status == "skipped").count() as i32,
        stock,
        pregnancy_week: gestation.map(|d| d / 7),
        pregnancy_day: gestation.map(|d| d % 7),
    })
}
