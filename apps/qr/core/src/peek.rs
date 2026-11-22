//! Peek's pure half (QRCore `Peek/`): decoding and sniffing a page, RDAP bootstrap and
//! responses, domain age, and the report that turns hops and a page into signals (SPEC §6). The
//! fetching itself stays native.

use crate::analyzer::{Analyzer, level_for};
use crate::checks;
use crate::contract::*;
use crate::data::{BundledData, RdapFile};
use crate::known::KnownAddresses;
use crate::text as t;
use crate::time;
use crate::url::WebAddress;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::SystemTime;

// MARK: charset

const CP1252_HIGH: [u32; 32] = [
    0x20AC, 0x81, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039,
    0x0152, 0x8D, 0x017D, 0x8F, 0x90, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
    0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x9D, 0x017E, 0x0178,
];

/// One windows-1252 byte as a scalar.
pub fn cp1252(b: u8) -> char {
    let v = if (0x80..=0x9F).contains(&b) {
        CP1252_HIGH[(b - 0x80) as usize]
    } else {
        b as u32
    };
    char::from_u32(v).unwrap_or('\u{FFFD}')
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Encoding {
    Utf8,
    Windows1252,
    Utf16Le,
    Utf16Be,
}

/// First index at or after `from` where `needle` occurs in `hay`.
pub fn find_bytes(needle: &[u8], hay: &[u8], from: usize) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() || from > hay.len() - needle.len() {
        return None;
    }
    hay[from..]
        .windows(needle.len())
        .position(|w| w == needle)
        .map(|p| p + from)
}

pub mod charset {
    use super::{Encoding, cp1252, find_bytes};
    use crate::text as t;

    /// Byte-to-text decoding. Only encodings that behave the same on every platform.
    pub fn decode(bytes: &[u8], content_type: Option<&str>) -> String {
        let (body, encoding) = if bytes.starts_with(&[0xEF, 0xBB, 0xBF]) {
            (&bytes[3..], Encoding::Utf8)
        } else if bytes.starts_with(&[0xFF, 0xFE]) {
            (&bytes[2..], Encoding::Utf16Le)
        } else if bytes.starts_with(&[0xFE, 0xFF]) {
            (&bytes[2..], Encoding::Utf16Be)
        } else if let Some(label) = content_type.and_then(in_header).or_else(|| meta(bytes)) {
            (bytes, encoding_for(&label))
        } else {
            (bytes, Encoding::Utf8)
        };
        match encoding {
            Encoding::Utf8 => t::utf8_lossy(body),
            Encoding::Windows1252 => body.iter().map(|&b| cp1252(b)).collect(),
            Encoding::Utf16Le | Encoding::Utf16Be => {
                let units = body.as_chunks::<2>().0.iter().map(|p| {
                    if encoding == Encoding::Utf16Be {
                        (p[0] as u16) << 8 | p[1] as u16
                    } else {
                        (p[1] as u16) << 8 | p[0] as u16
                    }
                });
                char::decode_utf16(units)
                    .map(|r| r.unwrap_or('\u{FFFD}'))
                    .collect()
            }
        }
    }

    pub fn encoding_for(raw: &str) -> Encoding {
        match t::lower(raw).as_str() {
            "utf-8" | "utf8" | "unicode-1-1-utf-8" => Encoding::Utf8,
            // The HTML standard decodes these as windows-1252.
            "iso-8859-1" | "iso8859-1" | "iso_8859-1" | "latin1" | "latin-1" | "l1"
            | "us-ascii" | "ascii" | "windows-1252" | "cp1252" | "x-cp1252" => {
                Encoding::Windows1252
            }
            "utf-16" | "utf-16le" | "ucs-2" => Encoding::Utf16Le,
            "utf-16be" => Encoding::Utf16Be,
            _ => Encoding::Utf8,
        }
    }

    /// `charset=` in a Content-Type value.
    pub fn in_header(header: &str) -> Option<String> {
        token_after(b"charset", t::lower(header).as_bytes())
    }

    /// `<meta charset=…>` or `<meta http-equiv=Content-Type content="…charset=…">` in the first 1024
    /// bytes.
    pub fn meta(bytes: &[u8]) -> Option<String> {
        let head: Vec<u8> = bytes
            .iter()
            .take(1024)
            .map(u8::to_ascii_lowercase)
            .collect();
        let mut i = 0;
        while let Some(at) = find_bytes(b"<meta", &head, i) {
            let end = head[at..]
                .iter()
                .position(|&b| b == b'>')
                .map_or(head.len(), |p| p + at);
            if let Some(tk) = token_after(b"charset", &head[at..end]) {
                return Some(tk);
            }
            i = at + 5;
        }
        None
    }

