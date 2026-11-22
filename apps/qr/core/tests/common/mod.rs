//! Shared by the Swift-parity suites: the fixture files the Swift tests read (from the Swift
//! package's own `Tests/QRCoreTests/Fixtures`, not a copy) and small helpers.
#![allow(dead_code)]

use base64::Engine;
use qr_core::*;
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};
use std::time::SystemTime;

pub fn fixtures_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../Kit/Tests/QRCoreTests/Fixtures")
}

pub fn fixture_bytes(path: &str) -> Vec<u8> {
    std::fs::read(fixtures_dir().join(path)).unwrap_or_else(|e| panic!("{path}: {e}"))
}

pub fn fixture_text(path: &str) -> String {
    String::from_utf8(fixture_bytes(path)).unwrap()
}

#[derive(Debug, Clone, Deserialize)]
pub struct Expect {
    #[serde(rename = "type")]
    pub kind: String,
    pub level: i64,
    pub signals: Vec<String>,
    pub info: Option<Vec<String>>,
    pub fields: Option<HashMap<String, String>>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FixtureCase {
    pub id: String,
    pub spec: Option<String>,
    pub input: Option<String>,
    pub input_base64: Option<String>,
    pub symbology: Option<String>,
    pub note: Option<String>,
    pub known: Option<Vec<String>>,
    pub expect: Expect,
}

impl FixtureCase {
    pub fn known_addresses(&self) -> KnownAddresses {
        let mut k = KnownAddresses::default();
        for (i, d) in self.known.iter().flatten().enumerate() {
            k.add(d, at(i as f64));
        }
        k
    }

    pub fn scanned_code(&self) -> ScannedCode {
        let sym = self
            .symbology
            .as_deref()
            .and_then(Symbology::from_raw)
            .unwrap_or(Symbology::Qr);
        if let Some(input) = &self.input {
            return ScannedCode::text(input, sym);
        }
        let bytes = self
            .input_base64
            .as_deref()
            .and_then(|b| base64::engine::general_purpose::STANDARD.decode(b).ok())
            .unwrap_or_default();
        ScannedCode {
            text: String::from_utf8(bytes.clone()).ok(),
            bytes,
            symbology: sym,
        }
    }
}

pub fn load(name: &str) -> Vec<FixtureCase> {
    serde_json::from_slice(&fixture_bytes(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

/// Top-level `*.json` files, sorted.
pub fn file_names() -> Vec<String> {
    let mut names: Vec<String> = std::fs::read_dir(fixtures_dir())
        .unwrap()
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_file())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".json"))
        .collect();
    names.sort();
    names
}

pub fn all() -> Vec<FixtureCase> {
    file_names().iter().flat_map(|n| load(n)).collect()
}

/// Every fixture case's comparison against an analysis; empty when it matches.
pub fn mismatches(c: &FixtureCase, a: &Analysis) -> Vec<String> {
    let mut out = Vec::new();
    if a.payload.type_id() != c.expect.kind {
        out.push(format!("type {} ≠ {}", a.payload.type_id(), c.expect.kind));
    }
    if a.level.raw() != c.expect.level {
        out.push(format!("level {} ≠ {}", a.level.raw(), c.expect.level));
    }
    let all = a.all_signals();
    let got: HashSet<&str> = all
        .iter()
        .filter(|s| s.severity() != Severity::Info)
        .map(|s| s.id.raw())
        .collect();
    let want: HashSet<&str> = c.expect.signals.iter().map(String::as_str).collect();
    if got != want {
        let (mut g, mut w): (Vec<_>, Vec<_>) =
            (got.into_iter().collect(), want.into_iter().collect());
        g.sort();
        w.sort();
        out.push(format!("signals {g:?} ≠ {w:?}"));
    }
    if let Some(info) = &c.expect.info {
        let got: HashSet<&str> = all
            .iter()
            .filter(|s| s.severity() == Severity::Info)
            .map(|s| s.id.raw())
            .collect();
        let want: HashSet<&str> = info.iter().map(String::as_str).collect();
        if got != want {
            out.push(format!("info {got:?} ≠ {want:?}"));
        }
    }
    let f = fields(&a.payload);
    for (k, v) in c.expect.fields.iter().flatten() {
        if f.get(k) != Some(v) {
            out.push(format!("fields[{k}] {:?} ≠ {v:?}", f.get(k)));
        }
    }
    out
}

pub fn shared() -> Arc<BundledData> {
    BundledData::shared()
}

pub fn analyzer() -> &'static Analyzer {
    static A: OnceLock<Analyzer> = OnceLock::new();
    A.get_or_init(|| Analyzer::with_known(BundledData::shared(), KnownAddresses::default()))
}

pub fn analyze(text: &str) -> Analysis {
    analyzer().analyze(ScannedCode::text(text, Symbology::Qr))
}

pub fn at(seconds: f64) -> SystemTime {
    qr_core::time::from_seconds(seconds)
}

pub fn secs(t: SystemTime) -> f64 {
    qr_core::time::seconds(t)
}

/// `Date` from UTC calendar fields.
pub fn utc(y: i64, mo: i64, d: i64, h: i64, mi: i64, s: i64) -> SystemTime {
    at((qr_core::time::days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s) as f64)
}

pub fn ids(signals: &[Signal]) -> Vec<SignalId> {
    signals.iter().map(|s| s.id).collect()
}

pub fn arg<'a>(s: Option<&'a Signal>, key: &str) -> Option<&'a str> {
    s?.args.get(key).map(String::as_str)
}
