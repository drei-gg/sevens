//! Structured payloads (QRCore `Structured/`): Wi-Fi, vCard/MeCard, iCalendar events, tel, SMS,
//! mailto/MATMSG and geo, plus the encoder Make uses. These work on Unicode scalars, as the Swift
//! code does (a `;` followed by a combining mark is still a separator).

use crate::contract::*;
use crate::text as t;

/// Text helpers shared by the parsers and the encoder.
pub mod stext {
    use crate::text as t;

    /// Analysis input cap: scanned codes are attacker-controlled.
    pub const MAX_BYTES: usize = 8192;

    pub fn capped(s: &str) -> String {
        if s.len() <= MAX_BYTES {
            return s.to_string();
        }
        t::utf8_lossy(&s.as_bytes()[..MAX_BYTES])
    }

    /// Capped, with leading whitespace, newlines and a BOM removed.
    pub fn prepared(s: &str) -> String {
        let c = capped(s);
        t::drop_while(&c, |g| t::ch_is_whitespace(g) || t::ch_is_newline(g) || t::ch_eq(g, '\u{FEFF}')).to_string()
    }

    pub fn trimmed(s: &str) -> &str {
        t::trim_ws_nl(s)
    }

    pub fn non_empty(s: Option<&str>) -> Option<String> {
        let tt = trimmed(s?);
        if tt.is_empty() { None } else { Some(tt.to_string()) }
    }

    fn lower(c: char) -> char {
        c.to_ascii_lowercase()
    }

    /// The rest of `s` after an ASCII `prefix`, matched case-insensitively.
    pub fn strip_prefix<'a>(s: &'a str, prefix: &str) -> Option<&'a str> {
        let mut it = s.char_indices();
        for p in prefix.chars() {
            let (_, c) = it.next()?;
            if lower(c) != lower(p) {
                return None;
            }
        }
        Some(match it.next() {
            Some((i, _)) => &s[i..],
            None => "",
        })
    }

    /// Splits on `sep`; a backslash protects the scalar after it (both stay in the pieces).
    pub fn split_unescaped(s: &str, sep: char) -> Vec<String> {
        let mut out = Vec::new();
        let mut cur = String::new();
        let mut it = s.chars();
        while let Some(c) = it.next() {
            if c == '\\' {
                cur.push(c);
                if let Some(n) = it.next() {
                    cur.push(n);
                }
            } else if c == sep {
                out.push(std::mem::take(&mut cur));
            } else {
                cur.push(c);
            }
        }
        out.push(cur);
        out
    }

    /// `\x` becomes `x`.
    pub fn unescape_backslash(s: &str) -> String {
        let mut out = String::new();
        let mut it = s.chars();
        while let Some(c) = it.next() {
            if c == '\\' {
                if let Some(n) = it.next() {
                    out.push(n);
                }
            } else {
                out.push(c);
            }
        }
        out
    }

    /// vCard / iCalendar TEXT escaping: `\n` and `\N` are newlines, `\x` is `x`.
    pub fn unescape_text(s: &str) -> String {
        let mut out = String::new();
        let mut it = s.chars();
        while let Some(c) = it.next() {
            if c == '\\' {
                if let Some(n) = it.next() {
                    out.push(if n == 'n' || n == 'N' { '\n' } else { n });
                }
            } else {
                out.push(c);
            }
        }
        out
    }

    /// Inverse of `unescape_text` for `, ; \` and newlines.
    pub fn escape_text(s: &str) -> String {
        let mut out = String::new();
        let mut prev_cr = false;
        for c in s.chars() {
            match c {
                '\\' | ';' | ',' => {
                    out.push('\\');
                    out.push(c);
                    prev_cr = false;
                }
                '\r' => {
                    out.push_str("\\n");
                    prev_cr = true;
                }
                '\n' => {
                    if !prev_cr {
                        out.push_str("\\n");
                    }
                    prev_cr = false;
                }
                _ => {
                    out.push(c);
                    prev_cr = false;
                }
            }
        }
        out
    }

    /// `\x` escaping for Wi-Fi style values.
    pub fn escape_backslash(s: &str, special: &str) -> String {
        let mut out = String::new();
        for c in s.chars() {
            if c == '\\' || special.contains(c) {
                out.push('\\');
            }
            out.push(c);
        }
        out
    }

    /// `KEY:value;KEY:value;;` bodies: keys uppercased, values still escaped.
    pub fn keyed(body: &str) -> Vec<(String, String)> {
        let mut out = Vec::new();
        for piece in split_unescaped(body, ';') {
            let Some(colon) = piece.find(':') else { continue };
            let key = t::upper(t::trim_ws(&piece[..colon]));
            if key.is_empty() {
                continue;
            }
            out.push((key, piece[colon + 1..].to_string()));
        }
        out
    }

    pub fn normalize_newlines(s: &str) -> String {
        s.replace("\r\n", "\n").replace('\r', "\n")
    }

    pub fn hex_value(b: u8) -> Option<u8> {
        match b {
            0x30..=0x39 => Some(b - 0x30),
            0x41..=0x46 => Some(b - 0x41 + 10),
            0x61..=0x66 => Some(b - 0x61 + 10),
            _ => None,
        }
    }

    pub fn percent_decode(s: &str) -> String {
        let u = s.as_bytes();
        let mut bytes = Vec::with_capacity(u.len());
        let mut i = 0;
        while i < u.len() {
            if u[i] == 0x25 && i + 2 < u.len()
                && let (Some(h), Some(l)) = (hex_value(u[i + 1]), hex_value(u[i + 2])) {
                    bytes.push(h << 4 | l);
                    i += 3;
                    continue;
                }
            bytes.push(u[i]);
            i += 1;
        }
        t::utf8_lossy(&bytes)
    }

    /// Percent-encodes UTF-8 bytes except letters, digits, `-._~` and the ASCII in `keep`.
    pub fn percent_encode(s: &str, keep: &str) -> String {
        const DIGITS: &[u8] = b"0123456789ABCDEF";
        let mut out = String::new();
        for b in s.bytes() {
            if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') || keep.as_bytes().contains(&b) {
                out.push(b as char);
            } else {
                out.push('%');
                out.push(DIGITS[(b >> 4) as usize] as char);
                out.push(DIGITS[(b & 15) as usize] as char);
            }
        }
        out
    }

    /// `a=b&c=d` with keys lowercased and values percent-decoded.
    pub fn query_pairs(q: &str) -> Vec<(String, String)> {
        let mut out = Vec::new();
        for part in t::split_ch(q, '&', true) {
            match t::find_ch(part, '=') {
                Some(eq) => out.push((t::lower(&part[..eq]), percent_decode(&part[eq + t::char_len_at(part, eq)..]))),
                None => out.push((t::lower(part), String::new())),
            }
        }
        out
    }

    /// Splits at the first occurrence of `sep` (scalar-based).
    pub fn split_first(s: &str, sep: char) -> (String, Option<String>) {
        match s.find(sep) {
            Some(i) => (s[..i].to_string(), Some(s[i + sep.len_utf8()..].to_string())),
            None => (s.to_string(), None),
        }
    }

    /// Folds a content line at 75 octets; continuation lines begin with one space.
    pub fn fold(line: &str) -> String {
        let mut out = String::new();
        let mut cur = String::new();
        let mut n = 0;
        let mut limit = 75;
        for c in line.chars() {
            let w = c.len_utf8();
            if n + w > limit {
                out.push_str(&cur);
                out.push_str("\r\n ");
                cur.clear();
                n = 0;
                limit = 74;
            }
            cur.push(c);
            n += w;
        }
        out + &cur
    }
}