    fn token_after(key: &[u8], hay: &[u8]) -> Option<String> {
        let at = find_bytes(key, hay, 0)?;
        let mut i = at + key.len();
        while i < hay.len() && (hay[i] == b' ' || hay[i] == b'\t') {
            i += 1;
        }
        if i >= hay.len() || hay[i] != b'=' {
            return None;
        }
        i += 1;
        while i < hay.len() && (hay[i] == b' ' || hay[i] == b'\t') {
            i += 1;
        }
        if i < hay.len() && (hay[i] == b'"' || hay[i] == b'\'') {
            i += 1;
        }
        let start = i;
        while i < hay.len()
            && (hay[i].is_ascii_lowercase()
                || hay[i].is_ascii_digit()
                || hay[i] == b'-'
                || hay[i] == b'_')
        {
            i += 1;
        }
        (i > start).then(|| String::from_utf8_lossy(&hay[start..i]).into_owned())
    }
}

// MARK: entities

pub mod entities {
    use crate::text as t;

    /// Decodes `&amp; &lt; &gt; &quot; &apos; &nbsp;` and numeric references. Anything else, or a
    /// reference without `;`, stays as written.
    pub fn decode(b: &[u8]) -> String {
        if !b.contains(&b'&') {
            return t::utf8_lossy(b);
        }
        let mut out = Vec::with_capacity(b.len());
        let mut i = 0;
        while i < b.len() {
            if b[i] == b'&'
                && let Some((c, next)) = reference(b, i)
            {
                let mut buf = [0u8; 4];
                out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                i = next;
                continue;
            }
            out.push(b[i]);
            i += 1;
        }
        t::utf8_lossy(&out)
    }

    fn reference(b: &[u8], start: usize) -> Option<(char, usize)> {
        let mut end = start + 1;
        let limit = b.len().min(start + 12);
        while end < limit && b[end] != b';' && b[end] != b'&' && b[end] != b' ' {
            end += 1;
        }
        if end >= b.len() || b[end] != b';' || end <= start + 1 {
            return None;
        }
        let name = t::utf8_lossy(&b[start + 1..end]);
        let c = match name.as_str() {
            "amp" => '&',
            "lt" => '<',
            "gt" => '>',
            "quot" => '"',
            "apos" => '\'',
            "nbsp" => '\u{A0}',
            _ => {
                let v = if name.starts_with("#x") || name.starts_with("#X") {
                    t::parse_uint(&name[2..], 16)
                } else if let Some(d) = name.strip_prefix('#') {
                    t::parse_uint(d, 10)
                } else {
                    None
                };
                v.filter(|&v| v <= u32::MAX as u64)
                    .and_then(|v| char::from_u32(v as u32))?
            }
        };
        Some((c, end + 1))
    }
}

// MARK: script redirect

/// Decides whether inline script *navigates* (SPEC §6.1): an assignment to `location` or to
/// `location.href`, or a call to `location.replace` / `location.assign`, with optional `window.`,
/// `document.`, `top.`, `self.`, `parent.` in front, dot or bracket access, optional spaces.
pub mod script_redirect {
    const WORD: &[u8] = b"location";
    const ROOTS: &[&[u8]] = &[
        b"window",
        b"document",
        b"top",
        b"self",
        b"parent",
        b"frames",
        b"globalthis",
    ];
    const MAX_SKIP: usize = 64;

    fn is_ident(c: u8) -> bool {
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_' || c == b'$' || c >= 0x80
    }
    fn is_space(c: u8) -> bool {
        matches!(c, b' ' | b'\t' | b'\n' | b'\r' | 0x0C)
    }

    /// `s` must already be ASCII-lowercased.
    pub fn contains(s: &[u8]) -> bool {
        let w = WORD.len();
        if s.len() < w {
            return false;
        }
        (0..=s.len() - w).any(|i| &s[i..i + w] == WORD && is_standalone(s, i) && assigns(s, i + w))
    }

