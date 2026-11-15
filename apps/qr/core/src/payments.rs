//! Payment decoding (QRCore `Payments/`): EMVCo merchant QR (TLV + CRC), EPC, Swiss QR-bill,
//! UPI, crypto URIs and bare addresses, IBAN, Bech32.

use crate::contract::*;
use crate::text as t;

/// CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no final XOR.
pub fn crc16_ccitt_false(bytes: &[u8]) -> u16 {
    let mut crc: u16 = 0xFFFF;
    for &b in bytes {
        crc ^= (b as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 { (crc << 1) ^ 0x1021 } else { crc << 1 };
        }
    }
    crc
}

#[uniffi::export]
pub fn crc16(bytes: Vec<u8>) -> u16 {
    crc16_ccitt_false(&bytes)
}

/// ISO 4217 numeric ("608") → alpha ("PHP") using the bundled table; None if unknown.
pub fn currency_alpha(numeric: &str, table: &std::collections::HashMap<String, String>) -> Option<String> {
    let d = numeric.as_bytes();
    if d.is_empty() || d.len() > 3 || !d.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let padded = format!("{}{}", "0".repeat(3 - d.len()), numeric);
    table.get(&padded).filter(|a| !a.is_empty()).cloned()
}

// MARK: shared text helpers

pub(crate) mod text {
    use crate::text as t;
    use std::collections::HashMap;

    pub const MAX_BYTES: usize = 8192;

    pub fn within_cap(s: &str) -> bool {
        s.len() <= MAX_BYTES
    }

    fn hex(b: u8) -> Option<u8> {
        match b {
            48..=57 => Some(b - 48),
            65..=70 => Some(b - 55),
            97..=102 => Some(b - 87),
            _ => None,
        }
    }

    /// Percent-decodes UTF-8. A malformed escape stays literal. `+` is left alone.
    pub fn percent_decode(s: &str) -> String {
        if !s.contains('%') {
            return s.to_string();
        }
        let b = s.as_bytes();
        let mut out = Vec::with_capacity(b.len());
        let mut i = 0;
        while i < b.len() {
            if b[i] == b'%' && i + 2 < b.len()
                && let (Some(h), Some(l)) = (hex(b[i + 1]), hex(b[i + 2])) {
                    out.push(h * 16 + l);
                    i += 3;
                    continue;
                }
            out.push(b[i]);
            i += 1;
        }
        t::utf8_lossy(&out)
    }

    /// Query string → lowercase key to decoded value; the first occurrence of a key wins.
    pub fn query_items(query: &str) -> HashMap<String, String> {
        let mut out = HashMap::new();
        for part in t::split_ch(query, '&', true) {
            let (key, value) = match t::find_ch(part, '=') {
                Some(eq) => (&part[..eq], &part[eq + t::char_len_at(part, eq)..]),
                None => (part, ""),
            };
            out.entry(t::lower(&percent_decode(key))).or_insert_with(|| percent_decode(value));
        }
        out
    }

    /// Lines split on LF with an optional CR before it. Empty lines are kept.
    pub fn lines(text: &str) -> Vec<String> {
        let mut out = Vec::new();
        let mut current = String::new();
        let strip = |c: &mut String| {
            if t::has_suffix(c, "\r") {
                *c = t::drop_last(c, 1).to_string();
            }
        };
        for ch in text.chars() {
            if ch == '\n' {
                strip(&mut current);
                out.push(std::mem::take(&mut current));
            } else {
                current.push(ch);
            }
        }
        strip(&mut current);
        out.push(current);
        out
    }

    /// Trimmed, None when empty.
    pub fn non_empty(s: Option<&str>) -> Option<String> {
        let tt = t::trim_ws(s?);
        if tt.is_empty() { None } else { Some(tt.to_string()) }
    }
}

// MARK: EMV

pub(crate) mod tlv {
    #[derive(Clone, Copy, PartialEq, Eq)]
    pub enum Unit {
        Utf8,
        Utf16,
    }

