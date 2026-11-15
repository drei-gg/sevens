//! A browser-like address parser (QRCore `URL/`): Punycode, the Public Suffix List, hosts and
//! `WebAddress`. Never uses a platform URL type, so both apps find the same host.

use crate::contract::LinkInfo;
use crate::text as t;
use std::collections::HashMap;

// MARK: Punycode

/// RFC 3492 for single labels (without the "xn--" prefix).
pub mod punycode {
    const BASE: i64 = 36;
    const T_MIN: i64 = 1;
    const T_MAX: i64 = 26;
    const SKEW: i64 = 38;
    const DAMP: i64 = 700;
    const INITIAL_BIAS: i64 = 72;
    const INITIAL_N: i64 = 128;

    fn adapt(delta_in: i64, num_points: i64, first: bool) -> i64 {
        let mut delta = if first { delta_in / DAMP } else { delta_in / 2 };
        delta += delta / num_points;
        let mut k = 0;
        while delta > ((BASE - T_MIN) * T_MAX) / 2 {
            delta /= BASE - T_MIN;
            k += BASE;
        }
        k + (BASE - T_MIN + 1) * delta / (delta + SKEW)
    }

    fn digit(d: i64) -> char {
        (if d < 26 { 97 + d } else { 22 + d }) as u8 as char
    }

    /// Encodes a label's Unicode text. All-ASCII input gives `text-`, per the RFC.
    /// None for empty input or arithmetic overflow.
    pub fn encode(label: &str) -> Option<String> {
        let input: Vec<i64> = label.chars().map(|c| c as i64).collect();
        if input.is_empty() || input.len() > 4096 {
            return None;
        }
        let mut output: String = input.iter().filter(|&&c| c < 128).map(|&c| c as u8 as char).collect();
        let b = output.chars().count() as i64;
        let mut h = b;
        if b > 0 {
            output.push('-');
        }
        let (mut n, mut delta, mut bias) = (INITIAL_N, 0i64, INITIAL_BIAS);
        while h < input.len() as i64 {
            let m = *input.iter().filter(|&&c| c >= n).min()?;
            let mul = (m - n).checked_mul(h + 1)?;
            delta = delta.checked_add(mul)?;
            n = m;
            for &c in &input {
                if c < n {
                    delta += 1;
                }
                if c == n {
                    let mut q = delta;
                    let mut k = BASE;
                    loop {
                        let tt = if k <= bias { T_MIN } else if k >= bias + T_MAX { T_MAX } else { k - bias };
                        if q < tt {
                            break;
                        }
                        output.push(digit(tt + (q - tt) % (BASE - tt)));
                        q = (q - tt) / (BASE - tt);
                        k += BASE;
                    }
                    output.push(digit(q));
                    bias = adapt(delta, h + 1, h == b);
                    delta = 0;
                    h += 1;
                }
            }
            delta += 1;
            n += 1;
        }
        Some(output)
    }

    /// Decodes the text after `xn--`. None when it isn't valid Punycode (or is empty).
    pub fn decode(label: &str) -> Option<String> {
        let bytes = label.as_bytes();
        if bytes.is_empty() || bytes.len() > 4096 || !bytes.iter().all(|&b| b < 128) {
            return None;
        }
        let mut output: Vec<i64> = Vec::new();
        let mut pos = 0;
        if let Some(d) = bytes.iter().rposition(|&b| b == b'-') {
            output = bytes[..d].iter().map(|&b| b as i64).collect();
            pos = d + 1;
        }
        let (mut n, mut i, mut bias) = (INITIAL_N, 0i64, INITIAL_BIAS);
        while pos < bytes.len() {
            let old_i = i;
            let mut w = 1i64;
            let mut k = BASE;
            loop {
                if pos >= bytes.len() {
                    return None;
                }
                let c = bytes[pos];
                pos += 1;
                let d = match c {
                    48..=57 => c as i64 - 22,
                    65..=90 => c as i64 - 65,
                    97..=122 => c as i64 - 97,
                    _ => return None,
                };
                i = i.checked_add(d.checked_mul(w)?)?;
                let tt = if k <= bias { T_MIN } else if k >= bias + T_MAX { T_MAX } else { k - bias };
                if d < tt {
                    break;
                }
                w = w.checked_mul(BASE - tt)?;
                k += BASE;
            }
            let count = output.len() as i64 + 1;
            bias = adapt(i - old_i, count, old_i == 0);
            n += i / count;
            i %= count;
            if n > 0x10FFFF || char::from_u32(n as u32).is_none() {
                return None;
            }
            output.insert(i as usize, n);
            i += 1;
            if output.len() > 4096 {
                return None;
            }
        }
        if output.is_empty() {
            return None;
        }
        output.into_iter().map(|v| char::from_u32(v as u32)).collect()
    }
}

