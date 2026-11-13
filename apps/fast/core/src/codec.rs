//! Backups: the JSON archive, merge-on-import and the CSV export.

use crate::archive::{FastArchive, TrackerError};
use crate::model::*;
use crate::time::millis;
use serde::Serialize;
use std::collections::HashMap;
use std::time::SystemTime;
use uuid::Uuid;

pub const MAX_ARCHIVE_BYTES: u64 = 25 * 1_024 * 1_024;

#[uniffi::export]
pub fn archive_max_bytes() -> u64 {
    MAX_ARCHIVE_BYTES
}

/// Pretty-printed, keys sorted, dates in milliseconds: the layout the Swift app wrote.
#[uniffi::export]
pub fn archive_encode(archive: &FastArchive) -> Result<Vec<u8>, TrackerError> {
    pretty(archive).map_err(|_| TrackerError::Unreadable)
}

pub(crate) fn pretty<T: Serialize>(value: &T) -> serde_json::Result<Vec<u8>> {
    // Through `Value` so object keys come out sorted, like `.sortedKeys`.
    let value = serde_json::to_value(value)?;
    let mut out = Vec::new();
    let mut serializer = serde_json::Serializer::with_formatter(&mut out, FoundationPretty::default());
    value.serialize(&mut serializer)?;
    Ok(out)
}

/// Foundation's `JSONEncoder` `.prettyPrinted` layout, byte for byte: two-space indent,
/// `"key" : value`, empty containers opened and closed on their own lines, `/` escaped, whole
/// doubles without a decimal point and no trailing newline.
#[derive(Default)]
struct FoundationPretty {
    indent: usize,
    has_value: bool,
}

impl FoundationPretty {
    fn newline<W: ?Sized + std::io::Write>(&self, w: &mut W) -> std::io::Result<()> {
        w.write_all(b"\n")?;
        for _ in 0..self.indent {
            w.write_all(b"  ")?;
        }
        Ok(())
    }
    fn close<W: ?Sized + std::io::Write>(&mut self, w: &mut W, bracket: &[u8]) -> std::io::Result<()> {
        self.indent -= 1;
        if !self.has_value {
            w.write_all(b"\n")?;
        }
        self.newline(w)?;
        self.has_value = true;
        w.write_all(bracket)
    }
}

impl serde_json::ser::Formatter for FoundationPretty {
    fn begin_array<W: ?Sized + std::io::Write>(&mut self, w: &mut W) -> std::io::Result<()> {
        self.indent += 1;
        self.has_value = false;
        w.write_all(b"[")
    }
    fn end_array<W: ?Sized + std::io::Write>(&mut self, w: &mut W) -> std::io::Result<()> {
        self.close(w, b"]")
    }
    fn begin_array_value<W: ?Sized + std::io::Write>(&mut self, w: &mut W, first: bool) -> std::io::Result<()> {
        if !first {
            w.write_all(b",")?;
        }
        self.newline(w)
    }
    fn end_array_value<W: ?Sized + std::io::Write>(&mut self, _: &mut W) -> std::io::Result<()> {
        self.has_value = true;
        Ok(())
    }
    fn begin_object<W: ?Sized + std::io::Write>(&mut self, w: &mut W) -> std::io::Result<()> {
        self.indent += 1;
        self.has_value = false;
        w.write_all(b"{")
    }
    fn end_object<W: ?Sized + std::io::Write>(&mut self, w: &mut W) -> std::io::Result<()> {
        self.close(w, b"}")
    }
    fn begin_object_key<W: ?Sized + std::io::Write>(&mut self, w: &mut W, first: bool) -> std::io::Result<()> {
        if !first {
            w.write_all(b",")?;
        }
        self.newline(w)
    }
    fn begin_object_value<W: ?Sized + std::io::Write>(&mut self, w: &mut W) -> std::io::Result<()> {
        w.write_all(b" : ")
    }
    fn end_object_value<W: ?Sized + std::io::Write>(&mut self, _: &mut W) -> std::io::Result<()> {
        self.has_value = true;
        Ok(())
    }
    fn write_f64<W: ?Sized + std::io::Write>(&mut self, w: &mut W, value: f64) -> std::io::Result<()> {
        if value.fract() == 0.0 && value.abs() < 1e15 {
            write!(w, "{}", value as i64)
        } else {
            w.write_all(ryu_like(value).as_bytes())
        }
    }
    fn write_string_fragment<W: ?Sized + std::io::Write>(&mut self, w: &mut W, fragment: &str) -> std::io::Result<()> {
        w.write_all(fragment.replace('/', "\\/").as_bytes())
    }
}

/// Shortest round-trip decimal, as Swift's `description` writes it.
fn ryu_like(value: f64) -> String {
    let text = format!("{value}");
    text
}