    impl Unit {
        pub fn text(self, units: &[u16]) -> String {
            match self {
                Unit::Utf8 => crate::text::utf8_lossy(&units.iter().map(|&u| u as u8).collect::<Vec<_>>()),
                Unit::Utf16 => char::decode_utf16(units.iter().copied()).map(|r| r.unwrap_or('\u{FFFD}')).collect(),
            }
        }
    }

    pub fn units(text: &str, unit: Unit) -> Vec<u16> {
        match unit {
            Unit::Utf8 => text.bytes().map(u16::from).collect(),
            Unit::Utf16 => text.encode_utf16().collect(),
        }
    }

    pub struct Item {
        pub tag: String,
        pub value: Vec<u16>,
    }

    pub struct Read {
        pub items: Vec<Item>,
        /// Every unit was consumed by well-formed TLVs.
        pub complete: bool,
    }

    fn digit(u: u16) -> Option<usize> {
        (0x30..=0x39).contains(&u).then(|| (u - 0x30) as usize)
    }

    /// Reads TLVs until the end or the first malformed one (which is dropped).
    pub fn read(units: &[u16]) -> Read {
        let mut items = Vec::new();
        let mut i = 0;
        let n = units.len();
        while i < n {
            if i + 4 > n {
                return Read { items, complete: false };
            }
            let (Some(t0), Some(t1), Some(l0), Some(l1)) =
                (digit(units[i]), digit(units[i + 1]), digit(units[i + 2]), digit(units[i + 3]))
            else {
                return Read { items, complete: false };
            };
            let length = l0 * 10 + l1;
            let start = i + 4;
            if start + length > n {
                return Read { items, complete: false };
            }
            items.push(Item { tag: format!("{t0}{t1}"), value: units[start..start + length].to_vec() });
            i = start + length;
        }
        Read { items, complete: true }
    }
}

pub mod emv {
    use super::tlv::{self, Item, Unit};
    use crate::contract::*;
    use crate::data::EmvNetwork;
    use crate::text as t;
    use std::collections::HashMap;

    pub const MAX_BYTES: usize = 8 * 1024;

    /// An EMVCo merchant-presented payload, or None if it isn't one. `checksum` is `Invalid` on a
    /// CRC mismatch.
    pub fn parse(text: &str, networks: &[EmvNetwork], currencies: &HashMap<String, String>) -> Option<PaymentInfo> {
        let bytes = text.as_bytes();
        if !bytes.starts_with(b"000201") {
            return None;
        }
        let mut info = PaymentInfo::new(PaymentFormat::Emv);
        if bytes.len() > MAX_BYTES {
            info.checksum = PaymentChecksum::Invalid;
            return Some(info);
        }
        info.checksum = checksum(bytes);
        let (items, unit) = top_level(text);
        for item in &items {
            apply(item, unit, &mut info, currencies);
        }
        apply_network(&items, unit, &mut info, networks);
        Some(info)
    }

    fn checksum(bytes: &[u8]) -> PaymentChecksum {
        if bytes.len() < 8 || &bytes[bytes.len() - 8..bytes.len() - 4] != b"6304" {
            return PaymentChecksum::Absent;
        }
        let mut value: u16 = 0;
        for &b in &bytes[bytes.len() - 4..] {
            let d = match b {
                0x30..=0x39 => b - 0x30,
                0x41..=0x46 => b - 0x41 + 10,
                0x61..=0x66 => b - 0x61 + 10,
                _ => return PaymentChecksum::Invalid,
            };
            value = value << 4 | d as u16;
        }
        if super::crc16_ccitt_false(&bytes[..bytes.len() - 4]) == value { PaymentChecksum::Valid } else { PaymentChecksum::Invalid }
    }

    fn top_level(text: &str) -> (Vec<Item>, Unit) {
        let mut lenient = Vec::new();
        for unit in [Unit::Utf8, Unit::Utf16] {
            let r = tlv::read(&tlv::units(text, unit));
            let ends_in_crc = r.items.last().is_some_and(|i| i.tag == "63");
            if r.complete && ends_in_crc {
                return (r.items, unit);
            }
            if unit == Unit::Utf8 {
                lenient = r.items;
            }
        }
        (lenient, Unit::Utf8)
    }