use stext::*;

// MARK: content lines

/// One unfolded vCard / iCalendar content line: `group.NAME;PARAM=v;FLAG:value`.
pub(crate) struct ContentLine {
    pub name: String,
    pub params: Vec<(String, String)>,
    pub raw_value: String,
}

impl ContentLine {
    pub fn param(&self, key: &str) -> Option<&str> {
        self.params.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }

    fn is_quoted_printable(&self) -> bool {
        self.params.iter().any(|(k, v)| k == "QUOTED-PRINTABLE" || (k == "ENCODING" && t::upper(v) == "QUOTED-PRINTABLE"))
    }

    fn decoded(&self, raw: &str) -> String {
        let s = if self.is_quoted_printable() { quoted_printable::decode(raw, self.param("CHARSET")) } else { raw.to_string() };
        normalize_newlines(&s)
    }

    pub fn text(&self) -> String {
        unescape_text(&self.decoded(&self.raw_value))
    }

    pub fn components(&self, sep: char) -> Vec<String> {
        split_unescaped(&self.raw_value, sep).iter().map(|c| unescape_text(&self.decoded(c))).collect()
    }
}

pub(crate) mod content_lines {
    use super::ContentLine;
    use super::stext::*;
    use crate::text as t;

    fn is_quoted_printable_open(line: &str) -> bool {
        if !line.ends_with('=') {
            return false;
        }
        let Some(colon) = t::find_ch(line, ':') else { return false };
        t::contains(&t::upper(&line[..colon]), "QUOTED-PRINTABLE")
    }

    /// Unfolds (CRLF or LF then space/tab) and joins quoted-printable soft line breaks.
    pub fn logical(text: &str) -> Vec<String> {
        let norm = normalize_newlines(text);
        let mut lines = Vec::new();
        let mut cur: Option<String> = None;
        for line in norm.split('\n') {
            if let Some(c) = &cur {
                if is_quoted_printable_open(c) {
                    cur = Some(t::drop_last(c, 1).to_string() + line);
                    continue;
                }
                if line.starts_with([' ', '\t']) {
                    cur = Some(c.clone() + t::drop_first(line, 1));
                    continue;
                }
            }
            if let Some(c) = cur.take() {
                lines.push(c);
            }
            cur = Some(line.to_string());
        }
        if let Some(c) = cur {
            lines.push(c);
        }
        lines
    }