/// Reads and validates a backup or the app's own file. Newer schemas are refused, not guessed at.
#[uniffi::export]
pub fn archive_decode(data: &[u8], now: SystemTime) -> Result<FastArchive, TrackerError> {
    if data.len() as u64 > MAX_ARCHIVE_BYTES {
        return Err(TrackerError::TooLarge);
    }
    #[derive(serde::Deserialize)]
    struct Header {
        #[serde(rename = "schemaVersion")]
        schema_version: i64,
    }
    let header: Header = serde_json::from_slice(data).map_err(|_| TrackerError::Unreadable)?;
    if header.schema_version != 1 {
        return Err(TrackerError::UnsupportedSchema);
    }
    let archive: FastArchive = serde_json::from_slice(data).map_err(|_| TrackerError::Unreadable)?;
    archive.validate(now)?;
    Ok(archive)
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct ImportPreview {
    pub archive: FastArchive,
    pub added_fasts: i32,
    pub added_journal: i32,
    pub added_metrics: i32,
    pub added_plans: i32,
    pub added_rest_days: i32,
    pub added_presets: i32,
    pub total_added: i32,
}

/// Existing settings win; restore is a merge, never a silent replacement of live tracking. A
/// record present in both must match exactly (dates within a microsecond), or the import fails.
#[uniffi::export]
pub fn archive_merge(incoming: &FastArchive, current: &FastArchive, now: SystemTime) -> Result<ImportPreview, TrackerError> {
    incoming.validate(now)?;
    current.validate(now)?;
    let mut next = current.clone();
    next.fasts = merged(&current.fasts, &incoming.fasts)?;
    next.journal = merged(&current.journal, &incoming.journal)?;
    next.metrics = merged(&current.metrics, &incoming.metrics)?;
    next.plans = merged(&current.plans, &incoming.plans)?;
    next.rest_days = merged(&current.rest_days, &incoming.rest_days)?;
    next.presets = merged(&current.presets, &incoming.presets)?;
    next.validate(now)?;
    let added = |a: usize, b: usize| (a - b) as i32;
    let preview = ImportPreview {
        added_fasts: added(next.fasts.len(), current.fasts.len()),
        added_journal: added(next.journal.len(), current.journal.len()),
        added_metrics: added(next.metrics.len(), current.metrics.len()),
        added_plans: added(next.plans.len(), current.plans.len()),
        added_rest_days: added(next.rest_days.len(), current.rest_days.len()),
        added_presets: added(next.presets.len(), current.presets.len()),
        total_added: 0,
        archive: next,
    };
    Ok(ImportPreview {
        total_added: preview.added_fasts + preview.added_journal + preview.added_metrics + preview.added_plans + preview.added_rest_days + preview.added_presets,
        ..preview
    })
}

/// Records whose dates may differ by JSON round-trip noise and nothing else.
trait Mergeable: Clone + PartialEq {
    fn id(&self) -> Uuid;
    /// Take `other`'s dates where they are within a microsecond of ours.
    fn align_dates(&mut self, other: &Self);
}

fn close(a: SystemTime, b: SystemTime) -> bool {
    (millis(a) - millis(b)).abs() <= 0.001
}

fn align(mine: &mut SystemTime, theirs: SystemTime) {
    if close(*mine, theirs) {
        *mine = theirs;
    }
}

impl Mergeable for FastRecord {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, other: &Self) {
        align(&mut self.start, other.start);
        if let (Some(mine), Some(theirs)) = (self.end, other.end) {
            if close(mine, theirs) {
                self.end = Some(theirs);
            }
        }
    }
}
impl Mergeable for JournalEntry {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, other: &Self) { align(&mut self.date, other.date) }
}
impl Mergeable for MetricEntry {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, other: &Self) { align(&mut self.date, other.date) }
}
impl Mergeable for PlannedFast {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, other: &Self) { align(&mut self.start, other.start) }
}
impl Mergeable for RestDay {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, other: &Self) { align(&mut self.date, other.date) }
}
impl Mergeable for FastPreset {
    fn id(&self) -> Uuid { self.id }
    fn align_dates(&mut self, _: &Self) {}
}

fn merged<T: Mergeable>(existing: &[T], incoming: &[T]) -> Result<Vec<T>, TrackerError> {
    let known: HashMap<Uuid, &T> = existing.iter().map(|x| (x.id(), x)).collect();
    for item in incoming {
        if let Some(old) = known.get(&item.id()) {
            let mut old = (*old).clone();
            old.align_dates(item);
            if old != *item {
                return Err(TrackerError::Conflict);
            }
        }
    }
    let mut result = existing.to_vec();
    result.extend(incoming.iter().filter(|x| !known.contains_key(&x.id())).cloned());
    Ok(result)
}