    fn apply(item: &Item, unit: Unit, info: &mut PaymentInfo, currencies: &HashMap<String, String>) {
        let value = unit.text(&item.value);
        let Some(tag) = t::parse_int(&item.tag) else { return };
        match tag {
            1 => info.is_dynamic = if value == "11" { Some(false) } else if value == "12" { Some(true) } else { None },
            52 => {
                info.extra.insert("mcc".into(), value);
            }
            53 => match super::currency_alpha(&value, currencies) {
                Some(a) => info.currency = Some(a),
                None => {
                    info.extra.insert("currencyNumeric".into(), value);
                }
            },
            54 => info.amount = Some(value),
            58 => info.country = Some(value),
            59 => info.payee_name = Some(value),
            60 => info.city = Some(value),
            62 => {
                for sub in tlv::read(&item.value).items {
                    let v = unit.text(&sub.value);
                    match sub.tag.as_str() {
                        "05" => info.reference = Some(v),
                        "08" => info.note = Some(v),
                        _ => {
                            info.extra.insert(format!("additional.{}", sub.tag), v);
                        }
                    }
                }
            }
            64 => {
                for sub in tlv::read(&item.value).items {
                    let v = unit.text(&sub.value);
                    let key = match sub.tag.as_str() {
                        "00" => "alternateLanguage",
                        "01" => "alternateName",
                        "02" => "alternateCity",
                        _ => continue,
                    };
                    info.extra.insert(key.into(), v);
                }
            }
            _ => {}
        }
    }

    /// First matching network wins, regardless of template order.
    fn apply_network(items: &[Item], unit: Unit, info: &mut PaymentInfo, networks: &[EmvNetwork]) {
        let mut first_id: Option<String> = None;
        for item in items {
            let Some(tag) = t::parse_int(&item.tag) else { continue };
            if !(26..=51).contains(&tag) {
                continue;
            }
            let subs = tlv::read(&item.value).items;
            let Some(id_item) = subs.iter().find(|s| s.tag == "00") else { continue };
            let id = unit.text(&id_item.value);
            if first_id.is_none() {
                first_id = Some(id.clone());
            }
            let lowered = t::lower(&id);
            let Some(net) = networks.iter().find(|n| t::lower(&n.id) == lowered) else { continue };
            info.network = Some(net.name.clone());
            info.party = PaymentParty::from_raw(&net.party).unwrap_or(PaymentParty::Unknown);
            let account = subs.iter().find(|s| s.tag == "01").or_else(|| subs.iter().find(|s| s.tag == "02"));
            info.payee_account = account.map(|a| unit.text(&a.value));
            return;
        }
        if let Some(id) = first_id {
            info.extra.insert("networkID".into(), id);
        }
    }
}

// MARK: IBAN

pub mod iban {
    use crate::text as t;

    /// Spaces removed and uppercased; None unless 15–34 letters/digits shaped as two country
    /// letters, two check digits, then a basic part.
    pub fn normalized(iban: &str) -> Option<String> {
        let s = t::upper(&t::graphemes(iban).filter(|g| !t::ch_eq(g, ' ')).collect::<String>());
        let u = s.as_bytes();
        if !(15..=34).contains(&u.len()) {
            return None;
        }
        for (i, &b) in u.iter().enumerate() {
            let (letter, digit) = (b.is_ascii_uppercase(), b.is_ascii_digit());
            let ok = match i {
                0 | 1 => letter,
                2 | 3 => digit,
                _ => letter || digit,
            };
            if !ok {
                return None;
            }
        }
        Some(s)
    }

    /// ISO 13616 mod-97, digit by digit. Length and shape only.
    pub fn is_valid(iban: &str) -> bool {
        let Some(s) = normalized(iban) else { return false };
        let b = s.as_bytes();
        let mut v = b[4..].to_vec();
        v.extend_from_slice(&b[..4]);
        mod97_is_1(&v)
    }

