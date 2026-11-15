//! The classifier (which parser handles a scan, SPEC §3) and the analyzer (checks, known
//! addresses, embedded links and the verdict rule, SPEC §4.1).

use crate::checks::{self, CheckContext, embedded_links};
use crate::contract::*;
use crate::data::BundledData;
use crate::known::KnownAddresses;
use crate::payments;
use crate::structured;
use crate::text as t;
use crate::url::{self, WebAddress, url_text};
use std::sync::Arc;

pub const MAX_BYTES: usize = 8192;

pub struct Classified {
    pub payload: Payload,
    pub signals: Vec<Signal>,
}

fn res(payload: Payload, signals: Vec<Signal>) -> Classified {
    Classified { payload, signals }
}

/// SPEC §3: first match wins; a parser that returns None falls through to the next rule. Also runs
/// the checks that belong to the payload itself.
pub fn classify(ctx: &CheckContext, code: &ScannedCode) -> Classified {
    let data = &ctx.data;
    let binary = || res(Payload::Binary { byte_count: code.bytes.len() as i64 }, vec![]);
    let Some(raw) = code.text.as_deref() else { return binary() };
    let tt = t::trim_ws_nl(raw);
    if tt.is_empty() {
        return binary();
    }
    if raw.len() > MAX_BYTES {
        let cut = t::cut_utf8(raw, MAX_BYTES).to_string();
        let links = embedded_links::find(Some(&cut));
        return res(Payload::Text { info: TextInfo { text: cut, is_truncated: true, links } }, vec![]);
    }
    let head = t::lower(t::prefix(tt, 32));
    let hp = |p: &str| t::has_prefix(&head, p);
    let psl = &data.public_suffixes;

    if hp("wifi:")
        && let Some(w) = structured::wifi::parse(tt) {
            let s = checks::open_wifi(&w).into_iter().collect();
            return res(Payload::Wifi { info: w }, s);
        }
    if (hp("begin:vcard") || hp("mecard:"))
        && let Some(mut c) = structured::contact::parse(tt) {
            let urls: Vec<String> = c.urls.iter().map(|u| t::trim_ws(u).to_string()).filter(|u| !u.is_empty()).collect();
            c.links = embedded_links::merge(&[urls, embedded_links::find(c.note.as_deref())]);
            return res(Payload::Contact { info: c }, vec![]);
        }
    if (hp("begin:vevent") || (hp("begin:vcalendar") && t::contains(&t::lower(tt), "begin:vevent")))
        && let Some(mut e) = structured::event::parse(tt) {
            let url: Vec<String> = e.url.iter().filter(|u| !u.is_empty()).cloned().collect();
            e.links = embedded_links::merge(&[
                url,
                embedded_links::find(e.description.as_deref()),
                embedded_links::find(e.location.as_deref()),
            ]);
            return res(Payload::Event { info: e }, vec![]);
        }
    if hp("000201") {
        let n = t::count(tt);
        if n >= 8 && t::has_prefix(t::drop_first(tt, n - 8), "6304")
            && let Some(p) = payments::emv::parse(tt, &data.payment_networks.emv, &data.currencies) {
                return payment(p);
            }
    }
    let first_line: String = tt.chars().take_while(|&c| c != '\n' && c != '\r').collect();
    let first_line = t::trim_ws(&first_line);
    if t::str_eq(first_line, "BCD")
        && let Some(p) = payments::epc::parse(tt) {
            return payment(p);
        }
    if t::str_eq(first_line, "SPC")
        && let Some(p) = payments::swiss::parse(tt) {
            return payment(p);
        }
    if hp("upi://")
        && let Some(p) = payments::upi::parse(tt) {
            return payment(p);
        }
    let schemes = &data.payment_networks.crypto_schemes;
    if schemes.iter().any(|s| hp(&format!("{}:", t::lower(&s.scheme))))
        && let Some(c) = payments::crypto_uri::parse(tt, schemes) {
            return res(Payload::Crypto { info: c }, vec![]);
        }
    if hp("tel:")
        && let Some(p) = structured::phone::parse(tt) {
            return res(Payload::Phone { info: p }, vec![]);
        }
    if (hp("sms:") || hp("smsto:"))
        && let Some(mut s) = structured::sms::parse(tt) {
            s.links = embedded_links::find(s.body.as_deref());
            return res(Payload::Sms { info: s }, vec![]);
        }
    if (hp("mailto:") || hp("matmsg:"))
        && let Some(mut m) = structured::email::parse(tt) {
            m.links = embedded_links::find(m.body.as_deref());
            return res(Payload::Email { info: m }, vec![]);
        }
    if hp("geo:")
        && let Some(g) = structured::geo::parse(tt) {
            return res(Payload::Geo { info: g }, vec![]);
        }

    let scheme = WebAddress::scheme_of(raw);
    if matches!(scheme.as_deref(), Some("http" | "https"))
        && let Some(a) = WebAddress::parse(raw, psl) {
            let s = checks::link_signals(&a, ctx);
            return res(Payload::Link { info: a.link }, s);
        }
    if let Some(scheme) = scheme.as_deref().filter(|s| *s != "http" && *s != "https") {
        let cleaned = url_text::clean(raw);
        let rest = url::split_scheme(&cleaned).map(|(_, r)| r.to_string()).unwrap_or_default();
        if let Some(danger) = checks::dangerous_scheme(scheme) {
            let link = LinkInfo {
                original: raw.to_string(),
                scheme: scheme.to_string(),
                userinfo: None,
                host_ascii: String::new(),
                host_unicode: String::new(),
                registrable_domain: None,
                public_suffix: None,
                suffix_is_private: false,
                port: None,
                path_and_query: rest.clone(),
                is_ip_address: false,
                normalized: format!("{scheme}:{rest}"),
            };
            return res(Payload::Link { info: link }, vec![danger]);
        }
        // Text such as "Total: 5", "C:\temp" or a vCard/iCal block that didn't parse isn't an app
        // link: it needs a real scheme name and something right after the colon.
        let after_colon = tt.chars().skip_while(|&c| c != ':').nth(1);
        if t::count(scheme) >= 2 && scheme != "begin" && !rest.is_empty() && after_colon.is_some_and(|c| !c.is_whitespace()) {
            return res(
                Payload::AppLink { info: AppLinkInfo { scheme: scheme.to_string(), original: raw.to_string() } },
                vec![checks::unknown_scheme(scheme)],
            );
        }
    }
    if let Some(c) = payments::crypto_address::detect(tt) {
        return res(Payload::Crypto { info: c }, vec![]);
    }
    if let Some(a) = WebAddress::parse_schemeless(raw, psl) {
        let s = checks::link_signals(&a, ctx);
        return res(Payload::Link { info: a.link }, s);
    }
    let links = embedded_links::find(Some(raw));
    res(Payload::Text { info: TextInfo { text: raw.to_string(), is_truncated: false, links } }, vec![])
}

