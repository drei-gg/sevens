//! Addresses the person told us they know (SPEC §3, §4.4) and their file
//! (QRCore `Known/`): `{ "schema": 1, "entries": [{ "added", "domain" }] }`.

use crate::contract::*;
use crate::json;
use crate::text as t;
use crate::time;
use serde_json::{Value, json};
use std::time::SystemTime;

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct KnownAddressEntry {
    /// A registrable domain, Unicode form, lowercase.
    pub domain: String,
    pub added: SystemTime,
}

/// Registrable domains the person told us they know. Newest first.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct KnownAddresses {
    pub entries: Vec<KnownAddressEntry>,
}

/// The most entries kept; the oldest fall off.
pub const KNOWN_MAX_ENTRIES: usize = 1_000;

/// The signals that come only from a bundled list. Only these can be overridden.
pub const LIST_BASED: &[SignalId] = &[
    SignalId::BrandOtherEnding,
    SignalId::BrandInDomain,
    SignalId::NearBrand,
    SignalId::LookalikeBrand,
    SignalId::RiskyTld,
    SignalId::Shortener,
];

impl KnownAddresses {
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// Lowercase, trimmed, no trailing dot: the form `LinkInfo.registrable_domain` has.
    pub fn normalized(domain: &str) -> String {
        let mut text = t::lower(t::trim_ws_nl(domain));
        while t::has_suffix(&text, ".") {
            text = t::drop_last(&text, 1).to_string();
        }
        text
    }

    pub fn contains(&self, domain: &str) -> bool {
        let key = Self::normalized(domain);
        !key.is_empty() && self.entries.iter().any(|e| t::str_eq(&e.domain, &key))
    }

    /// Adds (or moves to the front) one domain.
    pub fn add(&mut self, domain: &str, at: SystemTime) {
        let key = Self::normalized(domain);
        if key.is_empty() {
            return;
        }
        self.entries.retain(|e| !t::str_eq(&e.domain, &key));
        self.entries.insert(0, KnownAddressEntry { domain: key, added: at });
        self.entries.truncate(KNOWN_MAX_ENTRIES);
    }

    pub fn remove(&mut self, domain: &str) {
        let key = Self::normalized(domain);
        self.entries.retain(|e| !t::str_eq(&e.domain, &key));
    }

    /// The registrable domain to offer "I know this address" for: only for a link whose non-info
    /// signals are all list-based (at least one), not already known.
    pub fn offer(&self, analysis: &Analysis) -> Option<String> {
        let Payload::Link { info } = &analysis.payload else { return None };
        if info.is_ip_address {
            return None;
        }
        let domain = Self::normalized(info.registrable_domain.as_deref()?);
        if domain.is_empty() || self.contains(&domain) {
            return None;
        }
        let own: Vec<&Signal> = analysis.signals.iter().filter(|s| s.severity() != Severity::Info).collect();
        if own.is_empty() || !own.iter().all(|s| LIST_BASED.contains(&s.id)) {
            return None;
        }
        Some(domain)
    }

    /// The analyzer's final step: when the payload is a link whose registrable domain is known,
    /// drop the list-based signals and add `knownByYou`. Anything else comes back unchanged.
    pub fn apply(&self, signals: &[Signal], payload: &Payload) -> Vec<Signal> {
        let Payload::Link { info } = payload else { return signals.to_vec() };
        let Some(domain) = info.registrable_domain.as_deref().filter(|d| !info.is_ip_address && self.contains(d)) else {
            return signals.to_vec();
        };
        let mut kept: Vec<Signal> =
            signals.iter().filter(|s| !LIST_BASED.contains(&s.id) && s.id != SignalId::KnownByYou).cloned().collect();
        kept.push(Signal::with(SignalId::KnownByYou, &[("domain", &Self::normalized(domain))]));
        kept
    }

