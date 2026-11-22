use crate::{Result, insights, model::*, time};
pub fn cell(s: &str) -> String {
    let s = if s.trim_start().starts_with(['=', '+', '-', '@']) || s.starts_with(['\t', '\r', '\n'])
    {
        format!("'{s}")
    } else {
        s.into()
    };
    format!("\"{}\"", s.replace('"', "\"\""))
}
pub fn csv(a: &Archive, contacts: bool, notes: bool) -> String {
    let mut rows = vec!["type,id,date_or_time,details,notes".to_string()];
    let mut row = |kind: &str, id: &str, date: &str, detail: String, note: &str| {
        rows.push(
            [
                kind.to_string(),
                id.into(),
                date.into(),
                detail,
                if notes { note.into() } else { String::new() },
            ]
            .iter()
            .map(|s| cell(s))
            .collect::<Vec<_>>()
            .join(","),
        )
    };
    for d in &a.days {
        let mut detail = d.clone();
        detail.notes.clear();
        row(
            "day",
            &d.id,
            &d.day,
            serde_json::to_string(&detail).unwrap_or_default(),
            &d.notes,
        );
    }
    for o in &a.observations {
        let mut detail = o.clone();
        detail.notes.clear();
        row(
            "observation",
            &o.id,
            &o.at.to_string(),
            serde_json::to_string(&detail).unwrap_or_default(),
            &o.notes,
        );
    }
    for e in &a.exclusions {
        row(
            "exclusion",
            &e.id,
            &e.start,
            format!(
                "until {}; {}",
                e.end.as_deref().unwrap_or("ongoing"),
                e.reason
            ),
            "",
        );
    }
    if contacts {
        for c in &a.contacts {
            let mut detail = c.clone();
            detail.notes.clear();
            row(
                "contact",
                &c.id,
                &c.at.to_string(),
                serde_json::to_string(&detail).unwrap_or_default(),
                &c.notes,
            );
        }
    }
    for d in &a.doses {
        row(
            "dose",
            &d.id,
            &d.day,
            format!(
                "{}; {}; scheduled {}; recorded {}",
                d.plan_id, d.status, d.scheduled, d.recorded
            ),
            &d.notes,
        );
    }
    for p in &a.pregnancies {
        row(
            "pregnancy",
            &p.id,
            &p.start,
            format!(
                "due {}; ended {}; {}",
                p.due,
                p.ended.clone().unwrap_or_default(),
                p.outcome
            ),
            &p.notes,
        );
    }
    for p in &a.appointments {
        row(
            "appointment",
            &p.id,
            &p.at.to_string(),
            p.title.clone(),
            &p.notes,
        );
    }
    rows.join("\r\n") + "\r\n"
}
pub fn report(a: &Archive, from: &str, to: &str, contacts: bool, notes: bool) -> Result<String> {
    time::date(from)?;
    time::date(to)?;
    crate::ensure(from <= to, "Choose a valid report date range.")?;
    let mut text = format!(
        "Cycle & Health Report\n{from} to {to}\n\nThese are self-reported observations, not a diagnosis. Missing entries do not mean symptoms were absent.\n"
    );
    let cs = insights::cycles(a)?;
    text.push_str("\nPERIOD HISTORY\n");
    for c in cs
        .iter()
        .filter(|c| c.start.as_str() >= from && c.start.as_str() <= to)
    {
        text.push_str(&format!(
            "{}: {} logged bleeding days; cycle length {}\n",
            c.start,
            c.bleeding_days,
            c.length
                .map(|n| format!("{n} days"))
                .unwrap_or("not yet known".into())
        ));
    }
    for c in &cs {
        if let Some(reason) = &c.excluded_reason {
            if c.start.as_str() >= from && c.start.as_str() <= to {
                text.push_str(&format!(
                    "{} excluded from estimates: {}\n",
                    c.start, reason
                ));
            }
        }
    }
    text.push_str("\nTIMED OBSERVATIONS\n");
    let mut events: Vec<_> = a
        .observations
        .iter()
        .filter(|o| o.day.as_str() >= from && o.day.as_str() <= to)
        .collect();
    events.sort_by_key(|o| o.at);
    for o in events {
        let at = jiff::Timestamp::from_second(o.at)
            .map_err(|_| crate::Error::invalid("Invalid observation time."))?
            .to_zoned(
                jiff::tz::TimeZone::get(&o.zone)
                    .map_err(|_| crate::Error::invalid("Invalid time zone."))?,
            );
        text.push_str(&format!(
            "{} {}: {} {} {}{}{}\n",
            at.datetime(),
            o.zone,
            o.kind,
            o.label,
            o.value,
            o.level.map(|v| format!(" level {v}/3")).unwrap_or_default(),
            o.number
                .map(|v| format!(" measured {v}"))
                .unwrap_or_default()
        ));
        if notes && !o.notes.is_empty() {
            text.push_str(&format!("  {}\n", o.notes));
        }
    }
    text.push_str("\nDAILY OBSERVATIONS\n");
    let mut days: Vec<_> = a
        .days
        .iter()
        .filter(|d| d.day.as_str() >= from && d.day.as_str() <= to)
        .collect();
    days.sort_by_key(|d| &d.day);
    for d in days {
        text.push_str(&format!(
            "{}: flow {}{}\n",
            d.day,
            d.flow,
            if d.period_start {
                "; marked period start"
            } else {
                ""
            }
        ));
        for (k, v) in &d.symptoms {
            text.push_str(&format!("  {k}: {v}/3\n"));
        }
        if let Some(t) = d.bbt {
            text.push_str(&format!("  BBT: {t:.2} °C\n"));
        }
        if d.mucus != "none" {
            text.push_str(&format!("  Cervical mucus: {}\n", d.mucus));
        }
        if d.ovulation_test != "none" {
            text.push_str(&format!("  Ovulation test: {}\n", d.ovulation_test));
        }
        if d.pregnancy_test != "none" {
            text.push_str(&format!("  Pregnancy test: {}\n", d.pregnancy_test));
        }
        if !d.tags.is_empty() {
            text.push_str(&format!("  Tags: {}\n", d.tags.join(", ")));
        }
        if notes && !d.notes.is_empty() {
            text.push_str(&format!("  Notes: {}\n", d.notes));
        }
    }
    text.push_str("\nMEDICATION AND CONTRACEPTION\n");
    for p in &a.plans {
        text.push_str(&format!(
            "{}: {}; from {}; {}\n",
            p.name,
            p.method,
            p.start,
            if p.enabled { "enabled" } else { "archived" }
        ));
    }
    for d in a
        .doses
        .iter()
        .filter(|d| d.day.as_str() >= from && d.day.as_str() <= to)
    {
        let name = a
            .plans
            .iter()
            .find(|p| p.id == d.plan_id)
            .map(|p| p.name.as_str())
            .unwrap_or("Plan");
        text.push_str(&format!("{}: {name}, {}\n", d.day, d.status));
        if notes && !d.notes.is_empty() {
            text.push_str(&format!("  {}\n", d.notes));
        }
    }
    if contacts {
        text.push_str("\nSEX / CONTACT (EXPLICITLY INCLUDED)\n");
        for c in a
            .contacts
            .iter()
            .filter(|c| c.day.as_str() >= from && c.day.as_str() <= to)
        {
            text.push_str(&format!(
                "{}: {}; protection: {}; pain: {}; orgasm: {}\n",
                c.day,
                c.activity,
                c.protection,
                c.pain
                    .map(|v| format!("{v}/3"))
                    .unwrap_or("not logged".into()),
                c.orgasm
                    .map(|v| if v { "yes" } else { "no" })
                    .unwrap_or("not logged")
            ));
            if notes {
                text.push_str(&format!("  {}\n", c.notes));
            }
        }
    }
    text.push_str("\nPREGNANCY HISTORY\n");
    for p in a
        .pregnancies
        .iter()
        .filter(|p| p.start.as_str() <= to && p.ended.as_deref().is_none_or(|e| e >= from))
    {
        text.push_str(&format!(
            "Start {}; due {}; ended {}; outcome {}\n",
            p.start,
            p.due,
            p.ended.clone().unwrap_or("ongoing".into()),
            p.outcome
        ));
    }
    Ok(text)
}