// MARK: Public Suffix List

/// What the list says about a host.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PublicSuffixMatch {
    /// ASCII (xn--) form.
    pub public_suffix: String,
    /// ASCII form; None when the host *is* a public suffix.
    pub registrable_domain: Option<String>,
    /// The matched rule is in the PRIVATE section (e.g. github.io).
    pub is_private: bool,
    /// false when only PSL's default rule ("*") applied.
    pub matched_explicit_rule: bool,
    /// The registrable domain computed with the ICANN section only (for RDAP).
    pub icann_registrable_domain: Option<String>,
}

/// Mozilla's Public Suffix List (ICANN and private sections, wildcard and exception rules).
#[derive(Debug, Clone, Default)]
pub struct PublicSuffixList {
    rules: HashMap<String, bool>,
    wildcards: HashMap<String, bool>,
    exceptions: HashMap<String, bool>,
    max_labels: usize,
}

struct Rule {
    length: usize,
    explicit: bool,
    is_private: bool,
}

impl PublicSuffixList {
    /// Parses the `.dat` text. An empty string gives a list where only the default rule applies.
    pub fn new(text: &str) -> Self {
        let mut list = PublicSuffixList { max_labels: 1, ..Default::default() };
        let mut is_private = false;
        for raw_line in text.split(['\n', '\r']).filter(|l| !l.is_empty()) {
            if t::has_prefix(raw_line, "//") {
                if t::contains(raw_line, "===BEGIN PRIVATE DOMAINS===") {
                    is_private = true;
                } else if t::contains(raw_line, "===END PRIVATE DOMAINS===") {
                    is_private = false;
                }
                continue;
            }
            let Some(token) = t::split(raw_line, |g| g == " " || g == "\t", true).into_iter().next() else { continue };
            let mut rule = t::lower(token);
            let is_exception = t::has_prefix(&rule, "!");
            if is_exception {
                rule = t::drop_first(&rule, 1).to_string();
            }
            let is_wildcard = t::has_prefix(&rule, "*.");
            if is_wildcard {
                rule = t::drop_first(&rule, 2).to_string();
            }
            let Some(ascii) = Self::to_ascii(&rule) else { continue };
            let store = |d: &mut HashMap<String, bool>| {
                // A rule listed in both sections counts as ICANN.
                let v = *d.get(&ascii).unwrap_or(&is_private) && is_private;
                d.insert(ascii.clone(), v);
            };
            if is_exception {
                store(&mut list.exceptions);
            } else if is_wildcard {
                store(&mut list.wildcards);
            } else {
                store(&mut list.rules);
            }
            let labels = t::split_ch(&ascii, '.', true).len() + usize::from(is_wildcard);
            list.max_labels = list.max_labels.max(labels);
        }
        list
    }

    fn to_ascii(rule: &str) -> Option<String> {
        let mut out = Vec::new();
        for label in t::split_ch(rule, '.', false) {
            if label.is_empty() {
                return None;
            }
            if label.is_ascii() {
                out.push(label.to_string());
            } else {
                out.push(format!("xn--{}", punycode::encode(label)?));
            }
        }
        Some(out.join("."))
    }

    fn prevailing(&self, labels: &[&str], icann_only: bool) -> Rule {
        let n = labels.len();
        let limit = n.min(self.max_labels);
        let key = |k: usize| labels[n - k..].join(".");
        let usable = |flag: Option<&bool>| -> Option<bool> {
            let f = *flag?;
            if icann_only && f { None } else { Some(f) }
        };
        for k in (1..=limit).rev() {
            if let Some(flag) = usable(self.exceptions.get(&key(k))) {
                return Rule { length: k - 1, explicit: true, is_private: flag };
            }
        }
        let mut best = Rule { length: 1, explicit: false, is_private: false };
        for k in 1..=limit {
            if let Some(flag) = usable(self.rules.get(&key(k))) {
                best = Rule { length: k, explicit: true, is_private: flag };
            }
            if k >= 2 && k <= n
                && let Some(flag) = usable(self.wildcards.get(&key(k - 1))) {
                    best = Rule { length: k, explicit: true, is_private: flag };
                }
        }
        best
    }

