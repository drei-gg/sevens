use crate::{Error, Result, ensure, model::*, schedule, time};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::Value;
use sha2::{Digest, Sha256};

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Command {
    pub op: String,
    pub record: Option<Value>,
    pub id: Option<String>,
    pub request_id: String,
    pub expected_revision: Option<u64>,
    pub created_at: i64,
}
fn record<T: DeserializeOwned>(c: &Command) -> Result<T> {
    serde_json::from_value(c.record.clone().ok_or(Error::invalid("Missing record."))?)
        .map_err(|_| Error::invalid("Invalid record fields."))
}
fn permitted(existing: bool, paid: bool) -> Result<()> {
    ensure(
        existing || paid,
        "Start a trial or unlock to add records. Existing records and scheduled doses remain available.",
    )
}
fn historical(day: &str, today: &str) -> Result<()> {
    time::date(day)?;
    ensure(day <= today, "Observations cannot be logged in the future.")
}
macro_rules! upsert {
    ($items:expr,$value:expr) => {{
        let v = $value;
        if let Some(i) = $items.iter().position(|x| x.id == v.id) {
            $items[i] = v;
        } else {
            $items.push(v);
        }
    }};
}

pub fn apply(a: &mut Archive, c: Command, now: i64, zone: &str, paid: bool) -> Result<()> {
    ensure(
        uuid::Uuid::parse_str(&c.request_id).is_ok(),
        "Invalid request identifier.",
    )?;
    let digest = format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(&c).map_err(|_| Error::invalid("Invalid command."))?)
    );
    if let Some(r) = a.receipts.iter().find(|r| r.id == c.request_id) {
        return ensure(
            r.digest == digest,
            "This request identifier was already used for a different action.",
        );
    }
    ensure(
        c.created_at <= now + 300 && c.created_at >= now - 7 * 86400,
        "This request is too old or its device clock is incorrect. Review the record on your phone.",
    )?;
    ensure(
        c.expected_revision.is_none_or(|r| r == a.revision),
        "Records changed while you were editing. Reopen the editor before saving.",
    )?;
    let today = time::today(now, zone)?;
    match c.op.as_str() {
        "day" => {
            let d: DayLog = record(&c)?;
            historical(&d.day, &today)?;
            permitted(a.days.iter().any(|x| x.id == d.id), paid)?;
            upsert!(a.days, d);
        }
        "observation" => {
            let d: Observation = record(&c)?;
            historical(&d.day, &time::today(now, &d.zone)?)?;
            ensure(d.at <= now + 300, "Observation time is in the future.")?;
            permitted(a.observations.iter().any(|x| x.id == d.id), paid)?;
            upsert!(a.observations, d);
        }
        "exclusion" => {
            let d: Exclusion = record(&c)?;
            historical(&d.start, &today)?;
            // Eligibility corrections remain available after trial expiry.
            upsert!(a.exclusions, d);
        }
        "contact" => {
            let d: Contact = record(&c)?;
            historical(&d.day, &today)?;
            ensure(d.at <= now + 300, "Contact time is in the future.")?;
            permitted(a.contacts.iter().any(|x| x.id == d.id), paid)?;
            upsert!(a.contacts, d);
        }
        "plan" => {
            let mut d: Plan = record(&c)?;
            let old = a.plans.iter().find(|x| x.id == d.id);
            permitted(
                old.is_some() && (!d.enabled || old.is_some_and(|p| p.enabled)),
                paid,
            )?;
            if old.is_some_and(|p| p.enabled) && !d.enabled && d.end.is_none() && d.start <= today {
                d.end = Some(today.clone());
            }
            upsert!(a.plans, d);
        }
        "dose" => {
            let mut d: Dose = record(&c)?;
            let p = a
                .plans
                .iter()
                .find(|p| p.id == d.plan_id)
                .ok_or(Error::invalid("This plan no longer exists."))?;
            let dose_zone = if p.fixed_zone { &p.zone } else { zone };
            historical(&d.day, &time::today(now, dose_zone)?)?;
            if !a.doses.iter().any(|old| old.id == d.id) {
                ensure(
                    schedule::phase(p, &d.day)?.is_some(),
                    "This date is outside the plan's scheduled days.",
                )?;
                let z = if p.fixed_zone { &p.zone } else { zone };
                ensure(
                    time::today(d.scheduled, z)? == d.day,
                    "The scheduled time does not match this dose date.",
                )?;
            }
            // A delayed Watch request must not overwrite an explicit phone correction.
            if c.expected_revision.is_none()
                && a.doses
                    .iter()
                    .any(|old| old.id == d.id && old.recorded > c.created_at)
            {
                return Err(Error::invalid(
                    "This dose changed on your phone. Review it before trying again.",
                ));
            }
            ensure(
                d.recorded <= now + 300 && d.recorded >= 1,
                "Check the recorded dose time.",
            )?;
            if d.status == "snoozed" {
                ensure(
                    d.snooze_until
                        .is_some_and(|t| t > now && t <= now + 24 * 3600),
                    "Choose a snooze within the next 24 hours.",
                )?;
            } else {
                d.snooze_until = None;
            }
            upsert!(a.doses, d);
        }
        "refill" => {
            let d: Refill = record(&c)?;
            historical(&d.day, &today)?;
            upsert!(a.refills, d);
        }
        "appointment" => {
            let d: Appointment = record(&c)?;
            permitted(a.appointments.iter().any(|x| x.id == d.id), paid)?;
            upsert!(a.appointments, d);
        }
        "pregnancy" => {
            let d: Pregnancy = record(&c)?;
            historical(&d.start, &today)?;
            if let Some(end) = &d.ended {
                historical(end, &today)?;
            }
            permitted(a.pregnancies.iter().any(|x| x.id == d.id), paid)?;
            if d.ended.is_none() {
                a.profile.mode = "pregnancy".into();
            } else if a.profile.mode == "pregnancy"
                && a.pregnancies
                    .iter()
                    .any(|p| p.id == d.id && p.ended.is_none())
            {
                a.profile.mode = "cycle".into();
            }
            upsert!(a.pregnancies, d);
        }
        "profile" => {
            let next: Profile = record(&c)?;
            for (source, active) in [
                ("postpartum", next.mode == "postpartum"),
                ("lactation", next.lactating),
            ] {
                let was = if source == "postpartum" {
                    a.profile.mode == "postpartum"
                } else {
                    a.profile.lactating
                };
                if active && !was {
                    a.exclusions.push(Exclusion {
                        id: uuid::Uuid::new_v4().to_string(),
                        start: today.clone(),
                        end: None,
                        reason: if source == "postpartum" {
                            "Postpartum".into()
                        } else {
                            "Lactation".into()
                        },
                        source: source.into(),
                    });
                } else if !active && was {
                    for e in a
                        .exclusions
                        .iter_mut()
                        .filter(|e| e.source == source && e.end.is_none())
                    {
                        e.end = Some(today.clone());
                    }
                }
            }
            a.profile = next;
        }
        "delete_observation" => {
            a.observations.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_exclusion" => {
            a.exclusions.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_day" => {
            a.days.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_contact" => {
            a.contacts.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_dose" => {
            a.doses.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_refill" => {
            a.refills.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_appointment" => {
            a.appointments.retain(|x| Some(&x.id) != c.id.as_ref());
        }
        "delete_pregnancy" => {
            a.pregnancies.retain(|x| Some(&x.id) != c.id.as_ref());
            if !a.pregnancies.iter().any(|p| p.ended.is_none()) && a.profile.mode == "pregnancy" {
                a.profile.mode = "cycle".into();
            }
        }
        "delete_plan" => {
            // Preserve dose/refill history. Deletion archives the plan rather than orphaning records.
            let p = a
                .plans
                .iter_mut()
                .find(|x| Some(&x.id) == c.id.as_ref())
                .ok_or(Error::invalid("Plan not found."))?;
            p.enabled = false;
            if p.end.is_none() && p.start <= today {
                p.end = Some(today.clone());
            }
        }
        _ => return Err(Error::invalid("Unsupported action.")),
    }
    a.receipts.retain(|r| r.at >= now - 8 * 86400);
    a.receipts.push(Receipt {
        id: c.request_id,
        digest,
        at: now,
    });
    a.revision = a
        .revision
        .checked_add(1)
        .ok_or(Error::invalid("Archive revision overflow."))?;
    validate(a)
}

