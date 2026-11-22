use crate::{Error, Result, ensure, model::*, time};
use serde::{Deserialize, Serialize};
use uuid::Uuid;
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Occurrence {
    pub id: String,
    pub plan_id: String,
    pub day: String,
    pub at: i64,
    pub title: String,
    pub phase: String,
    pub status: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Reminder {
    pub id: String,
    pub at: i64,
    pub title: String,
    pub body: String,
    pub dose: Option<Occurrence>,
}
pub fn dose_id(plan: &str, day: &str) -> Result<String> {
    let id = Uuid::parse_str(plan).map_err(|_| Error::invalid("Invalid plan identifier."))?;
    Ok(seven_schedule::identifier(id, &format!("bloom-dose:{day}")).to_string())
}
pub fn phase(p: &Plan, day: &str) -> Result<Option<&'static str>> {
    let n = time::distance(&p.start, day)?;
    if n < 0 || p.end.as_ref().is_some_and(|end| day > end.as_str()) {
        return Ok(None);
    }
    Ok(match p.pattern.as_str() {
        "pack" => {
            if n % (p.active_days + p.break_days) < p.active_days {
                Some("active")
            } else if p.remind_break {
                Some("break")
            } else {
                None
            }
        }
        "interval" => {
            if n % p.every_days == 0 {
                Some("scheduled")
            } else {
                None
            }
        }
        "weekly" => {
            if p.weekdays
                .contains(&(time::date(day)?.weekday().to_sunday_one_offset() as i32))
            {
                Some("scheduled")
            } else {
                None
            }
        }
        "once" => {
            if n == 0 {
                Some("scheduled")
            } else {
                None
            }
        }
        _ => Some("scheduled"),
    })
}
pub fn occurrences(a: &Archive, now: i64, zone: &str, days: i32) -> Result<Vec<Occurrence>> {
    ensure(
        (1..=370).contains(&days) && now > 172_800,
        "Invalid schedule window.",
    )?;
    let mut out = vec![];
    for p in a.plans.iter().filter(|p| p.enabled) {
        // Two days back, so a dose whose day began before `now` (or before a zone change) is still
        // found; `days + 2` forward to match.
        let start = jiff::Timestamp::from_second(now - 172_800).map_err(|_| Error::invalid("Invalid schedule date."))?;
        Uuid::parse_str(&p.id).map_err(|_| Error::invalid("Invalid plan ID."))?;
        let weekdays = if p.pattern == "weekly" { p.weekdays.clone() } else { vec![1, 2, 3, 4, 5, 6, 7] };
        // The checks this had while it went through Alarm's routine validation, kept as they were.
        let distinct = weekdays.iter().collect::<std::collections::HashSet<_>>().len() == weekdays.len();
        ensure(
            (0..1440).contains(&p.minute) && !weekdays.is_empty() && distinct && weekdays.iter().all(|d| (1..=7).contains(d)),
            "Could not calculate the reminder schedule.",
        )?;
        let z = if p.fixed_zone { &p.zone } else { zone };
        let dates = seven_schedule::weekly_occurrences(p.minute, &weekdays, start, days + 2, &seven_schedule::zone(z));
        for date in dates {
            let at = date.as_second();
            let day = time::today(at, z)?;
            if let Some(phase) = phase(p, &day)? {
                let id = dose_id(&p.id, &day)?;
                let status = a
                    .doses
                    .iter()
                    .find(|d| d.id == id)
                    .map(|d| d.status.clone())
                    .unwrap_or("pending".into());
                out.push(Occurrence {
                    id,
                    plan_id: p.id.clone(),
                    day,
                    at,
                    title: p.name.clone(),
                    phase: phase.into(),
                    status,
                });
            }
        }
    }
    out.sort_by_key(|o| o.at);
    Ok(out)
}
pub fn reminders(a: &Archive, now: i64, zone: &str) -> Result<Vec<Reminder>> {
    let mut out = vec![];
    for o in occurrences(a, now, zone, 180)? {
        if ["taken", "skipped"].contains(&o.status.as_str()) {
            continue;
        }
        let at = a
            .doses
            .iter()
            .find(|d| d.id == o.id)
            .and_then(|d| d.snooze_until)
            .unwrap_or(o.at);
        if at <= now {
            continue;
        }
        let title = if a.profile.discreet {
            "Your reminder".into()
        } else {
            o.title.clone()
        };
        let body = if a.profile.discreet {
            "Open the app to view and record it.".into()
        } else if o.phase == "break" {
            "A reminder for the break or placebo part of your plan.".into()
        } else {
            "Record taken or skipped. Dismissing this alert does not record a dose.".into()
        };
        out.push(Reminder {
            id: format!("bloom.dose.{}", o.id),
            at,
            title,
            body,
            dose: Some(o),
        });
    }
    for p in a.appointments.iter().filter(|p| p.remind_minutes >= 0) {
        let at = p.at - i64::from(p.remind_minutes) * 60;
        if at > now {
            out.push(Reminder {
                id: format!("bloom.appointment.{}", p.id),
                at,
                title: if a.profile.discreet {
                    "Your reminder".into()
                } else {
                    p.title.clone()
                },
                body: "An appointment or planned event is coming up.".into(),
                dose: None,
            });
        }
    }
    out.sort_by_key(|r| r.at);
    Ok(out)
}
