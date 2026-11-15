//! The contract types (QRCore `Contract/`): what a scan is, what it decodes to, the signals and
//! the verdict. Field names follow Swift's; the bindings spell them in each platform's case.

use std::collections::HashMap;
use std::time::SystemTime;

// MARK: scanned code

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, uniffi::Enum)]
pub enum Symbology {
    Qr,
    MicroQr,
    Aztec,
    Pdf417,
    MicroPdf417,
    DataMatrix,
    Code128,
    Code39,
    Code93,
    Ean13,
    Ean8,
    Upce,
    Itf14,
    Codabar,
    Gs1DataBar,
    Other,
}

impl Symbology {
    pub const ALL: [Symbology; 16] = [
        Self::Qr, Self::MicroQr, Self::Aztec, Self::Pdf417, Self::MicroPdf417, Self::DataMatrix, Self::Code128,
        Self::Code39, Self::Code93, Self::Ean13, Self::Ean8, Self::Upce, Self::Itf14, Self::Codabar,
        Self::Gs1DataBar, Self::Other,
    ];

    /// Swift's raw value ("qr", "microQR", …), as stored in history files.
    pub fn raw(self) -> &'static str {
        match self {
            Self::Qr => "qr",
            Self::MicroQr => "microQR",
            Self::Aztec => "aztec",
            Self::Pdf417 => "pdf417",
            Self::MicroPdf417 => "microPDF417",
            Self::DataMatrix => "dataMatrix",
            Self::Code128 => "code128",
            Self::Code39 => "code39",
            Self::Code93 => "code93",
            Self::Ean13 => "ean13",
            Self::Ean8 => "ean8",
            Self::Upce => "upce",
            Self::Itf14 => "itf14",
            Self::Codabar => "codabar",
            Self::Gs1DataBar => "gs1DataBar",
            Self::Other => "other",
        }
    }

    pub fn from_raw(raw: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|s| s.raw() == raw)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct ScannedCode {
    /// None when the code isn't valid UTF-8 / Latin-1 text.
    pub text: Option<String>,
    pub bytes: Vec<u8>,
    pub symbology: Symbology,
}

impl ScannedCode {
    /// A text code; `bytes` are its UTF-8.
    pub fn text(text: &str, symbology: Symbology) -> Self {
        ScannedCode { text: Some(text.to_string()), bytes: text.as_bytes().to_vec(), symbology }
    }
}

/// A text code whose bytes are its UTF-8.
#[uniffi::export]
pub fn scanned_code_from_text(text: String, symbology: Symbology) -> ScannedCode {
    ScannedCode::text(&text, symbology)
}