fn payment(p: PaymentInfo) -> Classified {
    let s = checks::payment_checksum(&p).into_iter().collect();
    res(Payload::Payment { info: p }, s)
}

/// SPEC §4.1: any strong → likelyScam; three or more warnings → likelyScam; one or two
/// warnings → careful; otherwise normal. Info signals don't count.
pub fn level_for(signals: &[Signal]) -> Level {
    if signals.iter().any(|s| s.severity() == Severity::Strong) {
        return Level::LikelyScam;
    }
    match signals.iter().filter(|s| s.severity() == Severity::Warning).count() {
        0 => Level::Normal,
        1 | 2 => Level::Careful,
        _ => Level::LikelyScam,
    }
}

/// The verdict rule on its own.
#[uniffi::export]
pub fn level_for_signals(signals: Vec<Signal>) -> Level {
    level_for(&signals)
}

/// Classifier, checks and verdict for one scan. Offline and synchronous.
#[derive(uniffi::Object)]
pub struct Analyzer {
    pub data: Arc<BundledData>,
    /// Addresses the person told us they know, applied as the final step.
    pub known: KnownAddresses,
    pub(crate) context: CheckContext,
}

impl Analyzer {
    pub fn with_known(data: Arc<BundledData>, known: KnownAddresses) -> Self {
        Analyzer { context: CheckContext::new(data.clone()), data, known }
    }

    pub fn context(&self) -> &CheckContext {
        &self.context
    }

    fn analyze_inner(&self, code: &ScannedCode, embedding: bool) -> Analysis {
        let result = classify(&self.context, code);
        let own = if self.known.is_empty() { result.signals } else { self.known.apply(&result.signals, &result.payload) };
        let signals = in_stable_order(&own);
        let embedded: Vec<Analysis> = if embedding {
            embedded_links_of(&result.payload)
                .iter()
                .map(|l| self.analyze_inner(&ScannedCode::text(l, code.symbology), false))
                .collect()
        } else {
            vec![]
        };
        let level = embedded.iter().map(|e| e.level).fold(level_for(&signals), Level::max);
        Analysis { code: code.clone(), payload: result.payload, signals, embedded, level }
    }
}

fn embedded_links_of(payload: &Payload) -> Vec<String> {
    match payload {
        Payload::Text { info } => info.links.clone(),
        Payload::Contact { info } => info.links.clone(),
        Payload::Event { info } => info.links.clone(),
        Payload::Sms { info } => info.links.clone(),
        Payload::Email { info } => info.links.clone(),
        _ => vec![],
    }
}

#[uniffi::export]
impl Analyzer {
    #[uniffi::constructor]
    pub fn new(data: Arc<BundledData>, known: KnownAddresses) -> Arc<Self> {
        Arc::new(Self::with_known(data, known))
    }

    /// Offline, synchronous. Embedded links are analysed once, one level deep.
    pub fn analyze(&self, code: ScannedCode) -> Analysis {
        self.analyze_inner(&code, true)
    }

    pub fn level(&self, signals: Vec<Signal>) -> Level {
        level_for(&signals)
    }
}