/// One typed table, so no metric or journal entry is left behind in a fasting-only export.
#[uniffi::export]
pub fn archive_csv(archive: &FastArchive) -> String {
    fn date(t: SystemTime) -> String {
        // ISO 8601 in UTC with milliseconds, as ISO8601DateFormatter `.withFractionalSeconds`.
        let ms = millis(t).floor() as i64;
        jiff::Timestamp::from_millisecond(ms)
            .map(|ts| ts.strftime("%Y-%m-%dT%H:%M:%S%.3fZ").to_string())
            .unwrap_or_default()
    }
    fn number(v: Option<i32>) -> String {
        v.map(|n| n.to_string()).unwrap_or_default()
    }
    let id = |u: &Uuid| u.hyphenated().to_string().to_uppercase();
    let mut rows: Vec<Vec<String>> = vec![
        ["type", "id", "start", "end", "goal_minutes", "eating_minutes", "timezone", "fast_id", "kind", "value", "unit", "intention",
            "reflection_or_text", "tags", "mood", "energy", "hunger", "external_id"]
            .iter()
            .map(|s| s.to_string())
            .collect(),
    ];
    let mut fasts: Vec<&FastRecord> = archive.fasts.iter().collect();
    fasts.sort_by(|a, b| a.start.cmp(&b.start));
    for f in fasts {
        rows.push(vec![
            "fast".into(), id(&f.id), date(f.start), f.end.map(date).unwrap_or_default(), number(f.goal_minutes),
            f.eating_minutes.to_string(), f.time_zone_id.clone(), "".into(), "".into(), "".into(), "".into(),
            f.intention.clone(), f.reflection.clone(), f.tags.join(";"), "".into(), "".into(), "".into(), "".into(),
        ]);
    }
    let mut journal: Vec<&JournalEntry> = archive.journal.iter().collect();
    journal.sort_by(|a, b| a.date.cmp(&b.date));
    for e in journal {
        rows.push(vec![
            "journal".into(), id(&e.id), date(e.date), "".into(), "".into(), "".into(), "".into(),
            e.fast_id.as_ref().map(id).unwrap_or_default(), "".into(), "".into(), "".into(), "".into(), e.text.clone(),
            e.tags.join(";"), number(e.mood), number(e.energy), number(e.hunger), "".into(),
        ]);
    }
    let mut metrics: Vec<&MetricEntry> = archive.metrics.iter().collect();
    metrics.sort_by(|a, b| a.date.cmp(&b.date));
    for m in metrics {
        rows.push(vec![
            "metric".into(), id(&m.id), date(m.date), "".into(), "".into(), "".into(), "".into(), "".into(),
            serde_json::to_value(m.kind).ok().and_then(|v| v.as_str().map(String::from)).unwrap_or_default(),
            swift_double(m.value), metric_canonical_unit(m.kind), "".into(), "".into(), "".into(), "".into(), "".into(),
            "".into(), m.external_id.clone().unwrap_or_default(),
        ]);
    }
    for p in &archive.plans {
        rows.push(vec![
            "plan".into(), id(&p.id), date(p.start), "".into(), number(p.goal_minutes), p.eating_minutes.to_string(),
            "".into(), "".into(), "".into(), "".into(), "".into(), p.intention.clone(), "".into(), "".into(), "".into(),
            "".into(), "".into(), "".into(),
        ]);
    }
    for d in &archive.rest_days {
        let mut row = vec!["rest".to_string(), id(&d.id), date(d.date)];
        row.extend(std::iter::repeat_n(String::new(), 9));
        row.push(d.note.clone());
        row.extend(std::iter::repeat_n(String::new(), 5));
        rows.push(row);
    }
    for p in &archive.presets {
        rows.push(vec![
            "preset".into(), id(&p.id), "".into(), "".into(), number(p.goal_minutes), p.eating_minutes.to_string(),
            "".into(), "".into(), "".into(), "".into(), "".into(), p.name.clone(), "".into(), "".into(), "".into(),
            "".into(), "".into(), "".into(),
        ]);
    }
    let mut out = rows.iter().map(|r| r.iter().map(|c| csv_cell(c)).collect::<Vec<_>>().join(",")).collect::<Vec<_>>().join("\r\n");
    out.push_str("\r\n");
    out
}

/// Swift's `String(Double)`: always a decimal point for whole numbers ("250.0").
fn swift_double(v: f64) -> String {
    format!("{v:?}")
}

fn csv_cell(raw: &str) -> String {
    // Spreadsheet formula injection protection. The JSON backup keeps the original exactly.
    let first = raw.trim().chars().next();
    let protected = if first.map(|c| "=+-@".contains(c)).unwrap_or(false) { format!("'{raw}") } else { raw.to_string() };
    format!("\"{}\"", protected.replace('"', "\"\""))
}