    fn is_standalone(t: &[u8], i: usize) -> bool {
        let at = |j: isize| t[j as usize];
        let mut j = i as isize - 1;
        if j >= 0 && is_ident(at(j)) {
            return false;
        }
        let mut skipped = 0;
        while j >= 0 && is_space(at(j)) && skipped < MAX_SKIP {
            j -= 1;
            skipped += 1;
        }
        if !(j >= 0 && at(j) == b'.') {
            return true;
        }
        j -= 1;
        while j >= 0 && is_space(at(j)) && skipped < MAX_SKIP {
            j -= 1;
            skipped += 1;
        }
        let end = j + 1;
        while j >= 0 && is_ident(at(j)) && end - j <= 12 {
            j -= 1;
        }
        let len = end - (j + 1);
        if len <= 0 || len > 10 {
            return false;
        }
        if j >= 0 && at(j) == b'.' {
            return false;
        }
        ROOTS.contains(&&t[(j + 1) as usize..end as usize])
    }

    fn skip_spaces(t: &[u8], i: usize) -> usize {
        let (mut j, mut k) = (i, 0);
        while j < t.len() && is_space(t[j]) && k < MAX_SKIP {
            j += 1;
            k += 1;
        }
        j
    }

    /// `= value` but not `==`, `===` or `=>`.
    fn is_assignment(t: &[u8], i: usize) -> bool {
        i < t.len() && t[i] == b'=' && !(i + 1 < t.len() && (t[i + 1] == b'=' || t[i + 1] == b'>'))
    }

    fn assigns(t: &[u8], start: usize) -> bool {
        let i = skip_spaces(t, start);
        if i >= t.len() {
            return false;
        }
        if is_assignment(t, i) {
            return true;
        }
        let name: &[u8];
        let mut j;
        if t[i] == b'.' {
            j = skip_spaces(t, i + 1);
            let s = j;
            while j < t.len() && is_ident(t[j]) && j - s <= 8 {
                j += 1;
            }
            name = &t[s..j];
        } else if t[i] == b'[' {
            j = skip_spaces(t, i + 1);
            if !(j < t.len() && matches!(t[j], b'"' | b'\'' | b'`')) {
                return false;
            }
            let quote = t[j];
            let s = j + 1;
            j = s;
            while j < t.len() && t[j] != quote && j - s <= 8 {
                j += 1;
            }
            if !(j < t.len() && t[j] == quote) {
                return false;
            }
            name = &t[s..j];
            j = skip_spaces(t, j + 1);
            if !(j < t.len() && t[j] == b']') {
                return false;
            }
            j += 1;
        } else {
            return false;
        }
        j = skip_spaces(t, j);
        match name {
            b"href" => is_assignment(t, j),
            b"replace" | b"assign" => j < t.len() && t[j] == b'(',
            _ => false,
        }
    }
}

// MARK: sniffer

#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct HtmlInput {
    pub input_type: Option<String>,
    pub name: Option<String>,
    pub id: Option<String>,
    pub autocomplete: Option<String>,
    pub placeholder: Option<String>,
}

/// What a forgiving tag scan finds in a page. Not a DOM.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Default, uniffi::Record)]
pub struct HtmlSniff {
    /// Entities decoded, whitespace collapsed, at most 200 characters.
    pub title: Option<String>,
    /// Every `<form action>` as written (None for a form without action).
    pub form_actions: Vec<Option<String>>,
    pub inputs: Vec<HtmlInput>,
    /// The URL part of a `<meta http-equiv="refresh">`, if any.
    pub meta_refresh_url: Option<String>,
    /// Inline script assigns `location` / `location.href` or calls `location.replace` / `assign`.
    pub script_redirect: bool,
    pub script_count: i64,
    /// Characters of visible text (outside tags, script and style), whitespace collapsed.
    pub visible_text_length: i64,
}

/// Largest body looked at; the rest is ignored.
pub const SNIFF_MAX_BYTES: usize = 512 * 1024;

/// Decodes by the header `charset`, else a `<meta>` charset, else UTF-8 with replacement.
pub fn sniff(body: &[u8], content_type: Option<&str>) -> HtmlSniff {
    let bytes = &body[..body.len().min(SNIFF_MAX_BYTES)];
    let text = charset::decode(bytes, content_type);
    Scanner::new(text.as_bytes()).scan()
}

#[uniffi::export]
pub fn html_sniff(body: Vec<u8>, content_type: Option<String>) -> HtmlSniff {
    sniff(&body, content_type.as_deref())
}

struct Scanner<'a> {
    b: &'a [u8],
    lower: Vec<u8>,
    result: HtmlSniff,
    visible: i64,
    last_was_space: bool,
}