    /// `host_ascii`: lowercased, xn-- form, no trailing dot.
    pub fn match_host(&self, host_ascii: &str) -> PublicSuffixMatch {
        let labels = t::split_ch(host_ascii, '.', false);
        if host_ascii.is_empty() || labels.iter().any(|l| l.is_empty()) {
            return PublicSuffixMatch {
                public_suffix: host_ascii.to_string(),
                registrable_domain: None,
                is_private: false,
                matched_explicit_rule: false,
                icann_registrable_domain: None,
            };
        }
        let build = |r: &Rule| -> (String, Option<String>) {
            let n = labels.len();
            let len = r.length.min(n);
            let suffix = labels[n - len..].join(".");
            let domain = if n > len { Some(labels[n - len - 1..].join(".")) } else { None };
            (suffix, domain)
        };
        let all = self.prevailing(&labels, false);
        let icann = self.prevailing(&labels, true);
        let (suffix, domain) = build(&all);
        PublicSuffixMatch {
            public_suffix: suffix,
            registrable_domain: domain,
            is_private: all.is_private,
            matched_explicit_rule: all.explicit,
            icann_registrable_domain: build(&icann).1,
        }
    }
}

// MARK: URLText

pub mod url_text {
    use crate::text as t;
    use unicode_general_category::GeneralCategory as G;

    pub const MAX_BYTES: usize = 8192;

    /// Cap at 8 KB of UTF-8 (at a scalar boundary), trim C0 controls and space at both ends,
    /// remove tab, CR and LF anywhere.
    pub fn clean(text: &str) -> String {
        let capped = t::cut_utf8(text, MAX_BYTES);
        capped.trim_matches(|c: char| (c as u32) <= 0x20).chars().filter(|&c| c != '\t' && c != '\r' && c != '\n').collect()
    }

    pub fn is_label_scalar(c: char) -> bool {
        if c == '-' {
            return true;
        }
        matches!(
            t::general_category(c),
            G::UppercaseLetter
                | G::LowercaseLetter
                | G::TitlecaseLetter
                | G::ModifierLetter
                | G::OtherLetter
                | G::NonspacingMark
                | G::SpacingMark
                | G::DecimalNumber
        )
    }

    /// Splits at the first `?` or `#`: (path, rest including that character). With `backslashes`,
    /// `\` in the path becomes `/`.
    pub fn split_path(s: &str, backslashes: bool) -> (String, String) {
        let cut = t::find(s, |g| t::ch_eq(g, '?') || t::ch_eq(g, '#')).unwrap_or(s.len());
        let mut path = s[..cut].to_string();
        if backslashes {
            path = t::replace_ch(&path, '\\', "/");
        }
        (path, s[cut..].to_string())
    }

    /// RFC 3986 dot-segment removal; `%2e` counts as a dot, as in browsers.
    pub fn remove_dot_segments(path: &str) -> String {
        let segments = t::split_ch(path, '/', false);
        let mut out: Vec<&str> = Vec::new();
        for (i, seg) in segments.iter().enumerate() {
            let lower = t::lower(seg);
            let is_last = i == segments.len() - 1;
            let is_single = lower == "." || lower == "%2e";
            let is_double = ["..", ".%2e", "%2e.", "%2e%2e"].contains(&lower.as_str());
            if is_double {
                if out.len() > 1 {
                    out.pop();
                }
                if is_last {
                    out.push("");
                }
            } else if is_single {
                if is_last {
                    out.push("");
                }
            } else {
                out.push(seg);
            }
        }
        let joined = out.join("/");
        if t::has_prefix(&joined, "/") { joined } else { format!("/{joined}") }
    }
}

// MARK: hosts

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum HostKind {
    Domain,
    Ipv4,
    Ipv6,
}

pub struct ParsedHost {
    pub ascii: String,
    pub unicode: String,
    pub kind: HostKind,
    pub malformed: bool,
}

pub mod host {
    use super::{HostKind, ParsedHost, punycode};
    use crate::text as t;