    /// The letters-as-numbers (A=10…Z=35) reading of `bytes` is 1 mod 97.
    pub fn mod97_is_1(bytes: &[u8]) -> bool {
        let mut rem: u64 = 0;
        for &b in bytes {
            match b {
                48..=57 => rem = (rem * 10 + (b - 48) as u64) % 97,
                65..=90 => rem = (rem * 100 + (b - 55) as u64) % 97,
                _ => return false,
            }
        }
        rem == 1
    }
}

#[uniffi::export]
pub fn iban_is_valid(iban: String) -> bool {
    iban::is_valid(&iban)
}

/// Spaces dropped and uppercased (what EPC and Swiss parsing store).
fn compact_upper(s: &str) -> String {
    t::upper(&t::graphemes(s).filter(|g| !t::ch_eq(g, ' ')).collect::<String>())
}

// MARK: EPC

pub mod epc {
    use super::text::{lines, non_empty, within_cap};
    use crate::contract::*;
    use crate::text as t;

    /// EPC069-12 (first line `BCD`), or None. IBAN mod-97 failure → checksum `Invalid`.
    pub fn parse(text: &str) -> Option<PaymentInfo> {
        if !within_cap(text) {
            return None;
        }
        let l = lines(text);
        let line = |i: usize| if i < l.len() { non_empty(Some(&l[i])) } else { None };
        if line(0).as_deref() != Some("BCD") {
            return None;
        }
        if let Some(id) = line(3)
            && id != "SCT" {
                return None;
            }
        let mut info = PaymentInfo::new(PaymentFormat::Epc);
        if let Some(v) = line(4) {
            info.extra.insert("bic".into(), v);
        }
        info.payee_name = line(5);
        if let Some(raw) = line(6) {
            let iban = super::compact_upper(&raw);
            info.checksum = if super::iban::is_valid(&iban) { PaymentChecksum::Valid } else { PaymentChecksum::Invalid };
            info.payee_account = Some(iban);
        }
        if let Some(v) = line(7) {
            let letters = t::prefix(&v, 3);
            if t::count(letters) == 3 && t::graphemes(letters).all(|g| t::ch_is_ascii(g) && t::ch_is_letter(g)) {
                info.currency = Some(t::upper(letters));
                info.amount = non_empty(Some(t::drop_first(&v, 3)));
            } else {
                info.amount = Some(v);
            }
        }
        if let Some(v) = line(8) {
            info.extra.insert("purpose".into(), v);
        }
        info.reference = line(9);
        info.note = line(10);
        if let Some(v) = line(11) {
            info.extra.insert("information".into(), v);
        }
        Some(info)
    }
}

// MARK: Swiss QR-bill

pub mod swiss {
    use super::text::{lines, non_empty, within_cap};
    use crate::contract::*;
    use crate::text as t;