fn is_space(c: u8) -> bool {
    matches!(c, b' ' | b'\t' | b'\n' | b'\r' | 0x0C)
}
fn is_name_byte(c: u8) -> bool {
    c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-' || c == b':'
}
fn html_whitespace(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{0C}' | '\u{A0}') || c.is_whitespace()
}

impl<'a> Scanner<'a> {
    fn new(b: &'a [u8]) -> Self {
        Scanner {
            b,
            lower: b.iter().map(u8::to_ascii_lowercase).collect(),
            result: HtmlSniff::default(),
            visible: 0,
            last_was_space: true,
        }
    }

    fn scan(mut self) -> HtmlSniff {
        let b = self.b;
        let n = b.len();
        let mut i = 0;
        while i < n {
            if b[i] != b'<' {
                let end = b[i..].iter().position(|&c| c == b'<').map_or(n, |p| p + i);
                self.add_text(&b[i..end]);
                i = end;
                continue;
            }
            if i + 3 < n && b[i + 1] == b'!' && b[i + 2] == b'-' && b[i + 3] == b'-' {
                i = find_bytes(b"-->", b, i + 4).map_or(n, |p| p + 3);
                continue;
            }
            if i + 1 < n && (b[i + 1] == b'!' || b[i + 1] == b'?') {
                i = b[i..]
                    .iter()
                    .position(|&c| c == b'>')
                    .map_or(n, |p| p + i + 1);
                continue;
            }
            let closing = i + 1 < n && b[i + 1] == b'/';
            let name_start = i + if closing { 2 } else { 1 };
            if !(name_start < n && self.lower[name_start].is_ascii_lowercase()) {
                self.add_text(&b[i..i + 1]); // a lone "<" is text
                i += 1;
                continue;
            }
            let mut name_end = name_start;
            while name_end < n && is_name_byte(self.lower[name_end]) {
                name_end += 1;
            }
            let name = String::from_utf8_lossy(&self.lower[name_start..name_end]).into_owned();
            let Some((attrs, tag_end)) = self.parse_attributes(name_end) else {
                break;
            }; // cut mid-tag
            i = tag_end;
            if closing {
                continue;
            }
            match name.as_str() {
                "title" => i = self.read_title(i),
                "script" => i = self.read_script(&attrs, i),
                "style" => i = find_bytes(b"</style", &self.lower, i).unwrap_or(n),
                "form" => self
                    .result
                    .form_actions
                    .push(attrs.get("action").map(|a| entities::decode(a.as_bytes()))),
                "input" => self.result.inputs.push(HtmlInput {
                    input_type: attrs.get("type").cloned(),
                    name: attrs.get("name").cloned(),
                    id: attrs.get("id").cloned(),
                    autocomplete: attrs.get("autocomplete").cloned(),
                    placeholder: attrs.get("placeholder").cloned(),
                }),
                "meta" => self.read_meta(&attrs),
                _ => {}
            }
        }
        self.result.visible_text_length = self.visible;
        self.result
    }

    /// Attribute names lowercased, first occurrence wins, values as written.
    fn parse_attributes(&self, start: usize) -> Option<(HashMap<String, String>, usize)> {
        let b = self.b;
        let n = b.len();
        let mut attrs = HashMap::new();
        let mut i = start;
        while i < n {
            let c = b[i];
            if c == b'>' {
                return Some((attrs, i + 1));
            }
            if is_space(c) || c == b'/' {
                i += 1;
                continue;
            }
            let name_start = i;
            while i < n && !is_space(b[i]) && b[i] != b'=' && b[i] != b'>' && b[i] != b'/' {
                i += 1;
            }
            if i == name_start {
                i += 1; // stray "=": skip it
                continue;
            }
            let key = String::from_utf8_lossy(&self.lower[name_start..i]).into_owned();
            let mut j = i;
            while j < n && is_space(b[j]) {
                j += 1;
            }
            let mut value = String::new();
            if j < n && b[j] == b'=' {
                j += 1;
                while j < n && is_space(b[j]) {
                    j += 1;
                }
                if j >= n {
                    return None;
                }
                if b[j] == b'"' || b[j] == b'\'' {
                    let quote = b[j];
                    let close = b[j + 1..].iter().position(|&c| c == quote)? + j + 1;
                    value = String::from_utf8_lossy(&b[j + 1..close]).into_owned();
                    i = close + 1;
                } else {
                    let vs = j;
                    while j < n && !is_space(b[j]) && b[j] != b'>' {
                        j += 1;
                    }
                    value = String::from_utf8_lossy(&b[vs..j]).into_owned();
                    i = j;
                }
            }
            attrs.entry(key).or_insert(value);
        }
        None
    }