    fn hex(b: u8) -> Option<u8> {
        match b {
            48..=57 => Some(b - 48),
            65..=70 => Some(b - 55),
            97..=102 => Some(b - 87),
            _ => None,
        }
    }

    /// Percent-decodes; None when the result isn't UTF-8.
    pub fn percent_decode(s: &str) -> Option<String> {
        let bytes = s.as_bytes();
        let mut out = Vec::with_capacity(bytes.len());
        let mut i = 0;
        while i < bytes.len() {
            if bytes[i] == b'%' && i + 2 < bytes.len()
                && let (Some(h), Some(l)) = (hex(bytes[i + 1]), hex(bytes[i + 2])) {
                    out.push(h << 4 | l);
                    i += 3;
                    continue;
                }
            out.push(bytes[i]);
            i += 1;
        }
        String::from_utf8(out).ok()
    }

    const FORBIDDEN: &[char] = &[' ', '#', '/', ':', '<', '>', '?', '@', '[', '\\', ']', '^', '|', '%'];

    /// `raw` is the host as written, between userinfo and port (brackets included for IPv6).
    pub fn parse(raw: &str) -> Option<ParsedHost> {
        if t::has_prefix(raw, "[") {
            if !t::has_suffix(raw, "]") || t::count(raw) < 4 {
                return None;
            }
            let inner = t::lower(t::drop_last(t::drop_first(raw, 1), 1));
            let ok_chars = inner.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c) || c == ':' || c == '.');
            if !ok_chars || !inner.contains(':') || t::count(&inner) > 45 || inner.split("::").count() > 2 {
                return None;
            }
            let h = format!("[{inner}]");
            return Some(ParsedHost { ascii: h.clone(), unicode: h, kind: HostKind::Ipv6, malformed: false });
        }
        let mut host = percent_decode(raw)?;
        // Browsers fold compatibility forms before looking the host up, so we check the folded
        // form. Folding can create characters a host can't hold, so the forbidden-character test
        // runs afterwards.
        host = t::nfkc(&host);
        host = host.chars().map(|c| if matches!(c as u32, 0x3002 | 0xFF0E | 0xFF61) { '.' } else { c }).collect();
        if host.chars().any(|c| FORBIDDEN.contains(&c) || (c as u32) <= 0x20 || c as u32 == 0x7F) {
            return None;
        }
        host = t::nfkc(&t::lower(&host));
        if t::has_suffix(&host, ".") {
            host = t::drop_last(&host, 1).to_string();
        }
        if host.is_empty() {
            return None;
        }
        let labels = t::split_ch(&host, '.', false);
        if let Some(last) = labels.last()
            && is_numeric_label(last) {
                let v4 = parse_ipv4(&labels)?;
                return Some(ParsedHost { ascii: v4.clone(), unicode: v4, kind: HostKind::Ipv4, malformed: false });
            }
        let mut ascii = Vec::new();
        let mut unicode = Vec::new();
        let mut malformed = false;
        for label in labels {
            if label.is_ascii() {
                ascii.push(label.to_string());
                if t::has_prefix(label, "xn--") {
                    match punycode::decode(&label[4..]) {
                        Some(d) if !d.is_ascii() => unicode.push(d),
                        _ => {
                            unicode.push(label.to_string());
                            malformed = true;
                        }
                    }
                } else {
                    unicode.push(label.to_string());
                }
            } else if let Some(e) = punycode::encode(label) {
                ascii.push(format!("xn--{e}"));
                unicode.push(label.to_string());
            } else {
                ascii.push(label.to_string());
                unicode.push(label.to_string());
                malformed = true;
            }
        }
        Some(ParsedHost { ascii: ascii.join("."), unicode: unicode.join("."), kind: HostKind::Domain, malformed })
    }

    /// Unicode form of an ASCII host or part of one; labels that don't decode stay as they are.
    pub fn unicode_form(ascii: &str) -> String {
        t::split_ch(ascii, '.', false)
            .into_iter()
            .map(|label| {
                if t::has_prefix(label, "xn--")
                    && let Some(d) = punycode::decode(t::drop_first(label, 4)) {
                        return d;
                    }
                label.to_string()
            })
            .collect::<Vec<_>>()
            .join(".")
    }

    fn is_numeric_label(s: &str) -> bool {
        if s.is_empty() {
            return false;
        }
        if t::graphemes(s).all(|g| t::ch_is_ascii(g) && t::ch_is_number(g)) {
            return true;
        }
        let l = t::lower(s);
        t::has_prefix(&l, "0x") && t::graphemes(t::drop_first(&l, 2)).all(|g| g.len() == 1 && g.as_bytes()[0].is_ascii_hexdigit())
    }

    fn parse_ipv4_part(s: &str) -> Option<u64> {
        if s.is_empty() || t::count(s) > 12 {
            return None;
        }
        let lowered = t::lower(s);
        let mut digits: &str = &lowered;
        let mut radix = 10;
        if t::has_prefix(digits, "0x") {
            digits = t::drop_first(digits, 2);
            radix = 16;
        } else if t::count(digits) > 1 && t::has_prefix(digits, "0") {
            digits = t::drop_first(digits, 1);
            radix = 8;
        }
        if digits.is_empty() {
            return Some(0);
        }
        if !t::graphemes(digits).all(t::ch_is_ascii) {
            return None;
        }
        t::parse_uint(digits, radix)
    }

    fn parse_ipv4(parts: &[&str]) -> Option<String> {
        if !(1..=4).contains(&parts.len()) {
            return None;
        }
        let nums: Vec<u64> = parts.iter().map(|p| parse_ipv4_part(p)).collect::<Option<_>>()?;
        if nums[..nums.len() - 1].iter().any(|&v| v > 255) {
            return None;
        }
        let last_limit: u64 = 1u64 << (8 * (5 - nums.len() as u64));
        let last = nums[nums.len() - 1];
        if last >= last_limit {
            return None;
        }
        let mut ip = last;
        for (i, v) in nums[..nums.len() - 1].iter().enumerate() {
            ip += v << (8 * (3 - i as u64));
        }
        Some(format!("{}.{}.{}.{}", (ip >> 24) & 255, (ip >> 16) & 255, (ip >> 8) & 255, ip & 255))
    }
}