    /// Swiss QR-bill (first line `SPC`), or None. IBAN, QRR mod-10 or SCOR mod-97 failure →
    /// `Invalid`.
    pub fn parse(text: &str) -> Option<PaymentInfo> {
        if !within_cap(text) {
            return None;
        }
        let l = lines(text);
        let line = |i: usize| if i < l.len() { non_empty(Some(&l[i])) } else { None };
        if line(0).as_deref() != Some("SPC") {
            return None;
        }
        let version = line(1)?;
        if version.len() != 4 || !t::has_prefix(&version, "02") || !version.bytes().all(|b| b.is_ascii_digit()) {
            return None;
        }
        if let Some(coding) = line(2)
            && coding != "1" {
                return None;
            }
        let mut info = PaymentInfo::new(PaymentFormat::Swiss);
        let mut results = Vec::new();
        if let Some(raw) = line(3) {
            let iban = super::compact_upper(&raw);
            results.push(super::iban::is_valid(&iban));
            info.payee_account = Some(iban);
        }
        info.payee_name = line(5);
        let address: Vec<String> = (6..=9).filter_map(line).collect();
        if !address.is_empty() {
            info.extra.insert("creditorAddress".into(), address.join("\n"));
        }
        if line(4).as_deref() == Some("S") {
            info.city = line(9);
        }
        info.country = line(10);
        info.amount = line(18);
        info.currency = line(19).map(|c| t::upper(&c));
        if let Some(name) = line(21) {
            info.extra.insert("debtorName".into(), name);
        }
        let ref_type = line(27).map(|r| t::upper(&r));
        if let Some(r) = &ref_type {
            info.extra.insert("referenceType".into(), r.clone());
        }
        if let Some(r) = line(28) {
            let compact: String = t::graphemes(&r).filter(|g| !t::ch_eq(g, ' ')).collect();
            match ref_type.as_deref() {
                Some("QRR") => results.push(is_valid_qrr(&compact)),
                Some("SCOR") => results.push(is_valid_creditor_reference(&compact)),
                _ => {}
            }
            info.reference = Some(r);
        }
        info.note = line(29);
        if let Some(v) = line(31) {
            info.extra.insert("billingInfo".into(), v);
        }
        info.checksum = if results.is_empty() {
            PaymentChecksum::Absent
        } else if results.iter().all(|&r| r) {
            PaymentChecksum::Valid
        } else {
            PaymentChecksum::Invalid
        };
        Some(info)
    }

    /// 27 digits; the last is the recursive mod-10 check digit of the first 26.
    pub fn is_valid_qrr(r: &str) -> bool {
        let d = r.as_bytes();
        if d.len() != 27 || !d.iter().all(u8::is_ascii_digit) {
            return false;
        }
        const TABLE: [usize; 10] = [0, 9, 4, 6, 8, 2, 7, 1, 3, 5];
        let mut carry = 0;
        for &b in &d[..26] {
            carry = TABLE[(carry + (b - 48) as usize) % 10];
        }
        (10 - carry) % 10 == (d[26] - 48) as usize
    }

    /// ISO 11649: `RF`, two check digits, up to 21 alphanumerics; mod-97 equals 1.
    pub fn is_valid_creditor_reference(r: &str) -> bool {
        let s = t::upper(r);
        let u = s.as_bytes();
        if !(5..=25).contains(&u.len())
            || !s.starts_with("RF")
            || !u[2..4].iter().all(u8::is_ascii_digit)
            || !u.iter().all(|b| b.is_ascii_digit() || b.is_ascii_uppercase())
        {
            return false;
        }
        let mut v = u[4..].to_vec();
        v.extend_from_slice(&u[..4]);
        super::iban::mod97_is_1(&v)
    }
}

// MARK: UPI

pub mod upi {
    use super::text::{non_empty, query_items, within_cap};
    use crate::contract::*;
    use crate::text as t;
    use std::collections::HashMap;

    /// `upi://pay` and `upi://mandate` (case-insensitive scheme), or None.
    pub fn parse(text: &str) -> Option<PaymentInfo> {
        if !within_cap(text) {
            return None;
        }
        let tt = t::trim_ws_nl(text);
        if !t::has_prefix(&t::lower(tt), "upi://") {
            return None;
        }
        let rest = t::drop_first(tt, 6);
        let host_end = t::find(rest, |g| t::ch_eq(g, '?') || t::ch_eq(g, '/') || t::ch_eq(g, '#')).unwrap_or(rest.len());
        let kind = t::lower(&rest[..host_end]);
        if kind != "pay" && kind != "mandate" {
            return None;
        }
        let mut query = "";
        if let Some(q) = t::find_ch(rest, '?') {
            query = &rest[q + t::char_len_at(rest, q)..];
            if let Some(h) = t::find_ch(query, '#') {
                query = &query[..h];
            }
        }
        // The NPCI linking spec percent-encodes spaces; `+` is not special.
        let p = query_items(query);
        let get = |k: &str| non_empty(p.get(k).map(String::as_str));
        let mut extra = HashMap::from([("kind".to_string(), kind)]);
        for (key, name) in [("mc", "mcc"), ("mode", "mode"), ("url", "url")] {
            if let Some(v) = get(key) {
                extra.insert(name.into(), v);
            }
        }
        Some(PaymentInfo {
            payee_name: get("pn"),
            payee_account: get("pa"),
            amount: get("am"),
            currency: get("cu").map(|c| t::upper(&c)),
            reference: get("tr"),
            note: get("tn"),
            extra,
            ..PaymentInfo::new(PaymentFormat::Upi)
        })
    }
}