    fn read_title(&mut self, i: usize) -> usize {
        if self.result.title.is_some() {
            return i;
        }
        let close = find_bytes(b"</title", &self.lower, i);
        let end = close.unwrap_or_else(|| self.b.len().min(i + 2000));
        let text = collapse(&entities::decode(&self.b[i..end]));
        if !text.is_empty() {
            self.result.title = Some(t::prefix(&text, 200).to_string());
        }
        close.unwrap_or(end)
    }

    fn read_script(&mut self, attrs: &HashMap<String, String>, i: usize) -> usize {
        self.result.script_count += 1;
        let close = find_bytes(b"</script", &self.lower, i);
        let end = close.unwrap_or(self.b.len());
        if !attrs.contains_key("src")
            && !self.result.script_redirect
            && script_redirect::contains(&self.lower[i..end])
        {
            self.result.script_redirect = true;
        }
        end
    }

    fn read_meta(&mut self, attrs: &HashMap<String, String>) {
        if self.result.meta_refresh_url.is_some() {
            return;
        }
        if attrs
            .get("http-equiv")
            .map(|v| t::lower(t::trim_ws(v)))
            .as_deref()
            != Some("refresh")
        {
            return;
        }
        let Some(content) = attrs.get("content") else {
            return;
        };
        self.result.meta_refresh_url = refresh_url(&entities::decode(content.as_bytes()));
    }

    fn add_text(&mut self, run: &[u8]) {
        for s in entities::decode(run).chars() {
            if html_whitespace(s) {
                self.last_was_space = true;
            } else {
                if self.last_was_space && self.visible > 0 {
                    self.visible += 1;
                }
                self.visible += 1;
                self.last_was_space = false;
            }
        }
    }
}

fn collapse(s: &str) -> String {
    let mut out = String::new();
    let mut pending = false;
    for u in s.chars() {
        if html_whitespace(u) {
            pending = !out.is_empty();
            continue;
        }
        if pending {
            out.push(' ');
            pending = false;
        }
        out.push(u);
    }
    out
}

/// `5; url=https://x` → `https://x`; no URL → None.
pub fn refresh_url(content: &str) -> Option<String> {
    let sep = t::find(content, |g| t::ch_eq(g, ';') || t::ch_eq(g, ','))?;
    let mut rest = t::trim_ws_nl(&content[sep + t::char_len_at(content, sep)..]).to_string();
    if t::has_prefix(&t::lower(&rest), "url") {
        let after = t::drop_while(t::drop_first(&rest, 3), |g| {
            t::ch_eq(g, ' ') || t::ch_eq(g, '\t')
        });
        if t::first(after).is_some_and(|g| t::ch_eq(g, '=')) {
            rest = t::trim_ws_nl(t::drop_first(after, 1)).to_string();
        }
    }
    let is_quote = |g: &str| t::ch_eq(g, '"') || t::ch_eq(g, '\'');
    while t::first(&rest).is_some_and(is_quote) {
        rest = t::drop_first(&rest, 1).to_string();
    }
    while t::last(&rest).is_some_and(is_quote) {
        rest = t::drop_last(&rest, 1).to_string();
    }
    let rest = t::trim_ws_nl(&rest);
    (!rest.is_empty()).then(|| rest.to_string())
}

// MARK: ISO 8601 (RDAP)