    pub fn parse(line: &str) -> Option<ContentLine> {
        let sc: Vec<char> = line.chars().collect();
        let mut in_quote = false;
        let mut colon = None;
        for (i, &c) in sc.iter().enumerate() {
            if c == '"' {
                in_quote = !in_quote;
            } else if c == ':' && !in_quote {
                colon = Some(i);
                break;
            }
        }
        let ci = colon?;
        let mut parts: Vec<String> = Vec::new();
        let mut cur = String::new();
        in_quote = false;
        for &c in &sc[..ci] {
            if c == '"' {
                in_quote = !in_quote;
            }
            if c == ';' && !in_quote {
                parts.push(std::mem::take(&mut cur));
            } else {
                cur.push(c);
            }
        }
        parts.push(cur);
        let mut name = t::upper(t::trim_ws(&parts[0]));
        if let Some(dot) = t::find_ch(&name, '.') {
            name = name[dot + t::char_len_at(&name, dot)..].to_string();
        }
        let mut params = Vec::new();
        for p in &parts[1..] {
            let (k, v) = split_first(p, '=');
            let key = t::upper(t::trim_ws(&k));
            if key.is_empty() {
                continue;
            }
            let mut val = t::trim_ws(v.as_deref().unwrap_or("")).to_string();
            if t::count(&val) >= 2 && t::has_prefix(&val, "\"") && t::has_suffix(&val, "\"") {
                val = t::drop_last(t::drop_first(&val, 1), 1).to_string();
            }
            params.push((key, val));
        }
        Some(ContentLine { name, params, raw_value: sc[ci + 1..].iter().collect() })
    }
}

// MARK: quoted-printable

pub(crate) mod quoted_printable {
    use super::stext::hex_value;
    use crate::text as t;

    pub fn decode(s: &str, charset: Option<&str>) -> String {
        let u = s.as_bytes();
        let mut bytes = Vec::with_capacity(u.len());
        let mut i = 0;
        while i < u.len() {
            if u[i] == 0x3D && i + 2 < u.len()
                && let (Some(h), Some(l)) = (hex_value(u[i + 1]), hex_value(u[i + 2])) {
                    bytes.push(h << 4 | l);
                    i += 3;
                    continue;
                }
            bytes.push(u[i]);
            i += 1;
        }
        decode_bytes(&bytes, charset)
    }

    pub fn decode_bytes(bytes: &[u8], charset: Option<&str>) -> String {
        let cs = t::upper(charset.unwrap_or("")).replace('_', "-");
        match cs.as_str() {
            "ISO-8859-1" | "ISO8859-1" | "LATIN1" | "LATIN-1" | "US-ASCII" | "ASCII" => bytes.iter().map(|&b| b as char).collect(),
            "WINDOWS-1252" | "CP1252" | "WIN-1252" | "WINDOWS1252" => windows1252(bytes),
            "UTF-8" | "UTF8" => t::utf8_lossy(bytes),
            _ => match std::str::from_utf8(bytes) {
                Ok(s) => s.to_string(),
                // No usable charset and not UTF-8: the common legacy Windows encoding.
                Err(_) => {
                    if cs.is_empty() {
                        windows1252(bytes)
                    } else {
                        t::utf8_lossy(bytes)
                    }
                }
            },
        }
    }

    pub fn windows1252(bytes: &[u8]) -> String {
        bytes.iter().map(|&b| crate::peek::cp1252(b)).collect()
    }
}

// MARK: ICS dates

pub(crate) mod ics_date {
    use super::stext::trimmed;
    use crate::text as t;
    use crate::time;
    use std::time::SystemTime;

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub enum Kind {
        AllDay,
        Utc,
        Floating,
        Zoned(String),
    }

    pub fn wall_as_gmt(y: i64, mo: i64, d: i64, h: i64, mi: i64, s: i64) -> SystemTime {
        time::from_seconds((time::days_from_civil(y, mo, d) * 86400 + h * 3600 + mi * 60 + s) as f64)
    }

    pub fn format(date: SystemTime, kind: &Kind) -> String {
        let raw = time::seconds(date);
        let tt = if raw.is_finite() { raw.floor().clamp(-1e12, 1e12) as i64 } else { 0 };
        let (y, mo, d, h, mi, s) = time::gmt_fields(tt);
        let day = format!("{}{}{}", time::pad(y, 4), time::pad(mo, 2), time::pad(d, 2));
        if *kind == Kind::AllDay {
            return day;
        }
        let tm = format!("{day}T{}{}{}", time::pad(h, 2), time::pad(mi, 2), time::pad(s, 2));
        if *kind == Kind::Utc { tm + "Z" } else { tm }
    }

    fn number(s: &[char]) -> Option<i64> {
        if s.is_empty() {
            return None;
        }
        let mut n: i64 = 0;
        for &c in s {
            let d = c.to_digit(10).filter(|_| c.is_ascii_digit())?;
            n = n.checked_mul(10)?.checked_add(d as i64)?;
        }
        Some(n)
    }

