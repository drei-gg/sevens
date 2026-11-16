use crate::{Error, Result, ensure};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};

pub const MAX_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_RECORDS: usize = 50_000;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub mode: String,
    pub typical_cycle: i32,
    pub typical_period: i32,
    pub lactating: bool,
    pub discreet: bool,
    pub period_reminder: bool,
    pub watch_enabled: bool,
    pub widgets_enabled: bool,
}
impl Default for Profile {
    fn default() -> Self {
        Self {
            mode: "cycle".into(),
            typical_cycle: 28,
            typical_period: 5,
            lactating: false,
            discreet: true,
            period_reminder: false,
            watch_enabled: false,
            widgets_enabled: false,
        }
    }
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct DayLog {
    pub id: String,
    pub day: String,
    pub flow: String,
    pub period_start: bool,
    pub symptoms: BTreeMap<String, i32>,
    pub tags: Vec<String>,
    pub notes: String,
    pub bbt: Option<f64>,
    pub mucus: String,
    pub ovulation_test: String,
    pub pregnancy_test: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Contact {
    pub id: String,
    pub day: String,
    pub at: i64,
    pub activity: String,
    pub protection: String,
    pub libido: Option<i32>,
    pub orgasm: Option<bool>,
    pub pain: Option<i32>,
    pub notes: String,
    pub tags: Vec<String>,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Plan {
    pub id: String,
    pub name: String,
    pub method: String,
    pub hormonal: bool,
    pub start: String,
    pub end: Option<String>,
    pub minute: i32,
    pub zone: String,
    pub fixed_zone: bool,
    pub pattern: String,
    pub active_days: i32,
    pub break_days: i32,
    pub every_days: i32,
    pub weekdays: Vec<i32>,
    pub remind_break: bool,
    pub enabled: bool,
    pub initial_stock: Option<i32>,
    pub notes: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Dose {
    pub id: String,
    pub plan_id: String,
    pub day: String,
    pub scheduled: i64,
    pub status: String,
    pub recorded: i64,
    pub snooze_until: Option<i64>,
    pub notes: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Refill {
    pub id: String,
    pub plan_id: String,
    pub day: String,
    pub quantity: i32,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Appointment {
    pub id: String,
    pub title: String,
    pub at: i64,
    pub remind_minutes: i32,
    pub notes: String,
    pub kind: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Pregnancy {
    pub id: String,
    pub start: String,
    pub due: String,
    pub ended: Option<String>,
    pub outcome: String,
    pub notes: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Receipt {
    pub id: String,
    pub digest: String,
    pub at: i64,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Archive {
    pub schema: u32,
    pub revision: u64,
    pub profile: Profile,
    pub days: Vec<DayLog>,
    pub contacts: Vec<Contact>,
    pub plans: Vec<Plan>,
    pub doses: Vec<Dose>,
    pub refills: Vec<Refill>,
    pub appointments: Vec<Appointment>,
    pub pregnancies: Vec<Pregnancy>,
    pub receipts: Vec<Receipt>,
}
impl Default for Archive {
    fn default() -> Self {
        Self {
            schema: 1,
            revision: 0,
            profile: Profile::default(),
            days: vec![],
            contacts: vec![],
            plans: vec![],
            doses: vec![],
            refills: vec![],
            appointments: vec![],
            pregnancies: vec![],
            receipts: vec![],
        }
    }
}
fn text(s: &str, max: usize) -> Result<()> {
    ensure(
        s.chars().count() <= max && !s.contains('\0'),
        "Text is too long or contains an invalid character.",
    )
}
fn uuid(s: &str) -> Result<()> {
    ensure(
        uuid::Uuid::parse_str(s).is_ok(),
        "Invalid record identifier.",
    )
}
fn day(s: &str) -> Result<()> {
    crate::time::date(s).map(|_| ())
}
fn one(s: &str, values: &[&str]) -> Result<()> {
    ensure(
        values.contains(&s),
        "Unsupported record value. Update the app if this file came from a newer version.",
    )
}
fn tags(t: &[String]) -> Result<()> {
    ensure(t.len() <= 24, "Use at most 24 tags.")?;
    for s in t {
        text(s, 60)?;
    }
    Ok(())
}
fn unique<'a>(ids: impl Iterator<Item = &'a str>) -> Result<()> {
    let mut seen = HashSet::new();
    for id in ids {
        uuid(id)?;
        ensure(seen.insert(id), "Duplicate record identifiers.")?;
    }
    Ok(())
}
pub fn validate(a: &Archive) -> Result<()> {
    ensure(
        a.schema == 1,
        "This archive needs a newer version of the app. The original file has not been changed.",
    )?;
    ensure(
        a.days.len()
            + a.contacts.len()
            + a.doses.len()
            + a.refills.len()
            + a.appointments.len()
            + a.pregnancies.len()
            <= MAX_RECORDS,
        "Archive exceeds the supported record limit.",
    )?;
    ensure(
        a.plans.len() <= 100 && a.receipts.len() <= 20_000,
        "Archive has too many plans or pending history receipts.",
    )?;
    let p = &a.profile;
    one(
        &p.mode,
        &[
            "cycle",
            "conceive",
            "pregnancy",
            "postpartum",
            "perimenopause",
        ],
    )?;
    ensure(
        (15..=90).contains(&p.typical_cycle) && (1..=20).contains(&p.typical_period),
        "Check typical cycle and period lengths.",
    )?;
    unique(a.days.iter().map(|x| x.id.as_str()))?;
    unique(a.contacts.iter().map(|x| x.id.as_str()))?;
    unique(a.plans.iter().map(|x| x.id.as_str()))?;
    unique(a.doses.iter().map(|x| x.id.as_str()))?;
    unique(a.refills.iter().map(|x| x.id.as_str()))?;
    unique(a.appointments.iter().map(|x| x.id.as_str()))?;
    unique(a.pregnancies.iter().map(|x| x.id.as_str()))?;
    unique(a.receipts.iter().map(|x| x.id.as_str()))?;
    let mut days = HashSet::new();
    for d in &a.days {
        day(&d.day)?;
        ensure(
            days.insert(&d.day),
            "Only one daily log is allowed per date.",
        )?;
        one(&d.flow, &["none", "spotting", "light", "medium", "heavy"])?;
        ensure(
            !d.period_start || ["light", "medium", "heavy"].contains(&d.flow.as_str()),
            "A period start needs a period flow entry.",
        )?;
        ensure(d.symptoms.len() <= 80, "Too many symptoms in one entry.")?;
        for (k, v) in &d.symptoms {
            text(k, 60)?;
            ensure(
                !k.trim().is_empty() && (0..=3).contains(v),
                "Symptoms use a severity from 0 to 3.",
            )?;
        }
        text(&d.notes, 10_000)?;
        tags(&d.tags)?;
        if let Some(t) = d.bbt {
            ensure(
                t.is_finite() && (30.0..=45.0).contains(&t),
                "Temperature must be between 30 and 45 °C.",
            )?;
        }
        one(
            &d.mucus,
            &["none", "dry", "sticky", "creamy", "watery", "egg_white"],
        )?;
        one(
            &d.ovulation_test,
            &["none", "negative", "positive", "unclear"],
        )?;
        one(
            &d.pregnancy_test,
            &["none", "negative", "positive", "unclear"],
        )?;
    }
    for c in &a.contacts {
        day(&c.day)?;
        text(&c.activity, 80)?;
        text(&c.protection, 100)?;
        text(&c.notes, 10_000)?;
        tags(&c.tags)?;
        for v in [c.libido, c.pain].into_iter().flatten() {
            ensure((0..=3).contains(&v), "Ratings use a value from 0 to 3.")?;
        }
        ensure((0..=7_258_118_400).contains(&c.at), "Invalid event time.")?;
    }
    for p in &a.plans {
        ensure(!p.name.trim().is_empty(), "Give this reminder a name.")?;
        text(&p.name, 100)?;
        text(&p.notes, 5000)?;
        one(
            &p.method,
            &[
                "pill",
                "ring",
                "patch",
                "injection",
                "implant",
                "iud",
                "medication",
                "emergency",
            ],
        )?;
        one(&p.pattern, &["daily", "pack", "interval", "weekly", "once"])?;
        day(&p.start)?;
        if let Some(e) = &p.end {
            day(e)?;
            ensure(e >= &p.start, "The end date is before the start date.")?;
        }
        ensure(
            jiff::tz::TimeZone::get(&p.zone).is_ok(),
            "Unknown time zone.",
        )?;
        ensure(
            (0..1440).contains(&p.minute)
                && (1..=365).contains(&p.active_days)
                && (0..=365).contains(&p.break_days)
                && (1..=3650).contains(&p.every_days),
            "Check the reminder time and schedule lengths.",
        )?;
        ensure(
            !p.weekdays.is_empty()
                && p.weekdays.len() <= 7
                && p.weekdays.iter().all(|d| (1..=7).contains(d)),
            "Choose valid weekdays.",
        )?;
        if let Some(n) = p.initial_stock {
            ensure((0..=1_000_000).contains(&n), "Check the pill count.")?;
        }
    }
    let plans: HashSet<_> = a.plans.iter().map(|p| p.id.as_str()).collect();
    for d in &a.doses {
        day(&d.day)?;
        ensure(
            plans.contains(d.plan_id.as_str()),
            "A dose refers to a missing plan.",
        )?;
        ensure(
            d.id == crate::schedule::dose_id(&d.plan_id, &d.day)?,
            "Dose identifier does not match its plan and date.",
        )?;
        one(&d.status, &["taken", "skipped", "snoozed"])?;
        text(&d.notes, 5000)?;
        ensure(
            d.status == "snoozed" || d.snooze_until.is_none(),
            "Only a snoozed dose can have a reminder override.",
        )?;
        ensure(d.scheduled > 0 && d.recorded > 0, "Invalid dose timestamp.")?;
    }
    for r in &a.refills {
        day(&r.day)?;
        ensure(
            plans.contains(r.plan_id.as_str()) && (1..=100_000).contains(&r.quantity),
            "Check the refill plan and quantity.",
        )?;
    }
    for p in &a.appointments {
        text(&p.title, 200)?;
        ensure(
            !p.title.trim().is_empty() && p.at > 0 && (-1..=10080).contains(&p.remind_minutes),
            "Check the appointment details.",
        )?;
        text(&p.notes, 5000)?;
        text(&p.kind, 50)?;
    }
    let mut ongoing = 0;
    for p in &a.pregnancies {
        day(&p.start)?;
        day(&p.due)?;
        ensure(
            crate::time::distance(&p.start, &p.due)? >= 0
                && crate::time::distance(&p.start, &p.due)? <= 320,
            "Check the pregnancy start and due dates.",
        )?;
        if let Some(e) = &p.ended {
            day(e)?;
            ensure(e >= &p.start, "The end date is before the pregnancy start.")?;
        } else {
            ongoing += 1;
        };
        text(&p.outcome, 100)?;
        text(&p.notes, 10_000)?;
    }
    ensure(
        ongoing <= 1,
        "End the existing pregnancy before starting another.",
    )?;
    ensure(
        a.profile.mode != "pregnancy" || ongoing == 1,
        "Add an ongoing pregnancy before selecting pregnancy mode.",
    )?;
    Ok(())
}
pub fn decode(json: &str) -> Result<Archive> {
    ensure(json.len() <= MAX_BYTES, "File is too large.")?;
    let a:Archive=serde_json::from_str(json).map_err(|_|Error::Invalid{reason:"The archive is unreadable or uses unsupported fields. The original file has not been changed.".into()})?;
    validate(&a)?;
    Ok(a)
}
