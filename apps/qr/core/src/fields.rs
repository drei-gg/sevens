//! The flat `fields` view of every payload (QRCore `PayloadFields.swift`). These keys are what
//! fixtures' "fields" match against and what the CSV export writes. None values are omitted;
//! booleans are "true"/"false"; lists are joined with "\n"; dates are ISO 8601.

use crate::contract::*;
use crate::time;
use std::collections::HashMap;
use std::time::SystemTime;

struct F(HashMap<String, String>);

impl F {
    fn new() -> Self {
        F(HashMap::new())
    }
    fn s(&mut self, k: &str, v: &str) {
        self.0.insert(k.into(), v.into());
    }
    fn o(&mut self, k: &str, v: &Option<String>) {
        if let Some(v) = v {
            self.s(k, v);
        }
    }
    fn b(&mut self, k: &str, v: bool) {
        self.s(k, if v { "true" } else { "false" });
    }
    fn l(&mut self, k: &str, v: &[String]) {
        if !v.is_empty() {
            self.s(k, &v.join("\n"));
        }
    }
}

/// "2026-11-10" (all-day), "2026-11-10T09:00:00" (floating), "2026-11-10T01:00:00Z" (absolute).
pub(crate) fn event_date_text(date: SystemTime, all_day: bool, floating: bool) -> String {
    let (y, mo, d, h, mi, s) = time::gmt_fields(time::floor_seconds(date));
    let day = format!("{}-{}-{}", time::pad(y, 4), time::pad(mo, 2), time::pad(d, 2));
    if all_day {
        return day;
    }
    let t = format!("{}T{}:{}:{}", day, time::pad(h, 2), time::pad(mi, 2), time::pad(s, 2));
    if floating { t } else { t + "Z" }
}

pub fn link_fields(i: &LinkInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.s("original", &i.original);
    f.s("scheme", &i.scheme);
    f.o("userinfo", &i.userinfo);
    f.s("host", &i.host_unicode);
    f.s("hostASCII", &i.host_ascii);
    f.o("registrableDomain", &i.registrable_domain);
    f.o("publicSuffix", &i.public_suffix);
    f.b("suffixIsPrivate", i.suffix_is_private);
    f.o("port", &i.port.map(|p| p.to_string()));
    f.s("pathAndQuery", &i.path_and_query);
    f.b("isIPAddress", i.is_ip_address);
    f.s("normalized", &i.normalized);
    f.0
}

pub fn payment_fields(i: &PaymentInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.s("format", i.format.raw());
    f.o("payeeName", &i.payee_name);
    f.o("payeeAccount", &i.payee_account);
    f.o("city", &i.city);
    f.o("country", &i.country);
    f.o("amount", &i.amount);
    f.o("currency", &i.currency);
    f.o("network", &i.network);
    if let Some(d) = i.is_dynamic {
        f.b("isDynamic", d);
    }
    f.o("reference", &i.reference);
    f.o("note", &i.note);
    f.s("party", i.party.raw());
    f.s("checksum", i.checksum.raw());
    for (k, v) in &i.extra {
        f.s(&format!("extra.{k}"), v);
    }
    f.0
}

pub fn crypto_fields(i: &CryptoInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.o("scheme", &i.scheme);
    f.s("network", &i.network);
    f.s("address", &i.address);
    f.o("amount", &i.amount);
    f.o("label", &i.label);
    f.o("message", &i.message);
    f.o("chainID", &i.chain_id);
    f.b("isLightningInvoice", i.is_lightning_invoice);
    f.0
}

pub fn wifi_fields(i: &WiFiInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.s("ssid", &i.ssid);
    f.o("security", &i.security);
    f.o("password", &i.password);
    f.b("hidden", i.is_hidden);
    f.o("eapMethod", &i.eap_method);
    f.o("identity", &i.identity);
    f.0
}

pub fn contact_fields(i: &ContactInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.o("name", &i.name);
    f.o("givenName", &i.given_name);
    f.o("familyName", &i.family_name);
    f.o("organization", &i.organization);
    f.o("title", &i.title);
    f.l("phones", &i.phones);
    f.l("emails", &i.emails);
    f.l("urls", &i.urls);
    f.l("addresses", &i.addresses);
    f.o("note", &i.note);
    f.o("birthday", &i.birthday);
    f.0
}

pub fn event_fields(i: &EventInfo) -> HashMap<String, String> {
    let mut f = F::new();
    f.o("summary", &i.summary);
    f.o("start", &i.start.map(|d| event_date_text(d, i.is_all_day, i.is_floating)));
    f.o("end", &i.end.map(|d| event_date_text(d, i.is_all_day, i.is_floating)));
    f.b("allDay", i.is_all_day);
    f.b("floating", i.is_floating);
    f.o("timeZone", &i.time_zone_id);
    f.o("location", &i.location);
    f.o("description", &i.description);
    f.o("url", &i.url);
    f.0
}

/// Flat view, used by fixtures and CSV export.
pub fn fields(p: &Payload) -> HashMap<String, String> {
    match p {
        Payload::Link { info } => link_fields(info),
        Payload::AppLink { info } => {
            HashMap::from([("scheme".into(), info.scheme.clone()), ("original".into(), info.original.clone())])
        }
        Payload::Payment { info } => payment_fields(info),
        Payload::Crypto { info } => crypto_fields(info),
        Payload::Wifi { info } => wifi_fields(info),
        Payload::Contact { info } => contact_fields(info),
        Payload::Event { info } => event_fields(info),
        Payload::Phone { info } => HashMap::from([("number".into(), info.number.clone())]),
        Payload::Sms { info } => {
            let mut f = F::new();
            f.s("number", &info.number);
            f.o("body", &info.body);
            f.0
        }
        Payload::Email { info } => {
            let mut f = F::new();
            f.s("address", &info.address);
            f.o("subject", &info.subject);
            f.o("body", &info.body);
            f.0
        }
        Payload::Geo { info } => {
            let mut f = F::new();
            f.s("latitude", &info.latitude);
            f.s("longitude", &info.longitude);
            f.o("altitude", &info.altitude);
            f.o("query", &info.query);
            f.0
        }
        Payload::Text { info } => {
            let mut f = F::new();
            f.s("text", &info.text);
            f.b("truncated", info.is_truncated);
            f.0
        }
        Payload::Binary { byte_count } => HashMap::from([("byteCount".into(), byte_count.to_string())]),
    }
}

/// The flat `fields` view of a payload.
#[uniffi::export]
pub fn payload_fields(payload: Payload) -> HashMap<String, String> {
    fields(&payload)
}