    /// `20261110`, `20261110T090000`, `20261110T090000Z` (seconds optional).
    pub fn parse(value: &str, tzid: Option<&str>) -> Option<(SystemTime, Kind)> {
        let sc: Vec<char> = t::upper(trimmed(value)).chars().collect();
        if sc.len() < 8 {
            return None;
        }
        let (y, mo, d) = (number(&sc[0..4])?, number(&sc[4..6])?, number(&sc[6..8])?);
        if !(1..=12).contains(&mo) || d < 1 || d > time::days_in(y, mo) {
            return None;
        }
        if sc.len() == 8 {
            return Some((wall_as_gmt(y, mo, d, 0, 0, 0), Kind::AllDay));
        }
        if sc[8] != 'T' {
            return None;
        }
        let mut rest: Vec<char> = sc[9..].to_vec();
        let mut utc = false;
        if rest.last() == Some(&'Z') {
            utc = true;
            rest.pop();
        }
        if rest.len() != 4 && rest.len() != 6 {
            return None;
        }
        let (h, mi) = (number(&rest[0..2])?, number(&rest[2..4])?);
        if h > 23 || mi > 59 {
            return None;
        }
        let sec = if rest.len() == 6 { number(&rest[4..6])? } else { 0 };
        if sec > 60 {
            return None;
        }
        if utc {
            return Some((wall_as_gmt(y, mo, d, h, mi, sec), Kind::Utc));
        }
        if let Some(tzid) = tzid
            && let Ok(tz) = jiff::tz::TimeZone::get(tzid) {
                let dt = jiff::civil::DateTime::new(y as i16, mo as i8, d as i8, h as i8, mi as i8, sec.min(59) as i8, 0);
                if let Ok(dt) = dt
                    && let Ok(zoned) = tz.to_ambiguous_zoned(dt).compatible() {
                        let extra = if sec == 60 { 1.0 } else { 0.0 };
                        let at = time::from_seconds(zoned.timestamp().as_second() as f64 + extra);
                        let id = tz.iana_name().unwrap_or(tzid).to_string();
                        return Some((at, Kind::Zoned(id)));
                    }
            }
        Some((wall_as_gmt(y, mo, d, h, mi, sec), Kind::Floating))
    }

    /// `PnW` or `PnDTnHnMnS` in seconds; negative durations are not supported.
    pub fn duration(value: &str) -> Option<f64> {
        let sc: Vec<char> = t::upper(trimmed(value)).chars().collect();
        if sc.first() != Some(&'P') {
            return None;
        }
        let mut total = 0.0;
        let mut n: Option<i64> = None;
        let mut in_time = false;
        let mut saw_unit = false;
        for &c in &sc[1..] {
            if c.is_ascii_digit() {
                let next = n.unwrap_or(0) * 10 + (c as i64 - 48);
                if next >= 100_000_000 {
                    return None;
                }
                n = Some(next);
                continue;
            }
            if c == 'T' {
                in_time = true;
                continue;
            }
            let v = n.take()? as f64;
            saw_unit = true;
            total += match (c, in_time) {
                ('W', false) => v * 604_800.0,
                ('D', false) => v * 86_400.0,
                ('H', true) => v * 3_600.0,
                ('M', true) => v * 60.0,
                ('S', true) => v,
                _ => return None,
            };
        }
        (n.is_none() && saw_unit).then_some(total)
    }
}

// MARK: parsers

pub mod wifi {
    use super::stext::*;
    use crate::contract::WiFiInfo;
    use crate::text as t;

    /// `WIFI:T:WPA;S:name;P:pass;H:true;;`
    pub fn parse(text: &str) -> Option<WiFiInfo> {
        let p = prepared(text);
        let body = strip_prefix(&p, "WIFI:")?;
        let (mut ssid, mut security, mut password, mut eap, mut identity) = (None, None, None, None, None);
        let mut hidden = false;
        for (key, raw) in keyed(body) {
            let value = literal(&raw);
            match key.as_str() {
                "S" => {
                    if ssid.is_none() {
                        ssid = Some(value)
                    }
                }
                "T" => {
                    if security.is_none() {
                        security = non_empty(Some(&value)).map(|s| t::upper(&s))
                    }
                }
                "P" => {
                    if password.is_none() && !value.is_empty() {
                        password = Some(value)
                    }
                }
                "H" => hidden = hidden || t::lower(t::trim_ws(&value)) == "true",
                "E" => {
                    if eap.is_none() {
                        eap = non_empty(Some(&value))
                    }
                }
                "I"
                    if identity.is_none() => {
                        identity = non_empty(Some(&value))
                    }
                _ => {}
            }
        }
        let ssid = ssid.filter(|s| !s.is_empty())?;
        Some(WiFiInfo { ssid, security, password, is_hidden: hidden, eap_method: eap, identity })
    }

    /// A value wrapped in double quotes is literal text; otherwise unescape.
    fn literal(raw: &str) -> String {
        let sc: Vec<char> = raw.chars().collect();
        if sc.len() >= 2 && sc[0] == '"' && sc[sc.len() - 1] == '"' {
            return unescape_backslash(&sc[1..sc.len() - 1].iter().collect::<String>());
        }
        unescape_backslash(raw)
    }
}

pub mod contact {
    use super::stext::*;
    use super::{mecard, vcard};
    use crate::contract::ContactInfo;

    /// vCard 2.1/3.0/4.0 and MeCard.
    pub fn parse(text: &str) -> Option<ContactInfo> {
        let p = prepared(text);
        if let Some(body) = strip_prefix(&p, "MECARD:") {
            return mecard(body);
        }
        if strip_prefix(&p, "BEGIN:VCARD").is_some() {
            return vcard(&p);
        }
        None
    }
}