    /// Rebuilds a list from stored entries: normalized, no duplicates, newest first.
    pub fn from_entries(entries: &[KnownAddressEntry]) -> Self {
        let mut sorted = entries.to_vec();
        sorted.sort_by_key(|a| a.added);
        let mut result = KnownAddresses::default();
        for e in sorted {
            result.add(&e.domain, e.added);
        }
        result
    }
}

#[uniffi::export]
pub fn known_addresses_contains(known: KnownAddresses, domain: String) -> bool {
    known.contains(&domain)
}

#[uniffi::export]
pub fn known_addresses_add(known: KnownAddresses, domain: String, at: SystemTime) -> KnownAddresses {
    let mut k = known;
    k.add(&domain, at);
    k
}

#[uniffi::export]
pub fn known_addresses_remove(known: KnownAddresses, domain: String) -> KnownAddresses {
    let mut k = known;
    k.remove(&domain);
    k
}

#[uniffi::export]
pub fn known_addresses_offer(known: KnownAddresses, analysis: Analysis) -> Option<String> {
    known.offer(&analysis)
}

#[uniffi::export]
pub fn known_addresses_apply(known: KnownAddresses, signals: Vec<Signal>, payload: Payload) -> Vec<Signal> {
    known.apply(&signals, &payload)
}

// MARK: file

pub const KNOWN_SCHEMA: i64 = 1;
pub const KNOWN_FILE_NAME: &str = "known.v1.json";

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum KnownAddressesDecoded {
    Addresses { known: KnownAddresses },
    /// Leave the file untouched; this version can't read it.
    UnknownSchema { schema: i64 },
    Unreadable,
}

/// `HistoryDates.parse`: whole seconds, or fractional seconds.
pub(crate) fn parse_date(v: &Value) -> Option<SystemTime> {
    let s = v.as_str()?;
    time::parse_internet_date(s, false).or_else(|| time::parse_internet_date(s, true))
}

pub(crate) fn entry_from(v: &Value) -> Option<KnownAddressEntry> {
    let domain = json::string(json::field(v, "domain")?)?;
    let added = parse_date(json::field(v, "added")?)?;
    Some(KnownAddressEntry { domain, added })
}

pub(crate) fn entry_value(e: &KnownAddressEntry) -> Value {
    json!({ "added": time::iso8601(e.added), "domain": e.domain })
}

pub(crate) fn schema_of(root: &Value) -> Option<i64> {
    json::int(json::field(root, "schema")?)
}

pub fn decode_known_file(bytes: &[u8]) -> KnownAddressesDecoded {
    let Ok(root) = serde_json::from_slice::<Value>(bytes) else { return KnownAddressesDecoded::Unreadable };
    let Some(schema) = schema_of(&root) else { return KnownAddressesDecoded::Unreadable };
    if schema != KNOWN_SCHEMA {
        return KnownAddressesDecoded::UnknownSchema { schema };
    }
    let Some(entries) = json::field(&root, "entries").and_then(Value::as_array) else {
        return KnownAddressesDecoded::Unreadable;
    };
    let entries: Vec<KnownAddressEntry> = entries.iter().filter_map(entry_from).collect();
    KnownAddressesDecoded::Addresses { known: KnownAddresses::from_entries(&entries) }
}

pub fn encode_known_file(known: &KnownAddresses) -> Vec<u8> {
    let entries: Vec<Value> = known.entries.iter().map(entry_value).collect();
    json::to_bytes(&json!({ "schema": KNOWN_SCHEMA, "entries": entries }))
}

/// Reads `known.v1.json`.
#[uniffi::export]
pub fn known_addresses_file_decode(bytes: Vec<u8>) -> KnownAddressesDecoded {
    decode_known_file(&bytes)
}

/// Writes `known.v1.json`, byte for byte as the iOS app does.
#[uniffi::export]
pub fn known_addresses_file_encode(known: KnownAddresses) -> Vec<u8> {
    encode_known_file(&known)
}
