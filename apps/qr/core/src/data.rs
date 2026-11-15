//! The bundled data files and the downloadable lists bundle (QRCore `BundledData.swift`,
//! `Contract/DataModels.swift`, `Contract/ListsModels.swift`, `Lists/`). The files are read from
//! the Swift package's own `Data/` folder at build time, so there is one copy of each.

use crate::peek::RdapBootstrap;
use crate::text as t;
use crate::url::{PublicSuffixList, PublicSuffixMatch};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, RwLock};

// MARK: shapes

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct Brand {
    pub name: String,
    /// Lowercase tokens matched against domain labels and page titles.
    pub keywords: Vec<String>,
    /// The brand's own registrable domains, lowercase, Unicode form.
    pub domains: Vec<String>,
    pub category: String,
    pub region: String,
    /// Ordinary words (apple, chase, target…): fire only next to a bait word.
    pub ambiguous: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct RiskyTld {
    /// Without the dot, lowercase, ASCII (xn-- form for IDN endings).
    pub tld: String,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct EmvNetwork {
    /// As it appears in sub-tag 00 of tags 26–51 (compared case-insensitively).
    pub id: String,
    pub name: String,
    pub country: String,
    /// "person", "business" or "unknown".
    pub party: String,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct CryptoScheme {
    /// Lowercase URI scheme without the colon ("bitcoin").
    pub scheme: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct PaymentNetworks {
    pub emv: Vec<EmvNetwork>,
    pub crypto_schemes: Vec<CryptoScheme>,
}

/// One tracking tag Make offers to remove (`tracking-params.json`).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct TrackingParam {
    /// Lowercase query-parameter name. A trailing `*` means "this prefix" (`utm_*`).
    pub name: String,
    pub source: String,
}

impl TrackingParam {
    /// Case-insensitive; `utm_*` matches `utm_source` but not `utm` or `xutm_source`.
    pub fn matches(&self, key: &str) -> bool {
        let key = t::lower(key);
        if t::has_suffix(&self.name, "*") {
            let prefix = t::drop_last(&self.name, 1);
            return !prefix.is_empty() && t::has_prefix(&key, prefix) && t::count(&key) > t::count(prefix);
        }
        t::str_eq(&key, &self.name)
    }
}

#[uniffi::export]
pub fn tracking_param_matches(param: TrackingParam, key: String) -> bool {
    param.matches(&key)
}

/// `lists-version.json`, and the header of a downloaded bundle.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct ListsVersion {
    pub schema: i64,
    /// yyyymmdd × 100 + n, strictly increasing.
    pub version: i64,
    /// ISO 8601 date the lists were built.
    pub built: String,
}

impl ListsVersion {
    fn empty() -> Self {
        ListsVersion { schema: CURRENT_SCHEMA, version: 0, built: String::new() }
    }
}

/// IANA's RDAP bootstrap file, as carried inside a bundle.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RdapFile {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub publication: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    /// [[endings], [service URLs]] pairs.
    pub services: Vec<Vec<Vec<String>>>,
}

/// A JSON object of strings, in the order written (the publishing script keeps file order).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct OrderedStrings(pub Vec<(String, String)>);

impl OrderedStrings {
    pub fn get(&self, key: &str) -> Option<&str> {
        self.0.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }
    pub fn set(&mut self, key: &str, value: &str) {
        match self.0.iter_mut().find(|(k, _)| k == key) {
            Some(e) => e.1 = value.to_string(),
            None => self.0.push((key.to_string(), value.to_string())),
        }
    }
    pub fn to_map(&self) -> HashMap<String, String> {
        self.0.iter().cloned().collect()
    }
}

impl Serialize for OrderedStrings {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut m = s.serialize_map(Some(self.0.len()))?;
        for (k, v) in &self.0 {
            m.serialize_entry(k, v)?;
        }
        m.end()
    }
}