fn mecard(body: &str) -> Option<ContactInfo> {
    let mut info = ContactInfo::default();
    let mut name: Option<String> = None;
    for (key, raw) in keyed(body) {
        let value = || unescape_backslash(&raw);
        match key.as_str() {
            "N" => {
                if name.is_some() || info.family_name.is_some() {
                    continue;
                }
                let parts: Vec<String> = split_unescaped(&raw, ',').iter().map(|p| unescape_backslash(p)).collect();
                if parts.len() >= 2 {
                    info.family_name = non_empty(Some(&parts[0]));
                    info.given_name = non_empty(Some(&parts[1]));
                } else {
                    name = non_empty(Some(&value()));
                }
            }
            "TEL" => info.phones.extend(non_empty(Some(&value()))),
            "EMAIL" => info.emails.extend(non_empty(Some(&value()))),
            "URL" => info.urls.extend(non_empty(Some(&value()))),
            "ADR" => {
                let parts: Vec<String> =
                    split_unescaped(&raw, ',').iter().filter_map(|p| non_empty(Some(&unescape_backslash(p)))).collect();
                if !parts.is_empty() {
                    info.addresses.push(parts.join("\n"));
                }
            }
            "NOTE" => {
                if info.note.is_none() {
                    info.note = non_empty(Some(&value()))
                }
            }
            "BDAY" => {
                if info.birthday.is_none() {
                    info.birthday = non_empty(Some(&value()))
                }
            }
            "ORG" => {
                if info.organization.is_none() {
                    info.organization = non_empty(Some(&value()))
                }
            }
            "TITLE"
                if info.title.is_none() => {
                    info.title = non_empty(Some(&value()))
                }
            _ => {}
        }
    }
    info.name = name.or_else(|| non_empty(Some(&joined_name(&info))));
    info.links = info.urls.iter().take(10).cloned().collect();
    Some(info)
}

fn joined_name(info: &ContactInfo) -> String {
    [&info.given_name, &info.family_name].iter().filter_map(|v| v.as_deref()).collect::<Vec<_>>().join(" ")
}

fn vcard(text: &str) -> Option<ContactInfo> {
    let mut started = false;
    let mut info = ContactInfo::default();
    let mut fn_: Option<String> = None;
    for raw in content_lines::logical(text) {
        let Some(line) = content_lines::parse(&raw) else { continue };
        let marker = t::upper(t::trim_ws(&line.raw_value));
        if line.name == "BEGIN" {
            if marker == "VCARD" {
                started = true;
            }
            continue;
        }
        if !started {
            continue;
        }
        if line.name == "END" {
            if marker == "VCARD" {
                break;
            }
            continue;
        }
        match line.name.as_str() {
            "FN" => {
                if fn_.is_none() {
                    fn_ = non_empty(Some(&line.text()))
                }
            }
            "N" => {
                let c = line.components(';');
                if info.family_name.is_none() {
                    info.family_name = non_empty(c.first().map(String::as_str));
                }
                if info.given_name.is_none() && c.len() > 1 {
                    info.given_name = non_empty(Some(&c[1]));
                }
            }
            "ORG" => {
                if info.organization.is_none() {
                    info.organization = non_empty(line.components(';').first().map(String::as_str))
                }
            }
            "TITLE" => {
                if info.title.is_none() {
                    info.title = non_empty(Some(&line.text()))
                }
            }
            "TEL" => {
                if let Some(v) = non_empty(Some(&line.text())) {
                    info.phones.push(strip_prefix(&v, "tel:").map(|r| trimmed(r).to_string()).unwrap_or(v));
                }
            }
            "EMAIL" => info.emails.extend(non_empty(Some(&line.text()))),
            "URL" => info.urls.extend(non_empty(Some(&line.text()))),
            "ADR" => {
                let parts: Vec<String> = line.components(';').iter().take(7).filter_map(|p| non_empty(Some(p))).collect();
                if !parts.is_empty() {
                    info.addresses.push(parts.join("\n"));
                }
            }
            "NOTE" => {
                if info.note.is_none() {
                    info.note = non_empty(Some(&line.text()))
                }
            }
            "BDAY"
                if info.birthday.is_none() => {
                    info.birthday = non_empty(Some(&line.text()))
                }
            _ => {}
        }
    }
    if !started {
        return None;
    }
    info.name = fn_.or_else(|| non_empty(Some(&joined_name(&info))));
    info.links = info.urls.iter().take(10).cloned().collect();
    Some(info)
}

pub mod event {
    use super::stext::*;
    use super::{ContentLine, content_lines, ics_date};
    use crate::contract::EventInfo;
    use crate::text as t;
    use crate::time;