/// `YYYY-MM-DDTHH:MM:SS[.fraction](Z|+HH:MM|-HH:MM|+HHMM)`, hand-rolled so both platforms agree on
/// fractions of any length and on offsets.
pub fn iso8601_lite(text: &str) -> Option<SystemTime> {
    let b = t::trim_ws(text).as_bytes();
    if b.len() < 20 || b.len() > 40 {
        return None;
    }
    let num = |from: usize, len: usize| -> Option<i64> {
        if from + len > b.len() {
            return None;
        }
        let mut v = 0;
        for &c in &b[from..from + len] {
            if !c.is_ascii_digit() {
                return None;
            }
            v = v * 10 + (c - b'0') as i64;
        }
        Some(v)
    };
    if b[4] != b'-'
        || b[7] != b'-'
        || !matches!(b[10], b'T' | b't' | b' ')
        || b[13] != b':'
        || b[16] != b':'
    {
        return None;
    }
    let (y, mo, d, h, mi, s) = (
        num(0, 4)?,
        num(5, 2)?,
        num(8, 2)?,
        num(11, 2)?,
        num(14, 2)?,
        num(17, 2)?,
    );
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h >= 24 || mi >= 60 || s > 60 {
        return None;
    }
    let mut i = 19;
    let mut fraction = 0.0;
    if i < b.len() && (b[i] == b'.' || b[i] == b',') {
        i += 1;
        let mut scale = 0.1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            if i - start < 9 {
                fraction += (b[i] - b'0') as f64 * scale;
                scale /= 10.0;
            }
            i += 1;
        }
        if i == start {
            return None;
        }
    }
    if i >= b.len() {
        return None;
    }
    let mut offset = 0;
    if b[i] == b'Z' || b[i] == b'z' {
        i += 1;
    } else if b[i] == b'+' || b[i] == b'-' {
        let sign = if b[i] == b'+' { 1 } else { -1 };
        let oh = num(i + 1, 2)?;
        i += 3;
        if i < b.len() && b[i] == b':' {
            i += 1;
        }
        let om = if i < b.len() { num(i, 2)? } else { 0 };
        if oh >= 24 || om >= 60 {
            return None;
        }
        if i < b.len() {
            i += 2;
        }
        offset = sign * (oh * 3600 + om * 60);
    } else {
        return None;
    }
    if i != b.len() || d > time::days_in(y, mo) {
        return None;
    }
    let seconds = (time::days_from_civil(y, mo, d) * 86_400) as f64
        + (h * 3600 + mi * 60 + s - offset) as f64;
    Some(time::from_seconds(seconds + fraction))
}

// MARK: RDAP

/// IANA's RDAP bootstrap file for DNS (data.iana.org/rdap/dns.json).
#[derive(Debug, Clone, Default)]
pub struct RdapBootstrap {
    services: HashMap<String, String>,
}

impl RdapBootstrap {
    /// An empty or unreadable file gives a bootstrap that knows no services.
    pub fn new(json: &[u8]) -> Self {
        if json.len() > 4_000_000 {
            return Self::default();
        }
        let Ok(root) = serde_json::from_slice::<Value>(json) else {
            return Self::default();
        };
        let Some(list) = root
            .as_object()
            .and_then(|o| o.get("services"))
            .and_then(Value::as_array)
        else {
            return Self::default();
        };
        let mut b = Self::default();
        for entry in list {
            let Some(pair) = entry.as_array().filter(|p| p.len() >= 2) else {
                continue;
            };
            let (Some(tlds), Some(urls)) = (pair[0].as_array(), pair[1].as_array()) else {
                continue;
            };
            let tlds: Vec<&str> = tlds.iter().filter_map(Value::as_str).collect();
            let urls: Vec<&str> = urls.iter().filter_map(Value::as_str).collect();
            b.add(&tlds, &urls);
        }
        b
    }

    pub fn from_file(file: &RdapFile) -> Self {
        let mut b = Self::default();
        for pair in file.services.iter().filter(|p| p.len() >= 2) {
            let tlds: Vec<&str> = pair[0].iter().map(String::as_str).collect();
            let urls: Vec<&str> = pair[1].iter().map(String::as_str).collect();
            b.add(&tlds, &urls);
        }
        b
    }

    fn add(&mut self, tlds: &[&str], urls: &[&str]) {
        let strings: Vec<&str> = urls.iter().map(|u| t::trim_ws(u)).collect();
        let pick = strings
            .iter()
            .find(|s| t::has_prefix(&t::lower(s), "https://"))
            .or_else(|| strings.iter().find(|s| !s.is_empty()));
        let Some(pick) = pick.filter(|p| !p.is_empty()) else {
            return;
        };
        let mut base = pick.to_string();
        if !t::has_suffix(&base, "/") {
            base.push('/');
        }
        for tld in tlds {
            let key = t::lower(tld);
            if !key.is_empty() {
                self.services.entry(key).or_insert_with(|| base.clone());
            }
        }
    }

    /// The base URL (ending in "/") for a top-level label, or None.
    pub fn base_url(&self, tld: &str) -> Option<String> {
        self.services.get(&t::lower(tld)).cloned()
    }

    /// `<base>domain/<name>` for an ASCII registrable domain, or None.
    pub fn query_url(&self, ascii_domain: &str) -> Option<String> {
        let domain = t::lower(ascii_domain);
        if domain.is_empty()
            || domain.len() > 253
            || !domain
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'.')
        {
            return None;
        }
        let last = domain.split('.').next_back().filter(|l| !l.is_empty())?;
        Some(format!("{}domain/{}", self.services.get(last)?, domain))
    }
}