// MARK: Bech32

pub mod bech32 {
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub enum Encoding {
        Bech32,
        Bech32m,
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct Decoded {
        pub hrp: String,
        /// 5-bit values, checksum removed.
        pub data: Vec<u8>,
        pub encoding: Encoding,
    }

    const CHARSET: &[u8] = b"qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    const GENERATOR: [u32; 5] = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3];

    fn polymod(values: &[u8]) -> u32 {
        let mut chk: u32 = 1;
        for &v in values {
            let top = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v as u32;
            for (i, g) in GENERATOR.iter().enumerate() {
                if (top >> i) & 1 == 1 {
                    chk ^= g;
                }
            }
        }
        chk
    }

    /// Verifies the checksum. None for mixed case, characters outside 33–126, a missing or
    /// misplaced separator, a short data part, a bad character, a bad checksum, or more than
    /// `max_length` characters.
    pub fn decode(text: &str, max_length: usize) -> Option<Decoded> {
        let bytes = text.as_bytes();
        if bytes.len() > max_length || bytes.len() < 8 {
            return None;
        }
        let (mut lower_seen, mut upper_seen) = (false, false);
        for &b in bytes {
            if !(33..=126).contains(&b) {
                return None;
            }
            lower_seen |= b.is_ascii_lowercase();
            upper_seen |= b.is_ascii_uppercase();
        }
        if lower_seen && upper_seen {
            return None;
        }
        let lower: Vec<u8> = bytes.iter().map(|b| b.to_ascii_lowercase()).collect();
        let sep = lower.iter().rposition(|&b| b == b'1')?;
        if sep < 1 || lower.len() - sep - 1 < 6 {
            return None;
        }
        let hrp = &lower[..sep];
        let data: Vec<u8> = lower[sep + 1..].iter().map(|c| CHARSET.iter().position(|x| x == c).map(|i| i as u8)).collect::<Option<_>>()?;
        let mut expand: Vec<u8> = hrp.iter().map(|b| b >> 5).collect();
        expand.push(0);
        expand.extend(hrp.iter().map(|b| b & 31));
        expand.extend_from_slice(&data);
        let encoding = match polymod(&expand) {
            1 => Encoding::Bech32,
            0x2bc830a3 => Encoding::Bech32m,
            _ => return None,
        };
        Some(Decoded { hrp: String::from_utf8_lossy(hrp).into_owned(), data: data[..data.len() - 6].to_vec(), encoding })
    }
}

// MARK: crypto

pub mod crypto_address {
    use super::bech32::{self, Encoding};
    use crate::contract::CryptoInfo;
    use crate::text as t;

    fn is_base58(b: u8) -> bool {
        !matches!(b, b'0' | b'O' | b'I' | b'l') && b.is_ascii_alphanumeric()
    }