// MARK: WebAddress

/// A parsed http/https address and what the checks need to know about it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct WebAddress {
    pub link: LinkInfo,
    pub host_kind: HostKind,
    /// A label failed punycode conversion: the checks add `mixedScript`.
    pub has_malformed_label: bool,
    /// The public suffix came from an explicit PSL rule.
    pub suffix_is_known: bool,
    /// No scheme was given and `https` was assumed (the checks add `assumedHTTPS`).
    pub assumed_https: bool,
}

impl WebAddress {
    /// The scheme per the WHATWG URL rules, lowercased; None when the text has none.
    pub fn scheme_of(text: &str) -> Option<String> {
        split_scheme(&url_text::clean(text)).map(|(s, _)| s)
    }

    /// An absolute http/https address. None for other schemes or when there is no host.
    pub fn parse(text: &str, psl: &PublicSuffixList) -> Option<WebAddress> {
        build(&url_text::clean(text), text, psl, false)
    }

    /// Text with no scheme and no spaces whose host ends in a suffix with an explicit PSL rule
    /// → https assumed. None otherwise.
    pub fn parse_schemeless(text: &str, psl: &PublicSuffixList) -> Option<WebAddress> {
        let c = url_text::clean(text);
        if c.is_empty()
            || c.chars().any(|s| s.is_whitespace() || (s as u32) < 0x20 || s as u32 == 0x7F)
            || t::find_ch(&c, '@').is_some()
            || t::has_prefix(&c, "/")
            || t::has_prefix(&c, ".")
            || Self::scheme_of(&c).is_some()
        {
            return None;
        }
        let host_part = t::prefix_while(&c, |g| !(t::ch_eq(g, '/') || t::ch_eq(g, '?') || t::ch_eq(g, '#')));
        let labels = t::split_ch(host_part, '.', false);
        if labels.len() < 2 || !labels.iter().all(|l| !l.is_empty() && l.chars().all(url_text::is_label_scalar)) {
            return None;
        }
        let mut address = build(&format!("https://{c}"), text, psl, true)?;
        if address.host_kind != HostKind::Domain || !address.suffix_is_known || address.link.registrable_domain.is_none() {
            return None;
        }
        address.assumed_https = true;
        Some(address)
    }