    /// BEGIN:VEVENT, or a VCALENDAR containing one (the first VEVENT).
    pub fn parse(text: &str) -> Option<EventInfo> {
        let p = prepared(text);
        if strip_prefix(&p, "BEGIN:VEVENT").is_none() && strip_prefix(&p, "BEGIN:VCALENDAR").is_none() {
            return None;
        }
        let mut in_event = false;
        let mut depth = 0;
        let mut info = EventInfo::default();
        let (mut start_line, mut end_line, mut duration): (Option<ContentLine>, Option<ContentLine>, Option<String>) =
            (None, None, None);
        for raw in content_lines::logical(&p) {
            let Some(line) = content_lines::parse(&raw) else { continue };
            let marker = t::upper(t::trim_ws(&line.raw_value));
            if !in_event {
                if line.name == "BEGIN" && marker == "VEVENT" {
                    in_event = true;
                }
                continue;
            }
            if line.name == "BEGIN" {
                depth += 1;
                continue;
            }
            if line.name == "END" {
                if depth == 0 {
                    break;
                }
                depth -= 1;
                continue;
            }
            if depth > 0 {
                continue;
            }
            match line.name.as_str() {
                "SUMMARY" => {
                    if info.summary.is_none() {
                        info.summary = non_empty(Some(&line.text()))
                    }
                }
                "LOCATION" => {
                    if info.location.is_none() {
                        info.location = non_empty(Some(&line.text()))
                    }
                }
                "DESCRIPTION" => {
                    if info.description.is_none() {
                        info.description = non_empty(Some(&line.text()))
                    }
                }
                "URL" => {
                    if info.url.is_none() {
                        info.url = non_empty(Some(&line.text()))
                    }
                }
                "DTSTART" => {
                    if start_line.is_none() {
                        start_line = Some(line)
                    }
                }
                "DTEND" => {
                    if end_line.is_none() {
                        end_line = Some(line)
                    }
                }
                "DURATION"
                    if duration.is_none() => {
                        duration = Some(line.raw_value.clone())
                    }
                _ => {}
            }
        }
        if !in_event {
            return None;
        }
        if let Some(l) = &start_line
            && let Some((date, kind)) = ics_date::parse(&l.raw_value, l.param("TZID")) {
                info.start = Some(date);
                match kind {
                    ics_date::Kind::AllDay => info.is_all_day = true,
                    ics_date::Kind::Floating => info.is_floating = true,
                    ics_date::Kind::Zoned(id) => info.time_zone_id = Some(id),
                    ics_date::Kind::Utc => {}
                }
            }
        if let Some(e) = end_line.as_ref().and_then(|l| ics_date::parse(&l.raw_value, l.param("TZID"))) {
            info.end = Some(e.0);
        } else if let (Some(start), Some(d)) = (info.start, duration.as_deref().and_then(ics_date::duration)) {
            info.end = Some(time::from_seconds(time::seconds(start) + d));
        }
        if let Some(u) = &info.url {
            info.links = vec![u.clone()];
        }
        Some(info)
    }
}

pub mod phone {
    use super::stext::*;
    use crate::contract::PhoneInfo;

    /// tel:
    pub fn parse(text: &str) -> Option<PhoneInfo> {
        let p = prepared(text);
        let rest = strip_prefix(&p, "tel:")?;
        Some(PhoneInfo { number: non_empty(Some(&percent_decode(rest)))? })
    }
}

pub mod sms {
    use super::stext::*;
    use crate::contract::SmsInfo;

    /// sms: / smsto: / SMSTO:
    pub fn parse(text: &str) -> Option<SmsInfo> {
        let p = prepared(text);
        if let Some(rest) = strip_prefix(&p, "smsto:") {
            // SMSTO:number:body. The body is raw text (not percent-decoded) and may contain colons.
            let (num, body) = split_first(rest, ':');
            let number = non_empty(Some(&percent_decode(&num)))?;
            return Some(SmsInfo { number, body: body.filter(|b| !b.is_empty()), links: vec![] });
        }
        let rest = strip_prefix(&p, "sms:")?;
        let r = trimmed(rest);
        let cut = r.find(['?', '&']).unwrap_or(r.len());
        let number = non_empty(Some(&percent_decode(&r[..cut])))?;
        let mut body = None;
        if cut < r.len() {
            body = query_pairs(&r[cut + 1..]).into_iter().find(|(k, _)| k == "body").map(|(_, v)| v).filter(|v| !v.is_empty());
        }
        Some(SmsInfo { number, body, links: vec![] })
    }
}

pub mod email {
    use super::stext::*;
    use crate::contract::EmailInfo;
    use crate::text as t;

    /// mailto: / MATMSG:
    pub fn parse(text: &str) -> Option<EmailInfo> {
        let p = prepared(text);
        if let Some(rest) = strip_prefix(&p, "mailto:") {
            return mailto(trimmed(rest));
        }
        if let Some(body) = strip_prefix(&p, "MATMSG:") {
            return matmsg(body);
        }
        None
    }

    fn first_address(list: &str) -> Option<String> {
        let first = t::split_ch(list, ',', true).first().map(|s| s.to_string()).unwrap_or_default();
        non_empty(Some(&percent_decode(&first)))
    }

    fn mailto(rest: &str) -> Option<EmailInfo> {
        let (path, query) = split_first(rest, '?');
        let pairs = query.map(|q| query_pairs(&q)).unwrap_or_default();
        let get = |k: &str| pairs.iter().find(|(key, v)| key == k && !v.is_empty()).map(|(_, v)| v.clone());
        let address = first_address(&path)
            .or_else(|| get("to").and_then(|to| non_empty(t::split_ch(&to, ',', true).first().copied())))?;
        Some(EmailInfo { address, subject: get("subject"), body: get("body"), links: vec![] })
    }

    fn matmsg(body: &str) -> Option<EmailInfo> {
        let (mut to, mut sub, mut text) = (None, None, None);
        for (key, raw) in keyed(body) {
            let v = unescape_backslash(&raw);
            match key.as_str() {
                "TO" => {
                    if to.is_none() {
                        to = non_empty(Some(&v))
                    }
                }
                "SUB" => {
                    if sub.is_none() && !v.is_empty() {
                        sub = Some(v)
                    }
                }
                "BODY"
                    if text.is_none() && !v.is_empty() => {
                        text = Some(v)
                    }
                _ => {}
            }
        }
        Some(EmailInfo { address: to?, subject: sub, body: text, links: vec![] })
    }
}

