//! Backups and the app's own file: the JSON archive, dormant merge-on-import and the CSV export.

use crate::model::*;
use crate::text::first_character;
use crate::time::{as_swift_date, uuid_string};
use serde::Serialize;
use std::time::SystemTime;

/// Swift `BackupCodec.maxBytes`: 32 MB.
pub const MAX_BACKUP_BYTES: u64 = 32 * 1_024 * 1_024;

#[uniffi::export]
pub fn backup_max_bytes() -> u64 {
    MAX_BACKUP_BYTES
}

/// Swift `BackupCodec.encode`: pretty-printed, keys sorted, dates as seconds since 2001. Like
/// Swift, encoding does not validate.
#[uniffi::export]
pub fn backup_encode(archive: &LoopArchive) -> Result<Vec<u8>, LoopError> {
    pretty(archive).map_err(|e| LoopError::Unreadable { detail: e.to_string() })
}

/// Swift `BackupCodec.decode`: size, then the full decode, then validation. A newer version is
/// refused with its own message, never guessed at.
#[uniffi::export]
pub fn backup_decode(data: &[u8]) -> Result<LoopArchive, LoopError> {
    if data.len() as u64 > MAX_BACKUP_BYTES {
        return Err(invalid("This backup exceeds 32 MB."));
    }
    let archive: LoopArchive = serde_json::from_slice(data).map_err(|e| LoopError::Unreadable { detail: e.to_string() })?;
    loop_archive_validate(&archive)?;
    Ok(archive)
}

/// What `LoopRepository.change` does between the edit and the write: validate, encode and refuse
/// a file over 32 MB. The atomic write itself, and publishing only after it succeeds, stay native.
#[uniffi::export]
pub fn store_encode(archive: &LoopArchive) -> Result<Vec<u8>, LoopError> {
    loop_archive_validate(archive)?;
    let bytes = backup_encode(archive)?;
    if bytes.len() as u64 > MAX_BACKUP_BYTES {
        return Err(invalid("Archive exceeds 32 MB. Export and remove older history before adding more."));
    }
    Ok(bytes)
}

/// Swift `BackupCodec.merge`. Imports are dormant: new routines arrive disabled, and receipts,
/// the pending mission and the defaults belong to the original device only. A history ID present
/// on both sides must match exactly, or nothing is imported.
#[uniffi::export]
pub fn backup_merge(incoming: &LoopArchive, current: &LoopArchive) -> Result<LoopArchive, LoopError> {
    loop_archive_validate(incoming)?;
    let mut result = current.clone();
    for routine in &incoming.routines {
        if current.routines.iter().any(|r| r.id == routine.id) {
            continue;
        }
        let mut copy = routine.clone();
        copy.enabled = false;
        result.routines.push(copy);
    }
    for event in &incoming.events {
        match current.events.iter().find(|e| e.id == event.id) {
            // Compared as Swift `Date`s (doubles), so a value made natively equals its JSON copy.
            Some(old) => {
                if !same_event(old, event) {
                    return Err(invalid("A history ID has conflicting contents. Nothing was imported."));
                }
            }
            None => result.events.push(event.clone()),
        }
    }
    loop_archive_validate(&result)?;
    Ok(result)
}

fn same_event(a: &LoopEvent, b: &LoopEvent) -> bool {
    let normal = |e: &LoopEvent| LoopEvent { date: as_swift_date(e.date), ..e.clone() };
    normal(a) == normal(b)
}

/// Swift `BackupCodec.csv`: the history, one row per event, UTC times without fractions, CRLF
/// between rows and none after the last. Cells starting with `= + - @`, a tab or a carriage return
/// get a leading apostrophe against spreadsheet formula injection.
#[uniffi::export]
pub fn backup_csv(archive: &LoopArchive) -> String {
    fn quote(s: &str) -> String {
        // Swift tests `s.first`, a grapheme cluster, so "\r\n" at the start is not protected.
        let protect = first_character(s).is_some_and(|c| ["=", "+", "-", "@", "\t", "\r"].contains(&c));
        let protected = if protect { format!("'{s}") } else { s.to_string() };
        format!("\"{}\"", protected.replace('"', "\"\""))
    }
    fn iso(t: SystemTime) -> String {
        // ISO8601DateFormatter's default: UTC, whole seconds (truncated), "Z".
        crate::time::timestamp(t)
            .and_then(|ts| jiff::Timestamp::from_second(ts.as_second() - if ts.subsec_nanosecond() < 0 { 1 } else { 0 }).ok())
            .map(|ts| ts.strftime("%Y-%m-%dT%H:%M:%SZ").to_string())
            .unwrap_or_default()
    }
    let rows = archive.events.iter().map(|e| {
        let kind = serde_json::to_value(e.kind).ok().and_then(|v| v.as_str().map(String::from)).unwrap_or_default();
        [uuid_string(&e.id), uuid_string(&e.routine_id), e.name.clone(), iso(e.date), kind, e.note.clone()]
            .iter()
            .map(|c| quote(c))
            .collect::<Vec<_>>()
            .join(",")
    });
    format!("id,routine_id,routine,time_utc,event,note\r\n{}", rows.collect::<Vec<_>>().join("\r\n"))
}

pub(crate) fn pretty<T: Serialize>(value: &T) -> serde_json::Result<Vec<u8>> {
    // Through `Value` so object keys come out sorted, like `.sortedKeys`. Every key here is ASCII
    // and no two differ only by case, so byte order equals Foundation's ordering.
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