impl<'de> Deserialize<'de> for OrderedStrings {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> serde::de::Visitor<'de> for V {
            type Value = OrderedStrings;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("an object of strings")
            }
            fn visit_map<A: serde::de::MapAccess<'de>>(self, mut a: A) -> Result<OrderedStrings, A::Error> {
                let mut out = OrderedStrings::default();
                while let Some((k, v)) = a.next_entry::<String, String>()? {
                    out.set(&k, &v);
                }
                Ok(out)
            }
        }
        d.deserialize_map(V)
    }
}

/// The files a bundle replaces. Currencies don't age and aren't carried.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListsFiles {
    pub brands: Vec<Brand>,
    pub shorteners: Vec<String>,
    #[serde(rename = "riskyTLDs")]
    pub risky_tlds: Vec<RiskyTld>,
    pub bait_words: Vec<String>,
    pub payment_networks: PaymentNetworks,
    pub confusables: OrderedStrings,
    /// The text of `public_suffix_list.dat`.
    pub public_suffix_list: String,
    pub rdap: RdapFile,
    /// Optional so a bundle from before `tracking-params.json` existed still decodes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tracking_params: Option<Vec<TrackingParam>>,
}

/// The only schema this app version accepts.
pub const CURRENT_SCHEMA: i64 = 1;
/// Larger downloads are refused before decoding.
pub const LISTS_MAX_BYTES: usize = 2 * 1024 * 1024;

/// `lists.json`: `{ "schema", "version", "built", "files" }`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ListsBundle {
    pub schema: i64,
    pub version: i64,
    pub built: String,
    pub files: ListsFiles,
}

impl ListsBundle {
    pub fn header(&self) -> ListsVersion {
        ListsVersion { schema: self.schema, version: self.version, built: self.built.clone() }
    }

    /// Reads a `lists.json`.
    pub fn decode(bytes: &[u8]) -> Option<ListsBundle> {
        serde_json::from_slice(bytes).ok()
    }

    /// Compact JSON in field order, non-ASCII as UTF-8: the bytes `scripts/refresh-lists` writes.
    pub fn encode(&self) -> Vec<u8> {
        serde_json::to_vec(self).unwrap_or_default()
    }
}

/// Why a downloaded bundle was not used.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum ListsBundleError {
    #[error("too large")]
    TooLarge,
    #[error("bad signature")]
    BadSignature,
    #[error("unreadable")]
    Unreadable,
    #[error("unknown schema {schema}")]
    UnknownSchema { schema: i64 },
    /// Not newer than what is installed (no rollback).
    #[error("not newer: installed {installed}, offered {offered}")]
    NotNewer { installed: i64, offered: i64 },
    /// The named file broke a shape rule.
    #[error("bad shape: {file}")]
    BadShape { file: String },
}

// MARK: BundledData

/// Everything in `Data/`, parsed once. Immutable after load.
#[derive(Debug, Clone, uniffi::Object)]
pub struct BundledData {
    pub public_suffixes: PublicSuffixList,
    pub brands: Vec<Brand>,
    pub shorteners: HashSet<String>,
    pub risky_tlds: Vec<RiskyTld>,
    /// Single characters or short sequences → their Latin look-alike.
    pub confusables: HashMap<String, String>,
    pub bait_words: Vec<String>,
    pub payment_networks: PaymentNetworks,
    /// ISO 4217 numeric → alpha.
    pub currencies: HashMap<String, String>,
    pub rdap: RdapBootstrap,
    /// Tracking tags Make offers to remove.
    pub tracking_params: Vec<TrackingParam>,
    /// When these lists were built.
    pub lists: ListsVersion,
}

macro_rules! data_file {
    ($name:literal) => {
        include_bytes!(concat!("../../Kit/Sources/QRCore/Data/", $name)).as_slice()
    };
}