pub mod geo {
    use super::stext::*;
    use crate::contract::GeoInfo;
    use crate::text as t;

    /// geo:lat,lon[,alt][;params][?q=…]
    pub fn parse(text: &str) -> Option<GeoInfo> {
        let p = prepared(text);
        let rest = strip_prefix(&p, "geo:")?;
        let (path, query) = split_first(trimmed(rest), '?');
        let coord_text = split_first(&path, ';').0;
        let coords: Vec<String> = t::split_ch(&coord_text, ',', false).iter().map(|c| t::trim_ws(c).to_string()).collect();
        if !(coords.len() == 2 || coords.len() == 3) || !coords.iter().all(|c| is_decimal(c)) {
            return None;
        }
        let lat: f64 = coords[0].parse().ok()?;
        let lon: f64 = coords[1].parse().ok()?;
        if lat.abs() > 90.0 || lon.abs() > 180.0 {
            return None;
        }
        let q = query.and_then(|q| query_pairs(&q).into_iter().find(|(k, v)| k == "q" && !v.is_empty()).map(|(_, v)| v));
        Some(GeoInfo {
            latitude: coords[0].clone(),
            longitude: coords[1].clone(),
            altitude: if coords.len() == 3 { Some(coords[2].clone()) } else { None },
            query: q,
        })
    }

    /// `-12.5`, `+3`, `0.25`: sign, digits, optional fraction. No exponent, no NaN.
    fn is_decimal(s: &str) -> bool {
        let mut digits = 0;
        let mut dot = false;
        for (i, c) in s.chars().enumerate() {
            if c.is_ascii_digit() {
                digits += 1;
            } else if c == '.' && !dot {
                dot = true;
            } else if (c == '-' || c == '+') && i == 0 {
                continue;
            } else {
                return false;
            }
        }
        digits > 0
    }
}

// MARK: encoder

/// The text to put in a generated code. Round-trips through the parsers in this module.
pub fn encode(r: &MakeRequest) -> String {
    match r {
        MakeRequest::Link { text } => trimmed(text).to_string(),
        MakeRequest::Text { text } => text.clone(),
        MakeRequest::Wifi { info } => encode_wifi(info),
        MakeRequest::Contact { info } => v_card(info),
        MakeRequest::Event { info } => v_event(info),
        MakeRequest::Email { info } => encode_email(info),
        MakeRequest::Sms { info } => encode_sms(info),
        MakeRequest::Phone { number } => format!("TEL:{}", percent_encode(trimmed(number), NUMBER_KEEP)),
        MakeRequest::Geo { info } => encode_geo(info),
    }
}

/// The text to put in a generated code.
#[uniffi::export]
pub fn payload_encode(request: MakeRequest) -> String {
    encode(&request)
}

/// Characters a phone number keeps readable; everything else is percent-encoded.
const NUMBER_KEEP: &str = "+()*#,;=/ ";

fn encode_wifi(w: &WiFiInfo) -> String {
    let mut s = String::from("WIFI:");
    if let Some(sec) = non_empty(w.security.as_deref()) {
        let v = if t::upper(&sec) == "NOPASS" { "nopass".to_string() } else { escape_backslash(&sec, ";,:\"") };
        s += &format!("T:{v};");
    }
    s += &format!("S:{};", wifi_value(&w.ssid));
    if let Some(p) = w.password.as_deref().filter(|p| !p.is_empty()) {
        s += &format!("P:{};", wifi_value(p));
    }
    if let Some(e) = non_empty(w.eap_method.as_deref()) {
        s += &format!("E:{};", escape_backslash(&e, ";,:\""));
    }
    if let Some(i) = non_empty(w.identity.as_deref()) {
        s += &format!("I:{};", escape_backslash(&i, ";,:\""));
    }
    if w.is_hidden {
        s += "H:true;";
    }
    s + ";"
}

/// Escaped; an all-hex value is quoted so scanners don't read it as hex bytes.
fn wifi_value(v: &str) -> String {
    let hex = !v.is_empty() && v.len().is_multiple_of(2) && v.bytes().all(|b| hex_value(b).is_some());
    if hex { format!("\"{v}\"") } else { escape_backslash(v, ";,:\"") }
}

fn encode_email(e: &EmailInfo) -> String {
    let mut s = format!("mailto:{}", percent_encode(trimmed(&e.address), "@+"));
    let mut q = Vec::new();
    if let Some(v) = e.subject.as_deref().filter(|v| !v.is_empty()) {
        q.push(format!("subject={}", percent_encode(v, "")));
    }
    if let Some(v) = e.body.as_deref().filter(|v| !v.is_empty()) {
        q.push(format!("body={}", percent_encode(v, "")));
    }
    if !q.is_empty() {
        s += &format!("?{}", q.join("&"));
    }
    s
}

fn encode_sms(m: &SmsInfo) -> String {
    let number = percent_encode(trimmed(&m.number), NUMBER_KEEP);
    match m.body.as_deref().filter(|b| !b.is_empty()) {
        Some(b) => format!("SMSTO:{number}:{b}"),
        None => format!("sms:{number}"),
    }
}