pub fn merge(mut a: Archive, b: Archive) -> Result<Archive> {
    macro_rules! merge_rows {
        ($field:ident) => {
            for x in b.$field {
                if let Some(old) = a.$field.iter().find(|v| v.id == x.id) {
                    ensure(
                        old == &x,
                        "Import conflicts with an existing record. Nothing was imported.",
                    )?;
                } else {
                    a.$field.push(x);
                }
            }
        };
    }
    merge_rows!(days);
    merge_rows!(observations);
    merge_rows!(exclusions);
    merge_rows!(contacts);
    for mut p in b.plans {
        if let Some(old) = a.plans.iter().find(|v| v.id == p.id) {
            let mut normalized = old.clone();
            normalized.enabled = p.enabled;
            ensure(normalized == p, "Import conflicts with an existing plan.")?;
        } else {
            p.enabled = false;
            a.plans.push(p);
        }
    }
    merge_rows!(doses);
    merge_rows!(refills);
    for mut p in b.appointments {
        if let Some(old) = a.appointments.iter().find(|v| v.id == p.id) {
            let mut normalized = old.clone();
            normalized.remind_minutes = p.remind_minutes;
            ensure(
                normalized == p,
                "Import conflicts with an existing appointment.",
            )?;
        } else {
            p.remind_minutes = -1;
            a.appointments.push(p);
        }
    }
    merge_rows!(pregnancies);
    // Imports never activate reminders or change this device's privacy preferences/mode.
    a.revision = a
        .revision
        .checked_add(1)
        .ok_or(Error::invalid("Archive revision overflow."))?;
    validate(&a)?;
    Ok(a)
}