/// One file of the Swift package's `Data/` folder, by name.
pub fn resource(name: &str) -> Option<&'static [u8]> {
    Some(match name {
        "brands.json" => data_file!("brands.json"),
        "shorteners.json" => data_file!("shorteners.json"),
        "risky-tlds.json" => data_file!("risky-tlds.json"),
        "confusables.json" => data_file!("confusables.json"),
        "bait-words.json" => data_file!("bait-words.json"),
        "payment-networks.json" => data_file!("payment-networks.json"),
        "currencies.json" => data_file!("currencies.json"),
        "rdap-dns.json" => data_file!("rdap-dns.json"),
        "tracking-params.json" => data_file!("tracking-params.json"),
        "lists-version.json" => data_file!("lists-version.json"),
        "public_suffix_list.dat" => data_file!("public_suffix_list.dat"),
        _ => return None,
    })
}

fn json<T: for<'de> Deserialize<'de>>(name: &str) -> Option<T> {
    serde_json::from_slice(resource(name)?).ok()
}

static SHARED: RwLock<Option<Arc<BundledData>>> = RwLock::new(None);

impl BundledData {
    /// Data with only a suffix list; everything else empty (tests inject the rest).
    pub fn new(public_suffixes: PublicSuffixList) -> Self {
        BundledData {
            public_suffixes,
            brands: vec![],
            shorteners: HashSet::new(),
            risky_tlds: vec![],
            confusables: HashMap::new(),
            bait_words: vec![],
            payment_networks: PaymentNetworks::default(),
            currencies: HashMap::new(),
            rdap: RdapBootstrap::new(b""),
            tracking_params: vec![],
            lists: ListsVersion::empty(),
        }
    }

    /// Only what shipped with the app.
    pub fn load() -> BundledData {
        let psl = resource("public_suffix_list.dat").and_then(|b| std::str::from_utf8(b).ok()).unwrap_or("");
        BundledData {
            public_suffixes: PublicSuffixList::new(psl),
            brands: json("brands.json").unwrap_or_default(),
            shorteners: json::<Vec<String>>("shorteners.json").unwrap_or_default().into_iter().collect(),
            risky_tlds: json("risky-tlds.json").unwrap_or_default(),
            confusables: json("confusables.json").unwrap_or_default(),
            bait_words: json("bait-words.json").unwrap_or_default(),
            payment_networks: json("payment-networks.json").unwrap_or_default(),
            currencies: json("currencies.json").unwrap_or_default(),
            rdap: RdapBootstrap::new(resource("rdap-dns.json").unwrap_or(b"")),
            tracking_params: json("tracking-params.json").unwrap_or_default(),
            lists: json("lists-version.json").unwrap_or_else(ListsVersion::empty),
        }
    }

    /// Data built from a downloaded bundle. Currencies come from `base`, and so do tracking tags
    /// when the bundle has none.
    pub fn from_bundle(bundle: &ListsBundle, base: &BundledData) -> BundledData {
        let f = &bundle.files;
        BundledData {
            public_suffixes: PublicSuffixList::new(&f.public_suffix_list),
            brands: f.brands.clone(),
            shorteners: f.shorteners.iter().cloned().collect(),
            risky_tlds: f.risky_tlds.clone(),
            confusables: f.confusables.to_map(),
            bait_words: f.bait_words.clone(),
            payment_networks: f.payment_networks.clone(),
            currencies: base.currencies.clone(),
            rdap: RdapBootstrap::from_file(&f.rdap),
            tracking_params: f.tracking_params.clone().unwrap_or_else(|| base.tracking_params.clone()),
            lists: bundle.header(),
        }
    }

    /// Whichever is newer of the bundled lists and a saved download. A missing, unreadable,
    /// invalid or older download gives the bundled data.
    pub fn load_downloaded(downloaded: Option<&[u8]>) -> BundledData {
        let bundled = Self::load();
        let Some(bytes) = downloaded else { return bundled };
        if bytes.len() > LISTS_MAX_BYTES {
            return bundled;
        }
        let Some(bundle) = ListsBundle::decode(bytes) else { return bundled };
        if bundle.version <= bundled.lists.version || validate(&bundle).is_err() {
            return bundled;
        }
        Self::from_bundle(&bundle, &bundled)
    }