/// Bodies larger than this are ignored.
pub const RDAP_RESPONSE_MAX_BYTES: usize = 256 * 1024;

/// The `events[]` entry whose `eventAction` is `registration`, or None.
pub fn rdap_registration_date_of(json: &[u8]) -> Option<SystemTime> {
    if json.len() > RDAP_RESPONSE_MAX_BYTES {
        return None;
    }
    let root: Value = serde_json::from_slice(json).ok()?;
    let events = root.as_object()?.get("events")?.as_array()?;
    for event in events.iter().filter_map(Value::as_object) {
        let Some(action) = event.get("eventAction").and_then(Value::as_str) else {
            continue;
        };
        if t::lower(t::trim_ws(action)) != "registration" {
            continue;
        }
        let Some(date) = event
            .get("eventDate")
            .and_then(Value::as_str)
            .and_then(iso8601_lite)
        else {
            continue;
        };
        return Some(date);
    }
    None
}

#[uniffi::export]
pub fn rdap_registration_date(json: Vec<u8>) -> Option<SystemTime> {
    rdap_registration_date_of(&json)
}

/// Whole days in `seconds` (floor), never negative; NaN gives 0.
pub fn domain_age_days_in(seconds: f64) -> i64 {
    if !seconds.is_finite() || seconds < 86_400.0 {
        return 0;
    }
    (seconds / 86_400.0).floor().min(1_000_000_000.0) as i64
}

/// Whole days from `registered_at` to `now` (floor), never negative.
#[uniffi::export]
pub fn domain_age_days(registered_at: SystemTime, now: SystemTime) -> i64 {
    domain_age_days_in(time::seconds(now) - time::seconds(registered_at))
}

// MARK: report

pub const DOWNLOAD_EXTENSIONS: &[&str] = &[
    "apk",
    "ipa",
    "mobileconfig",
    "exe",
    "msi",
    "dmg",
    "pkg",
    "zip",
    "scr",
    "bat",
];

/// Hops + page → signals (SPEC §6).
#[derive(uniffi::Object)]
pub struct PeekAnalyzer {
    pub data: Arc<BundledData>,
    analyzer: Analyzer,
}

impl PeekAnalyzer {
    pub fn with_data(data: Arc<BundledData>) -> Self {
        PeekAnalyzer {
            analyzer: Analyzer::with_known(data.clone(), KnownAddresses::default()),
            data,
        }
    }

    fn page_signals(&self, s: &HtmlSniff, final_link: &LinkInfo) -> Vec<Signal> {
        let mut out = Vec::new();
        if let Some(kind) = checks::asks_for_secret(&s.inputs) {
            match checks::secret_form_brand_mismatch(
                s.title.as_deref(),
                final_link,
                &self.analyzer.context().brands,
            ) {
                Some(brand) => out.push(Signal::with(
                    SignalId::SecretFormBrandMismatch,
                    &[("brand", &brand), ("kind", kind)],
                )),
                None => out.push(Signal::with(SignalId::AsksForSecret, &[("kind", kind)])),
            }
        }
        if let Some(host) =
            checks::form_posts_elsewhere(&s.form_actions, final_link, &self.data.public_suffixes)
        {
            out.push(Signal::with(
                SignalId::FormPostsElsewhere,
                &[("host", &host)],
            ));
        }
        if s.meta_refresh_url.as_deref().is_some_and(|u| !u.is_empty()) {
            out.push(Signal::with(SignalId::ScriptRedirect, &[("via", "meta")]));
        } else if s.script_redirect {
            out.push(Signal::with(SignalId::ScriptRedirect, &[("via", "script")]));
        }
        if s.visible_text_length < 200 && s.script_count >= 1 {
            out.push(Signal::new(SignalId::NeedsJavaScript));
        }
        out
    }

    fn sniff_capture(c: &PeekCapture) -> Option<HtmlSniff> {
        if c.body.is_empty() {
            return None;
        }
        let ct = c.content_type.as_deref().map(t::lower).unwrap_or_default();
        let first = c
            .body
            .iter()
            .find(|&&b| !matches!(b, b' ' | b'\t' | b'\n' | b'\r'));
        if !t::contains(&ct, "html") && first != Some(&b'<') {
            return None;
        }
        Some(sniff(&c.body, c.content_type.as_deref()))
    }