    /// A bare address (bech32/bech32m with valid checksum and known prefix, `0x` + 40 hex,
    /// base58 26–35 chars starting 1 or 3), or None.
    pub fn detect(text: &str) -> Option<CryptoInfo> {
        if text.len() > 512 {
            return None;
        }
        let tt = t::trim_ws_nl(text);
        let u = tt.as_bytes();
        if u.is_empty() || !u.iter().all(|b| (33..=126).contains(b)) {
            return None;
        }
        if let Some(d) = bech32::decode(tt, 90) {
            let network = match d.hrp.as_str() {
                "bc" => "Bitcoin",
                "tb" => "Bitcoin testnet",
                "ltc" => "Litecoin",
                _ => return None,
            };
            // Segwit: version 0 uses bech32, versions 1-16 use bech32m.
            let version = *d.data.first()?;
            if version > 16 || (version == 0) != (d.encoding == Encoding::Bech32) {
                return None;
            }
            return Some(CryptoInfo::bare(network, tt));
        }
        if u.len() == 42 && tt.starts_with("0x") && u[2..].iter().all(u8::is_ascii_hexdigit) {
            return Some(CryptoInfo::bare("Ethereum", tt));
        }
        if (26..=35).contains(&u.len()) && (u[0] == b'1' || u[0] == b'3') && u.iter().all(|&b| is_base58(b)) {
            return Some(CryptoInfo::bare("Bitcoin", tt));
        }
        None
    }
}

pub mod crypto_uri {
    use super::text::{non_empty, query_items, within_cap};
    use crate::contract::CryptoInfo;
    use crate::data::CryptoScheme;
    use crate::text as t;
    use std::collections::HashMap;

    /// `bitcoin:`, `ethereum:`, `lightning:` and every scheme in `schemes`, or None.
    pub fn parse(text: &str, schemes: &[CryptoScheme]) -> Option<CryptoInfo> {
        if !within_cap(text) {
            return None;
        }
        let tt = t::trim_ws_nl(text);
        let colon = t::find_ch(tt, ':')?;
        let scheme = t::lower(&tt[..colon]);
        let mut rest = &tt[colon + t::char_len_at(tt, colon)..];
        if t::has_prefix(rest, "//") {
            rest = t::drop_first(rest, 2);
        }
        if let Some(h) = t::find_ch(rest, '#') {
            rest = &rest[..h];
        }
        let mut path = rest;
        let mut params = HashMap::new();
        if let Some(q) = t::find_ch(rest, '?') {
            path = &rest[..q];
            params = query_items(&rest[q + t::char_len_at(rest, q)..]);
        }
        let address = t::trim_ws(path).to_string();
        let get = |k: &str| non_empty(params.get(k).map(String::as_str));
        match scheme.as_str() {
            "lightning" => (!address.is_empty()).then(|| lightning(&scheme, &address)),
            "bitcoin" => {
                if address.is_empty() {
                    let inv = get("lightning")?;
                    return Some(lightning(&scheme, &inv));
                }
                Some(generic(&scheme, "Bitcoin", &address, &params))
            }
            "ethereum" => ethereum(&address, &params),
            _ => {
                let known = schemes.iter().find(|s| t::lower(&s.scheme) == scheme)?;
                if address.is_empty() {
                    return None;
                }
                Some(generic(&scheme, &known.name, &address, &params))
            }
        }
    }

    fn generic(scheme: &str, network: &str, address: &str, params: &HashMap<String, String>) -> CryptoInfo {
        let get = |k: &str| non_empty(params.get(k).map(String::as_str));
        CryptoInfo {
            scheme: Some(scheme.into()),
            amount: get("amount"),
            label: get("label"),
            message: get("message"),
            ..CryptoInfo::bare(network, address)
        }
    }

    /// EIP-681: `[pay-]target[@chainId][/function]?value=…`.
    fn ethereum(raw: &str, params: &HashMap<String, String>) -> Option<CryptoInfo> {
        let mut s = raw;
        if t::has_prefix(&t::lower(s), "pay-") {
            s = t::drop_first(s, 4);
        }
        if let Some(slash) = t::find_ch(s, '/') {
            s = &s[..slash];
        }
        let mut chain = None;
        if let Some(at) = t::find_ch(s, '@') {
            chain = non_empty(Some(&s[at + t::char_len_at(s, at)..]));
            s = &s[..at];
        }
        if s.is_empty() {
            return None;
        }
        let get = |k: &str| non_empty(params.get(k).map(String::as_str));
        let value = params.get("value").or_else(|| params.get("amount"));
        Some(CryptoInfo {
            scheme: Some("ethereum".into()),
            amount: non_empty(value.map(String::as_str)),
            label: get("label"),
            message: get("message"),
            chain_id: chain,
            ..CryptoInfo::bare("Ethereum", s)
        })
    }