    /// The lists in use: loaded on first use; `reload` replaces them.
    pub fn shared() -> Arc<BundledData> {
        if let Some(d) = SHARED.read().ok().and_then(|g| g.clone()) {
            return d;
        }
        let mut g = SHARED.write().unwrap_or_else(|e| e.into_inner());
        if let Some(d) = g.clone() {
            return d;
        }
        let d = Arc::new(Self::load());
        *g = Some(d.clone());
        d
    }

    /// Swaps in the bundled lists or a newer download, whichever has the higher version.
    pub fn reload(downloaded: Option<&[u8]>) {
        let d = Arc::new(Self::load_downloaded(downloaded));
        *SHARED.write().unwrap_or_else(|e| e.into_inner()) = Some(d);
    }

    /// The version of the lists that shipped with the app.
    pub fn bundled_lists_version() -> ListsVersion {
        json("lists-version.json").unwrap_or_else(ListsVersion::empty)
    }
}

#[uniffi::export]
impl BundledData {
    /// The lists version in use.
    pub fn lists_version(&self) -> ListsVersion {
        self.lists.clone()
    }
    pub fn brand_list(&self) -> Vec<Brand> {
        self.brands.clone()
    }
    pub fn tracking_param_list(&self) -> Vec<TrackingParam> {
        self.tracking_params.clone()
    }
    pub fn payment_network_list(&self) -> PaymentNetworks {
        self.payment_networks.clone()
    }
    pub fn currency_table(&self) -> HashMap<String, String> {
        self.currencies.clone()
    }
    /// The Public Suffix List's answer for a lowercased xn-- host.
    pub fn public_suffix_match(&self, host_ascii: String) -> PublicSuffixMatch {
        self.public_suffixes.match_host(&host_ascii)
    }
    /// RDAP base URL (ending in "/") for a top-level label.
    pub fn rdap_base_url(&self, tld: String) -> Option<String> {
        self.rdap.base_url(&tld)
    }
    /// `<base>domain/<name>` for an ASCII registrable domain.
    pub fn rdap_query_url(&self, ascii_domain: String) -> Option<String> {
        self.rdap.query_url(&ascii_domain)
    }
}

/// The shared lists (bundled, or a newer accepted download).
#[uniffi::export]
pub fn bundled_data_shared() -> Arc<BundledData> {
    BundledData::shared()
}

/// Swaps the shared lists: call with the accepted download's bytes, or None after a reset.
#[uniffi::export]
pub fn bundled_data_reload(downloaded: Option<Vec<u8>>) {
    BundledData::reload(downloaded.as_deref())
}

/// A fresh load: bundled, or the download when it is valid and newer.
#[uniffi::export]
pub fn bundled_data_load(downloaded: Option<Vec<u8>>) -> Arc<BundledData> {
    Arc::new(BundledData::load_downloaded(downloaded.as_deref()))
}

#[uniffi::export]
pub fn bundled_lists_version() -> ListsVersion {
    BundledData::bundled_lists_version()
}

/// Every acceptance rule of a downloaded `lists.json` except the network and the signature (which
/// the platform checks first, over the exact bytes): size, format, schema, no rollback, shape.
#[uniffi::export]
pub fn lists_bundle_check(bytes: Vec<u8>, installed_version: i64) -> Result<ListsVersion, ListsBundleError> {
    if bytes.len() > LISTS_MAX_BYTES {
        return Err(ListsBundleError::TooLarge);
    }
    let header: ListsVersion = serde_json::from_slice(&bytes).map_err(|_| ListsBundleError::Unreadable)?;
    if header.schema != CURRENT_SCHEMA {
        return Err(ListsBundleError::UnknownSchema { schema: header.schema });
    }
    let bundle = ListsBundle::decode(&bytes).ok_or(ListsBundleError::Unreadable)?;
    if bundle.version <= installed_version {
        return Err(ListsBundleError::NotNewer { installed: installed_version, offered: bundle.version });
    }
    validate(&bundle)?;
    Ok(bundle.header())
}