// MARK: payloads

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct LinkInfo {
    /// Exactly what was scanned.
    pub original: String,
    pub scheme: String,
    pub userinfo: Option<String>,
    /// xn-- form, lowercased. Empty for dangerous schemes without an authority.
    pub host_ascii: String,
    /// Decoded form, for display.
    pub host_unicode: String,
    /// Unicode form; None only for IP hosts (and hosts that are a suffix).
    pub registrable_domain: Option<String>,
    pub public_suffix: Option<String>,
    pub suffix_is_private: bool,
    pub port: Option<i64>,
    pub path_and_query: String,
    pub is_ip_address: bool,
    /// What Open and Peek use.
    pub normalized: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PaymentFormat {
    Emv,
    Upi,
    Epc,
    Swiss,
}

impl PaymentFormat {
    pub fn raw(self) -> &'static str {
        match self {
            Self::Emv => "emv",
            Self::Upi => "upi",
            Self::Epc => "epc",
            Self::Swiss => "swiss",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PaymentParty {
    Person,
    Business,
    Unknown,
}

impl PaymentParty {
    pub fn raw(self) -> &'static str {
        match self {
            Self::Person => "person",
            Self::Business => "business",
            Self::Unknown => "unknown",
        }
    }
    pub fn from_raw(raw: &str) -> Option<Self> {
        match raw {
            "person" => Some(Self::Person),
            "business" => Some(Self::Business),
            "unknown" => Some(Self::Unknown),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PaymentChecksum {
    Valid,
    Invalid,
    Absent,
}

impl PaymentChecksum {
    pub fn raw(self) -> &'static str {
        match self {
            Self::Valid => "valid",
            Self::Invalid => "invalid",
            Self::Absent => "absent",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PaymentInfo {
    pub format: PaymentFormat,
    pub payee_name: Option<String>,
    pub payee_account: Option<String>,
    pub city: Option<String>,
    pub country: Option<String>,
    /// Decimal text as given; never a float.
    pub amount: Option<String>,
    /// ISO 4217 alpha code.
    pub currency: Option<String>,
    /// Display name from payment-networks.json.
    pub network: Option<String>,
    pub is_dynamic: Option<bool>,
    pub reference: Option<String>,
    pub note: Option<String>,
    pub party: PaymentParty,
    pub checksum: PaymentChecksum,
    /// Format-specific extras ("alternateName", "networkID", "bic", "debtorName", …).
    pub extra: HashMap<String, String>,
}

impl PaymentInfo {
    pub fn new(format: PaymentFormat) -> Self {
        PaymentInfo {
            format,
            payee_name: None,
            payee_account: None,
            city: None,
            country: None,
            amount: None,
            currency: None,
            network: None,
            is_dynamic: None,
            reference: None,
            note: None,
            party: PaymentParty::Unknown,
            checksum: PaymentChecksum::Absent,
            extra: HashMap::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct CryptoInfo {
    /// URI scheme as scanned, lowercased; None for a bare address.
    pub scheme: Option<String>,
    pub network: String,
    /// The address, or for `lightning:` the invoice.
    pub address: String,
    pub amount: Option<String>,
    pub label: Option<String>,
    pub message: Option<String>,
    /// EIP-681 `@chain`.
    pub chain_id: Option<String>,
    pub is_lightning_invoice: bool,
}

impl CryptoInfo {
    pub fn bare(network: &str, address: &str) -> Self {
        CryptoInfo {
            scheme: None,
            network: network.into(),
            address: address.into(),
            amount: None,
            label: None,
            message: None,
            chain_id: None,
            is_lightning_invoice: false,
        }
    }

    /// The address in groups of four characters, for display.
    pub fn address_groups(&self) -> Vec<String> {
        let mut groups = Vec::new();
        let mut current = String::new();
        let mut n = 0;
        for g in crate::text::graphemes(&self.address) {
            current.push_str(g);
            n += 1;
            if n == 4 {
                groups.push(std::mem::take(&mut current));
                n = 0;
            }
        }
        if !current.is_empty() {
            groups.push(current);
        }
        groups
    }
}

/// The address in groups of four, for display (SPEC §5).
#[uniffi::export]
pub fn crypto_address_groups(info: CryptoInfo) -> Vec<String> {
    info.address_groups()
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct AppLinkInfo {
    /// Lowercased.
    pub scheme: String,
    pub original: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct TextInfo {
    /// The text, cut at 8 KB when `is_truncated`.
    pub text: String,
    pub is_truncated: bool,
    /// Links found inside, at most 10.
    pub links: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct WiFiInfo {
    pub ssid: String,
    /// `T` as given, uppercased; None when missing.
    pub security: Option<String>,
    pub password: Option<String>,
    pub is_hidden: bool,
    /// `E` (enterprise EAP method).
    pub eap_method: Option<String>,
    /// `I` (enterprise identity).
    pub identity: Option<String>,
}

impl WiFiInfo {
    pub fn is_enterprise(&self) -> bool {
        self.eap_method.is_some() || self.security.as_deref().is_some_and(|s| crate::text::contains(s, "EAP"))
    }

    /// SPEC §4.2 `openWiFi`: `T` missing, empty or `nopass`.
    pub fn is_open(&self) -> bool {
        match self.security.as_deref().map(crate::text::trim_ws) {
            None | Some("") => true,
            Some(s) => crate::text::lower(s) == "nopass",
        }
    }
}

#[uniffi::export]
pub fn wifi_is_open(info: WiFiInfo) -> bool {
    info.is_open()
}

#[uniffi::export]
pub fn wifi_is_enterprise(info: WiFiInfo) -> bool {
    info.is_enterprise()
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct ContactInfo {
    pub name: Option<String>,
    pub given_name: Option<String>,
    pub family_name: Option<String>,
    pub organization: Option<String>,
    pub title: Option<String>,
    pub phones: Vec<String>,
    pub emails: Vec<String>,
    pub urls: Vec<String>,
    /// One display string per address, lines joined with "\n".
    pub addresses: Vec<String>,
    pub note: Option<String>,
    /// As written (vCard `BDAY`).
    pub birthday: Option<String>,
    /// Every link to check: `urls` plus links found in `note`, at most 10.
    pub links: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct EventInfo {
    pub summary: Option<String>,
    /// For UTC and TZID times: the instant. For floating and all-day times: the wall-clock time
    /// read as if it were GMT.
    pub start: Option<SystemTime>,
    pub end: Option<SystemTime>,
    pub is_all_day: bool,
    pub is_floating: bool,
    /// The TZID the times were given in, when known.
    pub time_zone_id: Option<String>,
    pub location: Option<String>,
    pub description: Option<String>,
    pub url: Option<String>,
    /// `url` plus links found in description and location, at most 10.
    pub links: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct PhoneInfo {
    pub number: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct SmsInfo {
    pub number: String,
    pub body: Option<String>,
    pub links: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct EmailInfo {
    pub address: String,
    pub subject: Option<String>,
    pub body: Option<String>,
    pub links: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct GeoInfo {
    /// Decimal text as given.
    pub latitude: String,
    pub longitude: String,
    pub altitude: Option<String>,
    /// `?q=` as given (percent-decoded).
    pub query: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum Payload {
    /// http/https (including schemeless links) and the dangerous schemes.
    Link { info: LinkInfo },
    /// Any other scheme (carries `unknownScheme`).
    AppLink { info: AppLinkInfo },
    Payment { info: PaymentInfo },
    Crypto { info: CryptoInfo },
    Wifi { info: WiFiInfo },
    Contact { info: ContactInfo },
    Event { info: EventInfo },
    Phone { info: PhoneInfo },
    Sms { info: SmsInfo },
    Email { info: EmailInfo },
    Geo { info: GeoInfo },
    Text { info: TextInfo },
    Binary { byte_count: i64 },
}

impl Payload {
    /// "link", "payment", … used by fixtures and history.
    pub fn type_id(&self) -> &'static str {
        match self {
            Payload::Link { .. } => "link",
            Payload::AppLink { .. } => "appLink",
            Payload::Payment { .. } => "payment",
            Payload::Crypto { .. } => "crypto",
            Payload::Wifi { .. } => "wifi",
            Payload::Contact { .. } => "contact",
            Payload::Event { .. } => "event",
            Payload::Phone { .. } => "phone",
            Payload::Sms { .. } => "sms",
            Payload::Email { .. } => "email",
            Payload::Geo { .. } => "geo",
            Payload::Text { .. } => "text",
            Payload::Binary { .. } => "binary",
        }
    }
}

#[uniffi::export]
pub fn payload_type_id(payload: Payload) -> String {
    payload.type_id().to_string()
}

// MARK: verdict

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, uniffi::Enum)]
pub enum Level {
    Normal,
    Careful,
    LikelyScam,
}

impl Level {
    pub fn raw(self) -> i64 {
        self as i64
    }
    pub fn from_raw(raw: i64) -> Option<Self> {
        match raw {
            0 => Some(Self::Normal),
            1 => Some(Self::Careful),
            2 => Some(Self::LikelyScam),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum Severity {
    Info,
    Warning,
    Strong,
}

/// Every signal, in Swift's declaration order (which is the stable order within a severity).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord, uniffi::Enum)]
pub enum SignalId {
    DangerousScheme,
    UserinfoTrick,
    MixedScript,
    LookalikeBrand,
    BrandInDomain,
    NearBrand,
    IpHost,
    Shortener,
    PlainHttp,
    RiskyTld,
    DeepSubdomains,
    ObfuscatedPath,
    UnknownScheme,
    BrandOtherEnding,
    OpenWiFi,
    PaymentChecksumFail,
    TlsProblem,
    SecretFormBrandMismatch,
    DomainVeryNew,
    RedirectsToHttp,
    RedirectLeavesWeb,
    TooManyRedirects,
    ScriptRedirect,
    AsksForSecret,
    FormPostsElsewhere,
    DownloadsFile,
    DomainNew,
    AssumedHttps,
    SharedHostingDomain,
    NeedsJavaScript,
    AgeUnknown,
    PeekIncomplete,
    UnknownEnding,
    KnownByYou,
}

impl SignalId {
    pub const ALL: [SignalId; 34] = [
        Self::DangerousScheme, Self::UserinfoTrick, Self::MixedScript, Self::LookalikeBrand, Self::BrandInDomain,
        Self::NearBrand, Self::IpHost, Self::Shortener, Self::PlainHttp, Self::RiskyTld, Self::DeepSubdomains,
        Self::ObfuscatedPath, Self::UnknownScheme, Self::BrandOtherEnding, Self::OpenWiFi, Self::PaymentChecksumFail,
        Self::TlsProblem, Self::SecretFormBrandMismatch, Self::DomainVeryNew, Self::RedirectsToHttp,
        Self::RedirectLeavesWeb, Self::TooManyRedirects, Self::ScriptRedirect, Self::AsksForSecret,
        Self::FormPostsElsewhere, Self::DownloadsFile, Self::DomainNew, Self::AssumedHttps, Self::SharedHostingDomain,
        Self::NeedsJavaScript, Self::AgeUnknown, Self::PeekIncomplete, Self::UnknownEnding, Self::KnownByYou,
    ];

    /// Swift's raw value ("dangerousScheme", …).
    pub fn raw(self) -> &'static str {
        match self {
            Self::DangerousScheme => "dangerousScheme",
            Self::UserinfoTrick => "userinfoTrick",
            Self::MixedScript => "mixedScript",
            Self::LookalikeBrand => "lookalikeBrand",
            Self::BrandInDomain => "brandInDomain",
            Self::NearBrand => "nearBrand",
            Self::IpHost => "ipHost",
            Self::Shortener => "shortener",
            Self::PlainHttp => "plainHTTP",
            Self::RiskyTld => "riskyTLD",
            Self::DeepSubdomains => "deepSubdomains",
            Self::ObfuscatedPath => "obfuscatedPath",
            Self::UnknownScheme => "unknownScheme",
            Self::BrandOtherEnding => "brandOtherEnding",
            Self::OpenWiFi => "openWiFi",
            Self::PaymentChecksumFail => "paymentChecksumFail",
            Self::TlsProblem => "tlsProblem",
            Self::SecretFormBrandMismatch => "secretFormBrandMismatch",
            Self::DomainVeryNew => "domainVeryNew",
            Self::RedirectsToHttp => "redirectsToHTTP",
            Self::RedirectLeavesWeb => "redirectLeavesWeb",
            Self::TooManyRedirects => "tooManyRedirects",
            Self::ScriptRedirect => "scriptRedirect",
            Self::AsksForSecret => "asksForSecret",
            Self::FormPostsElsewhere => "formPostsElsewhere",
            Self::DownloadsFile => "downloadsFile",
            Self::DomainNew => "domainNew",
            Self::AssumedHttps => "assumedHTTPS",
            Self::SharedHostingDomain => "sharedHostingDomain",
            Self::NeedsJavaScript => "needsJavaScript",
            Self::AgeUnknown => "ageUnknown",
            Self::PeekIncomplete => "peekIncomplete",
            Self::UnknownEnding => "unknownEnding",
            Self::KnownByYou => "knownByYou",
        }
    }

    pub fn from_raw(raw: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|s| s.raw() == raw)
    }

    /// Fixed per ID.
    pub fn severity(self) -> Severity {
        use SignalId::*;
        match self {
            DangerousScheme | UserinfoTrick | MixedScript | LookalikeBrand | BrandInDomain | PaymentChecksumFail
            | TlsProblem | SecretFormBrandMismatch | DomainVeryNew => Severity::Strong,
            NearBrand | IpHost | Shortener | PlainHttp | RiskyTld | DeepSubdomains | ObfuscatedPath | UnknownScheme
            | OpenWiFi | RedirectsToHttp | RedirectLeavesWeb | TooManyRedirects | ScriptRedirect | AsksForSecret
            | FormPostsElsewhere | DownloadsFile | DomainNew | BrandOtherEnding => Severity::Warning,
            AssumedHttps | SharedHostingDomain | NeedsJavaScript | AgeUnknown | PeekIncomplete | UnknownEnding
            | KnownByYou => Severity::Info,
        }
    }
}

#[uniffi::export]
pub fn signal_id_raw(id: SignalId) -> String {
    id.raw().to_string()
}

#[uniffi::export]
pub fn signal_id_from_raw(raw: String) -> Option<SignalId> {
    SignalId::from_raw(&raw)
}

#[uniffi::export]
pub fn signal_id_severity(id: SignalId) -> Severity {
    id.severity()
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Signal {
    pub id: SignalId,
    /// e.g. {"brand": "PayPal"}, {"days": "12"}, {"tld": "zip"}
    pub args: HashMap<String, String>,
}

impl Signal {
    pub fn new(id: SignalId) -> Self {
        Signal { id, args: HashMap::new() }
    }

    pub fn with(id: SignalId, args: &[(&str, &str)]) -> Self {
        Signal { id, args: args.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect() }
    }

    pub fn severity(&self) -> Severity {
        self.id.severity()
    }
}

/// SPEC §4.2: strong first, then warning, then info, each in declaration order. Stable.
pub fn in_stable_order(signals: &[Signal]) -> Vec<Signal> {
    let rank = |s: &Signal| {
        let sev = match s.id.severity() {
            Severity::Strong => 0,
            Severity::Warning => 1,
            Severity::Info => 2,
        };
        sev * 1000 + s.id as usize
    };
    let mut out = signals.to_vec();
    out.sort_by_key(rank); // sort_by_key is stable
    out
}

#[uniffi::export]
pub fn signals_in_stable_order(signals: Vec<Signal>) -> Vec<Signal> {
    in_stable_order(&signals)
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct Analysis {
    pub code: ScannedCode,
    pub payload: Payload,
    /// This payload's own, in stable order.
    pub signals: Vec<Signal>,
    /// Links found inside text, contacts, events, SMS and email bodies.
    pub embedded: Vec<Analysis>,
    /// Max over self and embedded.
    pub level: Level,
}

impl Analysis {
    /// Every signal of this analysis and its embedded analyses, own first.
    pub fn all_signals(&self) -> Vec<Signal> {
        let mut out = self.signals.clone();
        for e in &self.embedded {
            out.extend(e.all_signals());
        }
        out
    }
}

#[uniffi::export]
pub fn analysis_all_signals(analysis: Analysis) -> Vec<Signal> {
    analysis.all_signals()
}

// MARK: Peek (pure half)

#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct PeekHop {
    pub url: String,
    pub status: Option<i64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum StopReason {
    Finished,
    Timeout,
    HttpTarget,
    NonWebTarget,
    TooManyRedirects,
    TlsFailure,
    NetworkError,
}

impl StopReason {
    pub fn from_raw(raw: &str) -> Option<Self> {
        Some(match raw {
            "finished" => Self::Finished,
            "timeout" => Self::Timeout,
            "httpTarget" => Self::HttpTarget,
            "nonWebTarget" => Self::NonWebTarget,
            "tooManyRedirects" => Self::TooManyRedirects,
            "tlsFailure" => Self::TlsFailure,
            "networkError" => Self::NetworkError,
            _ => return None,
        })
    }
}

/// What the native Peek client hands back.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeekCapture {
    /// Every URL requested or redirected to, in order; the first is the scanned link.
    pub hops: Vec<PeekHop>,
    pub stopped_because: StopReason,
    pub content_type: Option<String>,
    pub content_disposition: Option<String>,
    /// At most 512 KB.
    pub body: Vec<u8>,
    pub tls_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PeekReport {
    pub final_link: Option<LinkInfo>,
    /// Offline checks rerun on every hop.
    pub hop_analyses: Vec<Analysis>,
    pub title: Option<String>,
    pub signals: Vec<Signal>,
    /// Combined with the original scan's level.
    pub level: Level,
}

// MARK: make

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum MakeRequest {
    Link { text: String },
    Text { text: String },
    Wifi { info: WiFiInfo },
    Contact { info: ContactInfo },
    Event { info: EventInfo },
    Email { info: EmailInfo },
    Sms { info: SmsInfo },
    Phone { number: String },
    Geo { info: GeoInfo },
}