    fn lightning(scheme: &str, raw: &str) -> CryptoInfo {
        let mut invoice = raw;
        if t::has_prefix(&t::lower(invoice), "lightning:") {
            invoice = t::drop_first(invoice, 10);
        }
        CryptoInfo {
            scheme: Some(scheme.into()),
            amount: invoice_amount(invoice),
            is_lightning_invoice: true,
            ..CryptoInfo::bare("Lightning", invoice)
        }
    }

    /// Amount in BTC from the human-readable part only: `ln` + network + optional digits +
    /// optional multiplier. The data part is never decoded.
    pub fn invoice_amount(invoice: &str) -> Option<String> {
        let lower = t::lower(invoice);
        let sep = t::rfind_ch(&lower, '1')?;
        let mut hrp = &lower[..sep];
        if !t::has_prefix(hrp, "ln") {
            return None;
        }
        hrp = t::drop_first(hrp, 2);
        let net = ["bcrt", "tbs", "bc", "tb"].into_iter().find(|n| t::has_prefix(hrp, n))?;
        hrp = t::drop_first(hrp, net.len());
        // Swift compares Characters here (`$0 >= "0" && $0 <= "9"`): a digit carrying a combining
        // mark still counts, so the arithmetic below runs on characters, not bytes.
        let is_digit = |g: &str| {
            let n: String = unicode_normalization::UnicodeNormalization::nfc(g).collect();
            let f = n.chars().next().unwrap_or('\0');
            ('0'..'9').contains(&f) || n == "9"
        };
        let digits: Vec<&str> = t::graphemes(hrp).take_while(|g| is_digit(g)).collect();
        let tail = &hrp[digits.iter().map(|g| g.len()).sum::<usize>()..];
        if digits.is_empty() || digits.len() > 19 {
            return None;
        }
        let shift = match tail {
            "" => 0,
            "m" => 3,
            "u" => 6,
            "n" => 9,
            "p" => 12,
            _ => return None,
        };
        let mut d: Vec<&str> = digits;
        if d.len() <= shift {
            let mut padded = vec!["0"; shift - d.len() + 1];
            padded.extend(d);
            d = padded;
        }
        let cut = d.len() - shift;
        let (mut whole, mut frac) = (d[..cut].to_vec(), d[cut..].to_vec());
        while whole.len() > 1 && t::ch_eq(whole[0], '0') {
            whole.remove(0);
        }
        while frac.last().is_some_and(|g| t::ch_eq(g, '0')) {
            frac.pop();
        }
        let (whole, frac) = (whole.concat(), frac.concat());
        Some(if frac.is_empty() { whole } else { format!("{whole}.{frac}") })
    }
}

/// An EMVCo merchant-presented payload, or None if it isn't one.
#[uniffi::export]
pub fn emv_parse(
    text: String,
    networks: Vec<crate::data::EmvNetwork>,
    currencies: std::collections::HashMap<String, String>,
) -> Option<PaymentInfo> {
    emv::parse(&text, &networks, &currencies)
}

#[uniffi::export]
pub fn epc_parse(text: String) -> Option<PaymentInfo> {
    epc::parse(&text)
}

#[uniffi::export]
pub fn swiss_qr_parse(text: String) -> Option<PaymentInfo> {
    swiss::parse(&text)
}

#[uniffi::export]
pub fn upi_parse(text: String) -> Option<PaymentInfo> {
    upi::parse(&text)
}

#[uniffi::export]
pub fn crypto_uri_parse(text: String, schemes: Vec<crate::data::CryptoScheme>) -> Option<CryptoInfo> {
    crypto_uri::parse(&text, &schemes)
}

#[uniffi::export]
pub fn crypto_address_detect(text: String) -> Option<CryptoInfo> {
    crypto_address::detect(&text)
}