// MARK: validation (QRCore `ListsValidation.swift`)

fn ascii(s: &str) -> bool {
    s.is_ascii()
}

fn https_source(s: &str) -> bool {
    t::has_prefix(s, "https://") && t::count(s) > 12
}

fn is_lower(s: &str) -> bool {
    t::str_eq(s, &t::lower(s))
}

pub(crate) fn is_iso_date(text: &str) -> bool {
    let parts = t::split_ch(text, '-', false);
    if parts.len() != 3 || t::count(parts[0]) != 4 || t::count(parts[1]) != 2 || t::count(parts[2]) != 2 {
        return false;
    }
    let (Some(y), Some(m), Some(d)) = (t::parse_int(parts[0]), t::parse_int(parts[1]), t::parse_int(parts[2])) else {
        return false;
    };
    y >= 2000 && (1..=12).contains(&m) && (1..=31).contains(&d) && text.bytes().all(|b| b == b'-' || b.is_ascii_digit())
}

fn valid_brands(list: &[Brand], bait: &HashSet<&str>) -> bool {
    if !(120..=200).contains(&list.len()) {
        return false;
    }
    let mut names = HashSet::new();
    let mut keywords = HashSet::new();
    for b in list {
        if b.name.is_empty()
            || !names.insert(t::lower(&b.name))
            || b.domains.is_empty()
            || b.category.is_empty()
            || !is_lower(&b.category)
            || b.region.is_empty()
            || !is_lower(&b.region)
        {
            return false;
        }
        for d in &b.domains {
            if !is_lower(d) || !t::contains(d, ".") || t::contains(d, "/") || t::contains(d, " ") {
                return false;
            }
        }
        for w in &b.keywords {
            if !is_lower(w) || t::count(w) < 3 || !ascii(w) || bait.contains(w.as_str()) || !keywords.insert(w.clone()) {
                return false;
            }
        }
    }
    true
}

fn valid_shorteners(list: &[String]) -> bool {
    list.len() >= 60
        && list.iter().collect::<HashSet<_>>().len() == list.len()
        && list.iter().all(|s| is_lower(s) && t::contains(s, ".") && !t::contains(s, "/"))
}

fn valid_risky(list: &[RiskyTld]) -> bool {
    if !(15..=40).contains(&list.len()) || list.iter().map(|r| &r.tld).collect::<HashSet<_>>().len() != list.len() {
        return false;
    }
    list.iter().all(|r| !r.tld.is_empty() && is_lower(&r.tld) && !t::contains(&r.tld, ".") && ascii(&r.tld) && https_source(&r.source))
}

fn valid_bait(list: &[String]) -> bool {
    let set: HashSet<&str> = list.iter().map(String::as_str).collect();
    (30..=60).contains(&list.len())
        && set.len() == list.len()
        && list.iter().all(|w| is_lower(w) && !w.is_empty() && ascii(w))
        && ["login", "verify", "secure", "account"].iter().all(|w| set.contains(w))
}

fn valid_networks(n: &PaymentNetworks) -> bool {
    if n.emv.is_empty() || n.emv.iter().map(|e| t::lower(&e.id)).collect::<HashSet<_>>().len() != n.emv.len() {
        return false;
    }
    let emv_ok = n.emv.iter().all(|e| {
        !e.id.is_empty()
            && !e.name.is_empty()
            && t::count(&e.country) == 2
            && t::str_eq(&e.country, &t::upper(&e.country))
            && ["person", "business", "unknown"].contains(&e.party.as_str())
            && https_source(&e.source)
    });
    let schemes: HashSet<&str> = n.crypto_schemes.iter().map(|s| s.scheme.as_str()).collect();
    emv_ok
        && schemes.len() == n.crypto_schemes.len()
        && n.crypto_schemes.iter().all(|s| is_lower(&s.scheme) && !t::contains(&s.scheme, ":") && !s.name.is_empty())
        && ["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"].iter().all(|s| schemes.contains(s))
}