    /// Resolves a reference against a base, as a browser would for a redirect `Location` or a
    /// form `action`. None if the result isn't http/https.
    pub fn resolve(reference: &str, base: &LinkInfo, psl: &PublicSuffixList) -> Option<WebAddress> {
        let r = url_text::clean(reference);
        if base.scheme != "http" && base.scheme != "https" {
            return None;
        }
        if let Some((scheme, _)) = split_scheme(&r) {
            if scheme != "http" && scheme != "https" {
                return None;
            }
            return build(&r, &r, psl, false);
        }
        let is_slash = |g: &str| t::ch_eq(g, '/') || t::ch_eq(g, '\\');
        let first2: Vec<&str> = t::graphemes(&r).take(2).collect();
        if first2.len() == 2 && is_slash(first2[0]) && is_slash(first2[1]) {
            return build(&format!("{}:{}", base.scheme, r), &r, psl, false);
        }
        let mut authority = format!("{}://", base.scheme);
        if let Some(u) = &base.userinfo {
            authority += u;
            authority += "@";
        }
        authority += &base.host_ascii;
        if let Some(p) = base.port {
            authority += &format!(":{p}");
        }
        let (base_path, base_rest) = url_text::split_path(&base.path_and_query, false);
        let base_query = t::prefix_while(&base_rest, |g| !t::ch_eq(g, '#')).to_string();
        let (ref_path, ref_rest) = url_text::split_path(&r, true);
        let (path, rest) = if r.is_empty() {
            (base_path, base_query)
        } else if t::has_prefix(&r, "#") {
            (base_path, base_query + &r)
        } else if t::has_prefix(&r, "?") {
            (base_path, r.clone())
        } else if t::has_prefix(&ref_path, "/") {
            (ref_path, ref_rest)
        } else {
            let dir = match t::rfind_ch(&base_path, '/') {
                Some(i) => base_path[..i + t::char_len_at(&base_path, i)].to_string(),
                None => "/".to_string(),
            };
            (dir + &ref_path, ref_rest)
        };
        let absolute = format!("{}{}{}", authority, url_text::remove_dot_segments(&path), rest);
        build(&absolute, &absolute, psl, false)
    }
}

/// Scheme and the text after the colon, or None.
pub(crate) fn split_scheme(cleaned: &str) -> Option<(String, &str)> {
    let colon = t::find_ch(cleaned, ':')?;
    let name = &cleaned[..colon];
    let f = name.chars().next()?;
    if !(f.is_ascii() && f.is_alphabetic()) {
        return None;
    }
    if !name.chars().all(|c| c.is_ascii() && (c.is_alphabetic() || c.is_ascii_digit() || c == '+' || c == '-' || c == '.')) {
        return None;
    }
    Some((t::lower(name), &cleaned[colon + t::char_len_at(cleaned, colon)..]))
}

fn parse_port(s: &str) -> Option<Option<i64>> {
    if s.is_empty() {
        return Some(None);
    }
    if t::count(s) > 5 || !t::graphemes(s).all(|g| t::ch_is_ascii(g) && t::ch_is_number(g)) {
        return None;
    }
    let v = t::parse_int(s)?;
    if v > 65535 {
        return None;
    }
    Some(Some(v))
}