    fn downloads(c: &PeekCapture, final_link: &LinkInfo) -> bool {
        if let Some(d) = &c.content_disposition
            && t::has_prefix(t::trim_ws(&t::lower(d)), "attachment")
        {
            return true;
        }
        let path = t::prefix_while(&final_link.path_and_query, |g| {
            !t::ch_eq(g, '?') && !t::ch_eq(g, '#')
        });
        let Some(name) = t::split_ch(path, '/', true).last().copied() else {
            return false;
        };
        let Some(dot) = t::rfind_ch(name, '.') else {
            return false;
        };
        DOWNLOAD_EXTENSIONS.contains(&t::lower(&name[dot + t::char_len_at(name, dot)..]).as_str())
    }
}

#[uniffi::export]
impl PeekAnalyzer {
    #[uniffi::constructor]
    pub fn new(data: Arc<BundledData>) -> Arc<Self> {
        Arc::new(Self::with_data(data))
    }

    /// `age_days` None means the age is unknown (no RDAP service, 404, no registration event,
    /// timeout).
    pub fn report(
        &self,
        original: Analysis,
        capture: PeekCapture,
        age_days: Option<i64>,
    ) -> PeekReport {
        let psl = &self.data.public_suffixes;
        let symbology = original.code.symbology;
        let hop_analyses: Vec<Analysis> = capture
            .hops
            .iter()
            .map(|h| self.analyzer.analyze(ScannedCode::text(&h.url, symbology)))
            .collect();
        let final_link = capture
            .hops
            .iter()
            .rev()
            .find(|h| h.status.is_some())
            .and_then(|h| WebAddress::parse(&h.url, psl))
            .map(|a| a.link);

        let mut signals = Vec::new();
        match capture.stopped_because {
            StopReason::TooManyRedirects => signals.push(Signal::new(SignalId::TooManyRedirects)),
            StopReason::HttpTarget => {
                let host = capture
                    .hops
                    .last()
                    .and_then(|h| WebAddress::parse(&h.url, psl))
                    .map(|a| a.link.host_unicode);
                signals.push(match host {
                    Some(h) => Signal::with(SignalId::RedirectsToHttp, &[("host", &h)]),
                    None => Signal::new(SignalId::RedirectsToHttp),
                });
            }
            StopReason::NonWebTarget => {
                let scheme = capture
                    .hops
                    .last()
                    .and_then(|h| WebAddress::scheme_of(&h.url))
                    .unwrap_or_default();
                signals.push(if scheme.is_empty() {
                    Signal::new(SignalId::RedirectLeavesWeb)
                } else {
                    Signal::with(SignalId::RedirectLeavesWeb, &[("scheme", &scheme)])
                });
            }
            StopReason::TlsFailure => signals.push(Signal::new(SignalId::TlsProblem)),
            StopReason::Timeout | StopReason::NetworkError => {
                signals.push(Signal::new(SignalId::PeekIncomplete))
            }
            StopReason::Finished => {}
        }

        let mut title = None;
        if let Some(fl) = &final_link {
            if Self::downloads(&capture, fl) {
                signals.push(Signal::new(SignalId::DownloadsFile));
            }
            if let Some(s) = Self::sniff_capture(&capture) {
                title = s.title.clone();
                signals.extend(self.page_signals(&s, fl));
            }
            if fl.suffix_is_private {
                signals.push(Signal::with(
                    SignalId::SharedHostingDomain,
                    &[("suffix", fl.public_suffix.as_deref().unwrap_or(""))],
                ));
            }
            match age_days {
                None => signals.push(Signal::new(SignalId::AgeUnknown)),
                Some(d) if d < 30 => signals.push(Signal::with(
                    SignalId::DomainVeryNew,
                    &[("days", &d.to_string())],
                )),
                Some(d) if d < 183 => signals.push(Signal::with(
                    SignalId::DomainNew,
                    &[("days", &d.to_string())],
                )),
                _ => {}
            }
        }

        let mut seen = HashSet::new();
        let ordered: Vec<Signal> = in_stable_order(&signals)
            .into_iter()
            .filter(|s| seen.insert(s.id))
            .collect();
        let level = hop_analyses
            .iter()
            .map(|a| a.level)
            .fold(original.level.max(level_for(&ordered)), Level::max);
        PeekReport {
            final_link,
            hop_analyses,
            title,
            signals: ordered,
            level,
        }
    }
}
