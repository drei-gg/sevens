//! History (QRCore `History/`, SPEC §10): one saved scan or made code, the
//! `history.v1.json` file, search and the JSON/CSV exports.

use crate::contract::*;
use crate::json;
use crate::known::{self, KnownAddresses, entry_value};
use crate::text as t;
use crate::time;
use base64::Engine;
use serde_json::{Value, json};
use std::collections::HashSet;
use std::time::SystemTime;
use unicode_general_category::GeneralCategory as G;
use unicode_normalization::UnicodeNormalization;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum HistoryKind {
    Scanned,
    Made,
}

impl HistoryKind {
    pub fn raw(self) -> &'static str {
        match self {
            Self::Scanned => "scanned",
            Self::Made => "made",
        }
    }
    fn from_raw(s: &str) -> Option<Self> {
        match s {
            "scanned" => Some(Self::Scanned),
            "made" => Some(Self::Made),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PayloadEncoding {
    Text,
    Base64,
}

impl PayloadEncoding {
    pub fn raw(self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Base64 => "base64",
        }
    }
    fn from_raw(s: &str) -> Option<Self> {
        match s {
            "text" => Some(Self::Text),
            "base64" => Some(Self::Base64),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct HistoryItem {
    pub id: Uuid,
    pub kind: HistoryKind,
    /// Raw text, or base64 of the bytes for binary codes.
    pub payload: String,
    pub payload_encoding: PayloadEncoding,
    pub symbology: Symbology,
    pub type_id: String,
    pub level: Level,
    /// `SignalId` raw values (strings, so a newer file with unknown IDs still decodes).
    pub signal_ids: Vec<String>,
    pub date: SystemTime,
    pub note: Option<String>,
}

fn base64_engine() -> base64::engine::GeneralPurpose {
    base64::engine::GeneralPurpose::new(
        &base64::alphabet::STANDARD,
        base64::engine::GeneralPurposeConfig::new().with_decode_allow_trailing_bits(true),
    )
}

impl HistoryItem {
    /// From an analysis: text payloads as text, others as base64; non-info signal IDs of the
    /// analysis and its embedded analyses (de-duplicated, first seen first).
    pub fn from_analysis(analysis: &Analysis, kind: HistoryKind, date: SystemTime) -> Self {
        let code = &analysis.code;
        let mut seen = HashSet::new();
        let mut ids = Vec::new();
        for s in analysis.all_signals() {
            if s.severity() != Severity::Info && seen.insert(s.id) {
                ids.push(s.id.raw().to_string());
            }
        }
        let (payload, payload_encoding) = match &code.text {
            Some(text) => (text.clone(), PayloadEncoding::Text),
            None => (base64_engine().encode(&code.bytes), PayloadEncoding::Base64),
        };
        HistoryItem {
            id: Uuid::new_v4(),
            kind,
            payload,
            payload_encoding,
            symbology: code.symbology,
            type_id: analysis.payload.type_id().to_string(),
            level: analysis.level,
            signal_ids: ids,
            date,
            note: None,
        }
    }

    /// The `ScannedCode` this item was made from. Bad base64 gives empty bytes and no text.
    pub fn scanned_code(&self) -> ScannedCode {
        match self.payload_encoding {
            PayloadEncoding::Text => ScannedCode::text(&self.payload, self.symbology),
            PayloadEncoding::Base64 => match base64_engine().decode(&self.payload) {
                Ok(bytes) => ScannedCode { text: String::from_utf8(bytes.clone()).ok(), bytes, symbology: self.symbology },
                Err(_) => ScannedCode { text: None, bytes: vec![], symbology: self.symbology },
            },
        }
    }
}

#[uniffi::export]
pub fn history_item_from_analysis(analysis: Analysis, kind: HistoryKind, date: SystemTime) -> HistoryItem {
    HistoryItem::from_analysis(&analysis, kind, date)
}

#[uniffi::export]
pub fn history_item_scanned_code(item: HistoryItem) -> ScannedCode {
    item.scanned_code()
}

// MARK: file

pub const HISTORY_SCHEMA: i64 = 1;
pub const HISTORY_FILE_NAME: &str = "history.v1.json";

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryDecoded {
    Items { items: Vec<HistoryItem> },
    /// Leave the file untouched; show an empty history and say this version can't read it.
    UnknownSchema { schema: i64 },
    /// Not JSON or not our shape. Treated like UnknownSchema (never overwritten).
    Unreadable,
}

/// Foundation's `UUID(uuidString:)`: exactly the 36-character hyphenated form, any case.
fn parse_uuid(s: &str) -> Option<Uuid> {
    let b = s.as_bytes();
    if b.len() != 36 {
        return None;
    }
    for (i, &c) in b.iter().enumerate() {
        let hyphen = matches!(i, 8 | 13 | 18 | 23);
        if hyphen != (c == b'-') || (!hyphen && !c.is_ascii_hexdigit()) {
            return None;
        }
    }
    Uuid::parse_str(s).ok()
}

fn item_from(v: &Value) -> Option<HistoryItem> {
    let s = |k: &str| json::field(v, k).and_then(json::string);
    let signal_ids = json::field(v, "signalIDs")?.as_array()?.iter().map(json::string).collect::<Option<Vec<_>>>()?;
    Some(HistoryItem {
        id: parse_uuid(&s("id")?)?,
        kind: HistoryKind::from_raw(&s("kind")?)?,
        payload: s("payload")?,
        payload_encoding: PayloadEncoding::from_raw(&s("payloadEncoding")?)?,
        symbology: Symbology::from_raw(&s("symbology")?)?,
        type_id: s("typeID")?,
        level: Level::from_raw(json::int(json::field(v, "level")?)?)?,
        signal_ids,
        date: known::parse_date(json::field(v, "date")?)?,
        note: json::optional(v, "note", json::string).ok()?,
    })
}

fn item_value(i: &HistoryItem) -> Value {
    let mut m = json!({
        "id": i.id.hyphenated().to_string().to_uppercase(),
        "kind": i.kind.raw(),
        "payload": i.payload,
        "payloadEncoding": i.payload_encoding.raw(),
        "symbology": i.symbology.raw(),
        "typeID": i.type_id,
        "level": i.level.raw(),
        "signalIDs": i.signal_ids,
        "date": time::iso8601(i.date),
    });
    if let Some(n) = &i.note {
        m["note"] = Value::String(n.clone());
    }
    m
}

/// `{ "schema": 1, "items": [...] }`; one unreadable item is dropped, the rest kept.
pub fn decode_history(bytes: &[u8]) -> HistoryDecoded {
    let Ok(root) = serde_json::from_slice::<Value>(bytes) else { return HistoryDecoded::Unreadable };
    let Some(schema) = known::schema_of(&root) else { return HistoryDecoded::Unreadable };
    if schema != HISTORY_SCHEMA {
        return HistoryDecoded::UnknownSchema { schema };
    }
    let Some(items) = json::field(&root, "items").and_then(Value::as_array) else { return HistoryDecoded::Unreadable };
    HistoryDecoded::Items { items: items.iter().filter_map(item_from).collect() }
}

/// Byte for byte as the iOS app writes it: sorted keys, ISO 8601 dates in whole seconds.
pub fn encode_history(items: &[HistoryItem]) -> Vec<u8> {
    let items: Vec<Value> = items.iter().map(item_value).collect();
    json::to_bytes(&json!({ "schema": HISTORY_SCHEMA, "items": items }))
}

#[uniffi::export]
pub fn history_file_decode(bytes: Vec<u8>) -> HistoryDecoded {
    decode_history(&bytes)
}

#[uniffi::export]
pub fn history_file_encode(items: Vec<HistoryItem>) -> Vec<u8> {
    encode_history(&items)
}

/// Case- and diacritic-insensitive folding for search.
fn fold(s: &str) -> String {
    let stripped: String = s.nfd().filter(|&c| t::general_category(c) != G::NonspacingMark).collect();
    t::lower(&stripped)
}

/// Case- and diacritic-insensitive substring over payload, note and type ID; newest first.
pub fn search(items: &[HistoryItem], query: &str) -> Vec<HistoryItem> {
    let mut ordered = items.to_vec();
    ordered.sort_by(|a, b| b.date.cmp(&a.date)); // stable: equal dates keep input order
    let needle = t::trim_ws_nl(query);
    if needle.is_empty() {
        return ordered;
    }
    let n = fold(needle);
    ordered
        .into_iter()
        .filter(|i| {
            fold(&i.payload).contains(&n) || fold(&i.type_id).contains(&n) || i.note.as_deref().is_some_and(|x| fold(x).contains(&n))
        })
        .collect()
}

#[uniffi::export]
pub fn history_search(items: Vec<HistoryItem>, query: String) -> Vec<HistoryItem> {
    search(&items, &query)
}

// MARK: export

/// The file's own shape; with `known`, the same envelope plus a top-level `knownAddresses` array.
pub fn export_json(items: &[HistoryItem], known: Option<&KnownAddresses>) -> Vec<u8> {
    let Some(known) = known else { return encode_history(items) };
    let items: Vec<Value> = items.iter().map(item_value).collect();
    let entries: Vec<Value> = known.entries.iter().map(entry_value).collect();
    json::to_bytes(&json!({ "schema": HISTORY_SCHEMA, "items": items, "knownAddresses": entries }))
}

/// The addresses an export carries; empty for an export without them.
pub fn export_known_addresses(bytes: &[u8]) -> KnownAddresses {
    let Ok(root) = serde_json::from_slice::<Value>(bytes) else { return KnownAddresses::default() };
    if !root.is_object() {
        return KnownAddresses::default();
    }
    let entries = match root.get("knownAddresses") {
        None | Some(Value::Null) => vec![],
        Some(Value::Array(a)) => a.iter().filter_map(known::entry_from).collect(),
        Some(_) => return KnownAddresses::default(),
    };
    KnownAddresses::from_entries(&entries)
}

/// RFC 4180 (CRLF); a cell starting with `= + - @` gets a leading `'`.
pub fn export_csv(items: &[HistoryItem]) -> String {
    let mut rows = vec!["date,kind,type,level,payload,note,signals,symbology,id".to_string()];
    for i in items {
        let level = match i.level {
            Level::Normal => "normal",
            Level::Careful => "careful",
            Level::LikelyScam => "likelyScam",
        };
        let cells = [
            time::iso8601(i.date),
            i.kind.raw().to_string(),
            i.type_id.clone(),
            level.to_string(),
            i.payload.clone(),
            i.note.clone().unwrap_or_default(),
            i.signal_ids.join(" "),
            i.symbology.raw().to_string(),
            i.id.hyphenated().to_string().to_uppercase(),
        ];
        rows.push(cells.iter().map(|c| cell(c)).collect::<Vec<_>>().join(","));
    }
    rows.join("\r\n") + "\r\n"
}

fn cell(raw: &str) -> String {
    let mut text = raw.to_string();
    if text.starts_with(['=', '+', '-', '@']) {
        text = format!("'{text}");
    }
    if !text.contains([',', '"', '\r', '\n']) {
        return text;
    }
    format!("\"{}\"", text.replace('"', "\"\""))
}

#[uniffi::export]
pub fn history_export_json(items: Vec<HistoryItem>, known: Option<KnownAddresses>) -> Vec<u8> {
    export_json(&items, known.as_ref())
}

#[uniffi::export]
pub fn history_export_known_addresses(bytes: Vec<u8>) -> KnownAddresses {
    export_known_addresses(&bytes)
}

#[uniffi::export]
pub fn history_export_csv(items: Vec<HistoryItem>) -> String {
    export_csv(&items)
}