fn valid_confusables(map: &OrderedStrings) -> bool {
    if map.0.len() <= 40 {
        return false;
    }
    for (k, v) in &map.0 {
        if k.is_empty() || !is_lower(k) || v.is_empty() || !is_lower(v) || !ascii(v) {
            return false;
        }
    }
    map.get("0") == Some("o")
        && map.get("1") == Some("l")
        && map.get("i") == Some("l")
        && map.get("rn") == Some("m")
        && map.get("vv") == Some("w")
        && map.get("\u{0430}") == Some("a")
        && map.get("\u{03BF}") == Some("o")
        && map.get("\u{FF41}") == Some("a")
}

fn valid_psl(text: &str) -> bool {
    if !t::contains(text, "===BEGIN ICANN DOMAINS===") || !t::contains(text, "===END ICANN DOMAINS===") {
        return false;
    }
    let m = PublicSuffixList::new(text).match_host("example.com");
    m.public_suffix == "com" && m.registrable_domain.as_deref() == Some("example.com") && m.matched_explicit_rule
}

fn valid_rdap(file: &RdapFile) -> bool {
    if file.services.len() < 50 {
        return false;
    }
    for s in &file.services {
        if s.len() != 2
            || s[0].is_empty()
            || s[1].is_empty()
            || !s[0].iter().all(|e| !e.is_empty())
            || !s[1].iter().all(|u| {
                let l = t::lower(u);
                t::has_prefix(&l, "https://") || t::has_prefix(&l, "http://")
            })
        {
            return false;
        }
    }
    RdapBootstrap::from_file(file).base_url("com").is_some()
}

fn valid_tracking(list: &[TrackingParam]) -> bool {
    if list.iter().map(|p| &p.name).collect::<HashSet<_>>().len() != list.len() {
        return false;
    }
    list.iter().all(|p| {
        let body = if t::has_suffix(&p.name, "*") { t::drop_last(&p.name, 1) } else { &p.name };
        !body.is_empty() && body.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') && https_source(&p.source)
    })
}

/// The shape rules for every file in the bundle, plus the schema.
pub fn validate(bundle: &ListsBundle) -> Result<(), ListsBundleError> {
    if bundle.schema != CURRENT_SCHEMA {
        return Err(ListsBundleError::UnknownSchema { schema: bundle.schema });
    }
    let bad = |f: &str| Err(ListsBundleError::BadShape { file: f.to_string() });
    if bundle.version <= 0 {
        return bad("version");
    }
    if !is_iso_date(&bundle.built) {
        return bad("built");
    }
    let f = &bundle.files;
    let bait: HashSet<&str> = f.bait_words.iter().map(String::as_str).collect();
    let checks: [(&str, bool); 9] = [
        ("brands", valid_brands(&f.brands, &bait)),
        ("shorteners", valid_shorteners(&f.shorteners)),
        ("riskyTLDs", valid_risky(&f.risky_tlds)),
        ("baitWords", valid_bait(&f.bait_words)),
        ("paymentNetworks", valid_networks(&f.payment_networks)),
        ("confusables", valid_confusables(&f.confusables)),
        ("publicSuffixList", valid_psl(&f.public_suffix_list)),
        ("rdap", valid_rdap(&f.rdap)),
        ("trackingParams", f.tracking_params.as_deref().is_none_or(valid_tracking)),
    ];
    match checks.iter().find(|c| !c.1) {
        Some((name, _)) => bad(name),
        None => Ok(()),
    }
}