pub(crate) fn build(cleaned: &str, original: &str, psl: &PublicSuffixList, assumed_https: bool) -> Option<WebAddress> {
    let (scheme, after_colon) = split_scheme(cleaned)?;
    if scheme != "http" && scheme != "https" {
        return None;
    }
    let body = t::drop_while(after_colon, |g| t::ch_eq(g, '/') || t::ch_eq(g, '\\'));
    let authority_end =
        t::find(body, |g| t::ch_eq(g, '/') || t::ch_eq(g, '\\') || t::ch_eq(g, '?') || t::ch_eq(g, '#')).unwrap_or(body.len());
    let authority = &body[..authority_end];
    let mut remainder = body[authority_end..].to_string();

    let mut userinfo = None;
    let mut host_port = authority;
    if let Some(at) = t::rfind_ch(authority, '@') {
        let u = &authority[..at];
        userinfo = if u.is_empty() { None } else { Some(u.to_string()) };
        host_port = &authority[at + t::char_len_at(authority, at)..];
    }
    let mut raw_host = host_port;
    let mut port = None;
    if t::has_prefix(host_port, "[") {
        let close = t::find_ch(host_port, ']')?;
        let end = close + t::char_len_at(host_port, close);
        raw_host = &host_port[..end];
        let after = &host_port[end..];
        if !after.is_empty() {
            if !t::has_prefix(after, ":") {
                return None;
            }
            port = parse_port(t::drop_first(after, 1))?;
        }
    } else if let Some(colon) = t::rfind_ch(host_port, ':') {
        raw_host = &host_port[..colon];
        let p = parse_port(&host_port[colon + t::char_len_at(host_port, colon)..]);
        if t::find_ch(raw_host, ':').is_some() {
            return None;
        }
        port = p?;
    }
    let host = host::parse(raw_host)?;
    if port == Some(if scheme == "https" { 443 } else { 80 }) {
        port = None;
    }

    // Path part: `\` is `/` for http/https; fragment and query are kept as written.
    if remainder.is_empty() || t::has_prefix(&remainder, "?") || t::has_prefix(&remainder, "#") {
        remainder = format!("/{remainder}");
    }
    let (p, rest) = url_text::split_path(&remainder, true);
    let path_and_query = p + &rest;

    let (mut registrable, mut suffix, mut is_private, mut known) = (None, None, false, false);
    if host.kind == HostKind::Domain {
        let m = psl.match_host(&host.ascii);
        // An ending without an explicit rule falls back to the last label as the suffix (PSL's
        // default rule); `suffix_is_known` tells the checks to add `unknownEnding`.
        registrable = m.registrable_domain.as_deref().map(host::unicode_form);
        suffix = Some(host::unicode_form(&m.public_suffix));
        is_private = m.is_private;
        known = m.matched_explicit_rule;
    }
    let mut normalized = format!("{scheme}://");
    if let Some(u) = &userinfo {
        normalized += u;
        normalized += "@";
    }
    normalized += &host.ascii;
    if let Some(p) = port {
        normalized += &format!(":{p}");
    }
    normalized += &path_and_query;
    let link = LinkInfo {
        original: original.to_string(),
        scheme,
        userinfo,
        host_ascii: host.ascii,
        host_unicode: host.unicode,
        registrable_domain: registrable,
        public_suffix: suffix,
        suffix_is_private: is_private,
        port,
        path_and_query,
        is_ip_address: host.kind != HostKind::Domain,
        normalized,
    };
    Some(WebAddress { link, host_kind: host.kind, has_malformed_label: host.malformed, suffix_is_known: known, assumed_https })
}

/// Host labels and the parts of them the checks look at (QRCore `CheckContext.swift`).
impl LinkInfo {
    /// Host labels, Unicode form.
    pub fn unicode_labels(&self) -> Vec<String> {
        t::split_ch(&self.host_unicode, '.', true).into_iter().map(String::from).collect()
    }

    /// Labels left of the public suffix, including the registrable domain's first label.
    pub fn labels_left_of_suffix(&self) -> Vec<String> {
        let all = self.unicode_labels();
        let Some(suffix) = &self.public_suffix else { return all };
        let n = t::split_ch(suffix, '.', true).len();
        let keep = all.len().saturating_sub(n);
        all[..keep].to_vec()
    }

    /// Labels left of the registrable domain.
    pub fn subdomain_labels(&self) -> Vec<String> {
        let Some(reg) = &self.registrable_domain else { return vec![] };
        let n = t::split_ch(reg, '.', true).len();
        let all = self.unicode_labels();
        let keep = all.len().saturating_sub(n);
        all[..keep].to_vec()
    }

    pub fn registrable_first_label(&self) -> Option<String> {
        let reg = self.registrable_domain.as_deref()?;
        t::split_ch(reg, '.', true).first().map(|s| t::lower(s))
    }
}

/// RFC 3492 encoding of one label (without "xn--").
#[uniffi::export]
pub fn punycode_encode(label: String) -> Option<String> {
    punycode::encode(&label)
}

/// RFC 3492 decoding of the text after "xn--".
#[uniffi::export]
pub fn punycode_decode(label: String) -> Option<String> {
    punycode::decode(&label)
}

/// The scheme per the WHATWG URL rules, lowercased; None when the text has none.
#[uniffi::export]
pub fn web_address_scheme(text: String) -> Option<String> {
    WebAddress::scheme_of(&text)
}