fn encode_geo(g: &GeoInfo) -> String {
    let mut s = format!("geo:{},{}", trimmed(&g.latitude), trimmed(&g.longitude));
    if let Some(a) = non_empty(g.altitude.as_deref()) {
        s += &format!(",{a}");
    }
    if let Some(q) = g.query.as_deref().filter(|q| !q.is_empty()) {
        s += &format!("?q={}", percent_encode(q, ""));
    }
    s
}

/// vCard 3.0, CRLF line endings, lines folded at 75 octets.
fn v_card(c: &ContactInfo) -> String {
    let esc = escape_text;
    let mut lines = vec!["BEGIN:VCARD".to_string(), "VERSION:3.0".to_string()];
    lines.push(format!(
        "N:{};{};;;",
        esc(c.family_name.as_deref().unwrap_or("")),
        esc(c.given_name.as_deref().unwrap_or(""))
    ));
    let fn_ = non_empty(c.name.as_deref())
        .or_else(|| non_empty(Some(&joined_name(c))))
        .or_else(|| non_empty(c.organization.as_deref()));
    if let Some(f) = fn_ {
        lines.push(format!("FN:{}", esc(&f)));
    }
    if let Some(o) = non_empty(c.organization.as_deref()) {
        lines.push(format!("ORG:{}", esc(&o)));
    }
    if let Some(x) = non_empty(c.title.as_deref()) {
        lines.push(format!("TITLE:{}", esc(&x)));
    }
    lines.extend(c.phones.iter().filter(|p| !p.is_empty()).map(|p| format!("TEL:{}", esc(p))));
    lines.extend(c.emails.iter().filter(|p| !p.is_empty()).map(|p| format!("EMAIL:{}", esc(p))));
    lines.extend(c.urls.iter().filter(|p| !p.is_empty()).map(|p| format!("URL:{}", esc(p))));
    lines.extend(c.addresses.iter().filter(|p| !p.is_empty()).map(|p| format!("ADR:;;{};;;;", esc(p))));
    if let Some(n) = non_empty(c.note.as_deref()) {
        lines.push(format!("NOTE:{}", esc(&n)));
    }
    if let Some(b) = non_empty(c.birthday.as_deref()) {
        lines.push(format!("BDAY:{}", esc(&b)));
    }
    lines.push("END:VCARD".into());
    lines.iter().map(|l| fold(l)).collect::<Vec<_>>().join("\r\n")
}

/// A bare VEVENT. Absolute times are UTC, floating times have no `Z`, all-day times are
/// `VALUE=DATE`.
fn v_event(e: &EventInfo) -> String {
    use ics_date::Kind;
    let esc = escape_text;
    let mut lines = vec!["BEGIN:VEVENT".to_string()];
    if let Some(s) = non_empty(e.summary.as_deref()) {
        lines.push(format!("SUMMARY:{}", esc(&s)));
    }
    let kind = if e.is_all_day { Kind::AllDay } else if e.is_floating { Kind::Floating } else { Kind::Utc };
    let stamp = |name: &str, d| {
        let head = if kind == Kind::AllDay { format!("{name};VALUE=DATE:") } else { format!("{name}:") };
        head + &ics_date::format(d, &kind)
    };
    if let Some(d) = e.start {
        lines.push(stamp("DTSTART", d));
    }
    if let Some(d) = e.end {
        lines.push(stamp("DTEND", d));
    }
    if let Some(v) = non_empty(e.location.as_deref()) {
        lines.push(format!("LOCATION:{}", esc(&v)));
    }
    if let Some(v) = non_empty(e.description.as_deref()) {
        lines.push(format!("DESCRIPTION:{}", esc(&v)));
    }
    if let Some(v) = non_empty(e.url.as_deref()) {
        lines.push(format!("URL:{}", esc(&v)));
    }
    lines.push("END:VEVENT".into());
    lines.iter().map(|l| fold(l)).collect::<Vec<_>>().join("\r\n")
}

/// Wall-clock time read as if it were GMT (what floating and all-day event times hold).
#[uniffi::export]
pub fn ics_wall_as_gmt(year: i64, month: i64, day: i64, hour: i64, minute: i64, second: i64) -> std::time::SystemTime {
    ics_date::wall_as_gmt(year, month, day, hour, minute, second)
}

#[uniffi::export]
pub fn wifi_parse(text: String) -> Option<WiFiInfo> {
    wifi::parse(&text)
}

#[uniffi::export]
pub fn contact_parse(text: String) -> Option<ContactInfo> {
    contact::parse(&text)
}

#[uniffi::export]
pub fn event_parse(text: String) -> Option<EventInfo> {
    event::parse(&text)
}

#[uniffi::export]
pub fn phone_parse(text: String) -> Option<PhoneInfo> {
    phone::parse(&text)
}

#[uniffi::export]
pub fn sms_parse(text: String) -> Option<SmsInfo> {
    sms::parse(&text)
}

#[uniffi::export]
pub fn email_parse(text: String) -> Option<EmailInfo> {
    email::parse(&text)
}

#[uniffi::export]
pub fn geo_parse(text: String) -> Option<GeoInfo> {
    geo::parse(&text)
}
