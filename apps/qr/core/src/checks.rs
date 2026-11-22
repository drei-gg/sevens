//! The offline scam checks (QRCore `Checks/`), the brand lookups they share and the look-alike
//! machinery (skeletons, script mixing, edit distance).

use crate::contract::*;
use crate::data::BundledData;
use crate::peek::HtmlInput;
use crate::text as t;
use crate::url::{HostKind, PublicSuffixList, WebAddress};
use std::collections::{HashMap, HashSet};
use unicode_general_category::GeneralCategory as G;

// MARK: Damerau-Levenshtein

/// Optimal string alignment distance (insert, delete, substitute, adjacent transposition).
pub fn damerau_levenshtein(a: &[char], b: &[char]) -> usize {
    if a.is_empty() {
        return b.len();
    }
    if b.is_empty() {
        return a.len();
    }
    let mut prev2 = vec![0usize; b.len() + 1];
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    let mut cur = vec![0usize; b.len() + 1];
    for i in 1..=a.len() {
        cur[0] = i;
        for j in 1..=b.len() {
            let cost = usize::from(a[i - 1] != b[j - 1]);
            let mut d = (prev[j] + 1).min(cur[j - 1] + 1).min(prev[j - 1] + cost);
            if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] {
                d = d.min(prev2[j - 2] + 1);
            }
            cur[j] = d;
        }
        std::mem::swap(&mut prev2, &mut prev);
        std::mem::swap(&mut prev, &mut cur);
    }
    prev[b.len()]
}

// MARK: Skeleton

/// Confusable skeleton: lowercase, map through `confusables.json` (longest key first), then strip
/// hyphens.
#[derive(Debug, Clone)]
pub struct Skeleton {
    tables: Vec<(usize, HashMap<Vec<char>, String>)>,
}

impl Skeleton {
    pub fn new(confusables: &HashMap<String, String>) -> Self {
        let mut by_length: HashMap<usize, HashMap<Vec<char>, String>> = HashMap::new();
        for (k, v) in confusables {
            let key: Vec<char> = k.chars().collect();
            let n = key.len();
            if n == 0 || n > 8 {
                continue;
            }
            by_length.entry(n).or_default().insert(key, v.clone());
        }
        let mut lengths: Vec<usize> = by_length.keys().copied().collect();
        lengths.sort_by(|a, b| b.cmp(a));
        Skeleton {
            tables: lengths
                .into_iter()
                .map(|n| (n, by_length.remove(&n).unwrap_or_default()))
                .collect(),
        }
    }

    pub fn of(&self, label: &str) -> String {
        let scalars: Vec<char> = t::lower(label).chars().collect();
        let mut out = String::new();
        let mut i = 0;
        while i < scalars.len() {
            let mut matched = false;
            for (length, map) in &self.tables {
                if i + length > scalars.len() {
                    continue;
                }
                if let Some(v) = map.get(&scalars[i..i + length]) {
                    out.push_str(v);
                    i += length;
                    matched = true;
                    break;
                }
            }
            if !matched {
                out.push(scalars[i]);
                i += 1;
            }
        }
        t::graphemes(&out)
            .filter(|g| !(t::ch_eq(g, '-') || t::ch_eq(g, '\u{2010}') || t::ch_eq(g, '\u{2011}')))
            .collect()
    }
}

// MARK: ScriptMix

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Script {
    Latin,
    Greek,
    Cyrillic,
    Armenian,
    Hebrew,
    Arabic,
    Devanagari,
    Bengali,
    Tamil,
    Thai,
    Georgian,
    Hangul,
    Hiragana,
    Katakana,
    Han,
    Bopomofo,
    Other,
}

impl Script {
    pub fn name(self) -> &'static str {
        match self {
            Script::Latin => "Latin",
            Script::Greek => "Greek",
            Script::Cyrillic => "Cyrillic",
            Script::Armenian => "Armenian",
            Script::Hebrew => "Hebrew",
            Script::Arabic => "Arabic",
            Script::Devanagari => "Devanagari",
            Script::Bengali => "Bengali",
            Script::Tamil => "Tamil",
            Script::Thai => "Thai",
            Script::Georgian => "Georgian",
            Script::Hangul => "Hangul",
            Script::Hiragana => "Hiragana",
            Script::Katakana => "Katakana",
            Script::Han => "Han",
            Script::Bopomofo => "Bopomofo",
            Script::Other => "Other",
        }
    }
}

/// Script table for `mixedScript`. Small and in code on purpose.
pub mod script_mix {
    use super::Script::{self, *};
    use std::collections::HashSet;

    const RANGES: &[(Script, &[(u32, u32)])] = &[
        (
            Latin,
            &[
                (0x41, 0x5A),
                (0x61, 0x7A),
                (0xAA, 0xAA),
                (0xBA, 0xBA),
                (0xC0, 0xD6),
                (0xD8, 0xF6),
                (0xF8, 0x2AF),
                (0x1D00, 0x1D25),
                (0x1E00, 0x1EFF),
                (0x2C60, 0x2C7F),
                (0xA720, 0xA7FF),
                (0xFB00, 0xFB06),
                (0xFF21, 0xFF3A),
                (0xFF41, 0xFF5A),
            ],
        ),
        (Greek, &[(0x370, 0x3FF), (0x1F00, 0x1FFF)]),
        (
            Cyrillic,
            &[
                (0x400, 0x52F),
                (0x1C80, 0x1C8F),
                (0x2DE0, 0x2DFF),
                (0xA640, 0xA69F),
            ],
        ),
        (Armenian, &[(0x530, 0x58F)]),
        (Hebrew, &[(0x590, 0x5FF)]),
        (
            Arabic,
            &[
                (0x600, 0x6FF),
                (0x750, 0x77F),
                (0x8A0, 0x8FF),
                (0xFB50, 0xFDFF),
                (0xFE70, 0xFEFF),
            ],
        ),
        (Devanagari, &[(0x900, 0x97F)]),
        (Bengali, &[(0x980, 0x9FF)]),
        (Tamil, &[(0xB80, 0xBFF)]),
        (Thai, &[(0xE00, 0xE7F)]),
        (
            Georgian,
            &[(0x10A0, 0x10FF), (0x1C90, 0x1CBF), (0x2D00, 0x2D2F)],
        ),
        (
            Hangul,
            &[
                (0x1100, 0x11FF),
                (0x3130, 0x318F),
                (0xA960, 0xA97F),
                (0xAC00, 0xD7FF),
                (0xFFA0, 0xFFDC),
            ],
        ),
        (Hiragana, &[(0x3041, 0x309F)]),
        (
            Katakana,
            &[
                (0x30A0, 0x30FA),
                (0x30FD, 0x30FF),
                (0x31F0, 0x31FF),
                (0xFF66, 0xFF9F),
            ],
        ),
        (
            Han,
            &[
                (0x2E80, 0x2FDF),
                (0x3005, 0x3005),
                (0x3007, 0x3007),
                (0x3021, 0x3029),
                (0x3038, 0x303B),
                (0x3400, 0x4DBF),
                (0x4E00, 0x9FFF),
                (0xF900, 0xFAFF),
                (0x20000, 0x2FA1F),
                (0x30000, 0x3134F),
            ],
        ),
        (Bopomofo, &[(0x3100, 0x312F), (0x31A0, 0x31BF)]),
    ];

    /// None for Common and Inherited characters (digits, hyphen, combining marks), which never count.
    pub fn script_of(c: char) -> Option<Script> {
        let v = c as u32;
        if v < 0x80 && !((0x41..=0x5A).contains(&v) || (0x61..=0x7A).contains(&v)) {
            return None;
        }
        if (0x300..=0x36F).contains(&v) || v == 0x30FB || v == 0x30FC {
            return None;
        }
        for (script, rs) in RANGES {
            if rs.iter().any(|&(a, b)| (a..=b).contains(&v)) {
                return Some(*script);
            }
        }
        if c.is_alphabetic() { Some(Other) } else { None }
    }

    pub fn scripts(label: &str) -> HashSet<Script> {
        label.chars().filter_map(script_of).collect()
    }

    pub fn is_mixed(scripts: &HashSet<Script>) -> bool {
        if scripts.len() < 2 {
            return false;
        }
        let allowed: [&[Script]; 3] = [
            &[Latin, Han, Hiragana, Katakana],
            &[Latin, Hangul, Han],
            &[Latin, Han, Bopomofo],
        ];
        !allowed
            .iter()
            .any(|set| scripts.iter().all(|s| set.contains(s)))
    }
}

// MARK: BrandOverrides

/// In-code loosening where `brands.json` is missing an official domain.
pub mod brand_overrides {
    use std::collections::HashMap;

    /// `nearBrand` never fires for these brands.
    pub const NEAR_BRAND_OFF: &[&str] = &[];
    /// Keywords (lowercase) that never match.
    pub const KEYWORD_OFF: &[&str] = &[];

    const REGIONAL: &[(&str, &[&str], &[&str])] = &[
        (
            "Google",
            &["google"],
            &[
                "co.uk", "com.ph", "de", "fr", "es", "it", "ca", "co.jp", "co.in", "com.au",
                "com.br", "com.mx", "nl", "pl", "com.sg", "co.id", "co.kr", "com.tr", "com.ar",
                "co.za", "ae", "com.sa", "com.hk", "com.tw", "co.th", "com.vn", "com.my", "pt",
                "se", "ch", "at", "be", "ie", "co.nz", "ru", "cl", "com.co", "com.pe",
            ],
        ),
        (
            "eBay",
            &["ebay"],
            &[
                "co.uk", "de", "fr", "it", "es", "com.au", "ca", "ph", "com.sg", "com.my", "ie",
                "nl", "at", "ch", "pl", "com.hk",
            ],
        ),
        (
            "PayPal",
            &["paypal"],
            &[
                "co.uk", "de", "fr", "it", "es", "com.au", "ca", "com.br", "ph", "com.mx", "nl",
                "at", "ch", "pl", "com.sg", "co.jp", "com.hk",
            ],
        ),
        (
            "Yahoo",
            &["yahoo"],
            &[
                "co.jp", "co.uk", "fr", "de", "it", "es", "ca", "com.au", "co.in", "com.br",
                "com.tw", "com.hk",
            ],
        ),
    ];

    /// Extra official domains by brand name.
    pub fn extra_domains() -> HashMap<&'static str, Vec<String>> {
        let mut out: HashMap<&'static str, Vec<String>> = HashMap::new();
        for (brand, labels, suffixes) in REGIONAL {
            for l in *labels {
                out.entry(brand)
                    .or_default()
                    .extend(suffixes.iter().map(|s| format!("{l}.{s}")));
            }
        }
        out
    }
}

// MARK: BrandMatcher

#[derive(Debug, Clone)]
pub struct LabelEntry {
    pub label: String,
    pub scalars: Vec<char>,
    pub skeleton: String,
    pub brand: usize,
}

/// Brand lookups shared by `brandInDomain`, `lookalikeBrand`, `nearBrand` and Peek's
/// `secretFormBrandMismatch`. Built once per analyzer.
#[derive(Debug, Clone)]
pub struct BrandMatcher {
    pub brands: Vec<crate::data::Brand>,
    pub skeleton: Skeleton,
    pub bait_words: HashSet<String>,
    pub label_entries: Vec<LabelEntry>,
    pub all_labels: HashSet<String>,
    skeleton_index: HashMap<String, usize>,
    keyword_index: HashMap<String, Vec<usize>>,
    short_keyword_index: HashMap<String, Vec<usize>>,
    keyword_skeleton_index: HashMap<String, usize>,
    long_keywords: Vec<HashSet<String>>,
    own_domains: HashMap<String, HashSet<usize>>,
}

impl BrandMatcher {
    pub fn new(data: &BundledData) -> Self {
        let brands = data.brands.clone();
        let skeleton = Skeleton::new(&data.confusables);
        let bait_words = data.bait_words.iter().map(|w| t::lower(w)).collect();
        let short_keywords: HashSet<String> = brands
            .iter()
            .flat_map(|b| b.keywords.iter())
            .map(|k| t::lower(k))
            .filter(|k| k.chars().count() == 3)
            .collect();
        let extra = brand_overrides::extra_domains();
        let mut m = BrandMatcher {
            brands: vec![],
            skeleton,
            bait_words,
            label_entries: vec![],
            all_labels: HashSet::new(),
            skeleton_index: HashMap::new(),
            keyword_index: HashMap::new(),
            short_keyword_index: HashMap::new(),
            keyword_skeleton_index: HashMap::new(),
            long_keywords: vec![],
            own_domains: HashMap::new(),
        };
        for (i, b) in brands.iter().enumerate() {
            let mut labels: HashSet<String> = HashSet::new();
            let mut longs = HashSet::new();
            let extras = extra.get(b.name.as_str()).cloned().unwrap_or_default();
            for d in b.domains.iter().chain(extras.iter()) {
                let dl = t::lower(d);
                m.own_domains.entry(dl.clone()).or_default().insert(i);
                if let Some(first) = t::split_ch(&dl, '.', true).first() {
                    labels.insert(first.to_string());
                }
            }
            for k in &b.keywords {
                let kl = t::lower(k);
                labels.insert(kl.clone());
                let n = kl.chars().count();
                if brand_overrides::KEYWORD_OFF.contains(&kl.as_str()) {
                    continue;
                }
                if n >= 4 {
                    m.keyword_index.entry(kl.clone()).or_default().push(i);
                    let ks = m.skeleton.of(&kl);
                    longs.insert(kl);
                    m.keyword_skeleton_index.entry(ks).or_insert(i);
                } else if n == 3 {
                    m.short_keyword_index.entry(kl).or_default().push(i);
                }
            }
            m.long_keywords.push(longs);
            let mut sorted: Vec<String> = labels.into_iter().collect();
            sorted.sort();
            for l in sorted.into_iter().filter(|l| !l.is_empty()) {
                // Keywords under four letters never take part in look-alike or near-miss matching.
                if short_keywords.contains(&l) {
                    m.all_labels.insert(l);
                    continue;
                }
                let sk = m.skeleton.of(&l);
                m.label_entries.push(LabelEntry {
                    label: l.clone(),
                    scalars: l.chars().collect(),
                    skeleton: sk.clone(),
                    brand: i,
                });
                m.all_labels.insert(l);
                m.skeleton_index.entry(sk).or_insert(i);
            }
        }
        m.brands = brands;
        m
    }

    pub fn is_own_domain(&self, registrable: &str) -> bool {
        self.own_domains.contains_key(&t::lower(registrable))
    }

    pub fn is_own_domain_of(&self, registrable: Option<&str>, brand: usize) -> bool {
        registrable.is_some_and(|r| {
            self.own_domains
                .get(&t::lower(r))
                .is_some_and(|s| s.contains(&brand))
        })
    }

    /// The brand whose label has the same skeleton, if any.
    pub fn brand_with_skeleton(&self, sk: &str) -> Option<usize> {
        self.skeleton_index.get(sk).copied()
    }

    /// The brand whose keyword (4+ letters) has this skeleton.
    pub fn brand_with_keyword_skeleton(&self, sk: &str) -> Option<usize> {
        self.keyword_skeleton_index.get(sk).copied()
    }

    /// Splits labels on `.`, `-`, `_` and letter/digit boundaries; lowercase.
    pub fn tokens(labels: &[String]) -> Vec<String> {
        let mut out = Vec::new();
        for label in labels {
            let mut cur = String::new();
            let mut cur_is_digit = false;
            for c in label.chars() {
                let is_digit = t::is_decimal(c);
                let gc = t::general_category(c);
                let is_word = is_digit
                    || c.is_alphabetic()
                    || gc == G::NonspacingMark
                    || gc == G::SpacingMark;
                if !is_word {
                    if !cur.is_empty() {
                        out.push(t::lower(&std::mem::take(&mut cur)));
                    }
                    continue;
                }
                if !cur.is_empty() && is_digit != cur_is_digit {
                    out.push(t::lower(&std::mem::take(&mut cur)));
                }
                cur.push(c);
                cur_is_digit = is_digit;
            }
            if !cur.is_empty() {
                out.push(t::lower(&cur));
            }
        }
        out
    }

    /// Brands (by index) with a keyword equal to a token. Ambiguous brands count only when a bait
    /// word is among `bait_context` (default: the tokens themselves).
    pub fn brands_matching(
        &self,
        tokens: &[String],
        bait_context: Option<&[String]>,
    ) -> Vec<usize> {
        let has_bait = bait_context
            .unwrap_or(tokens)
            .iter()
            .any(|w| self.bait_words.contains(w));
        let mut found = Vec::new();
        for tk in tokens {
            let Some(idxs) = self.keyword_index.get(tk) else {
                continue;
            };
            for &i in idxs {
                if found.contains(&i) || (self.brands[i].ambiguous && !has_bait) {
                    continue;
                }
                found.push(i);
            }
        }
        found
    }

    /// Brands with a three-letter keyword that is a whole token of the host (split on `.`, `-`,
    /// `_`), while a bait word is another token of the same host.
    pub fn brands_matching_short(&self, host_labels: &[String]) -> Vec<usize> {
        if self.short_keyword_index.is_empty() {
            return vec![];
        }
        let mut words: Vec<String> = Vec::new();
        for label in host_labels {
            let l = t::lower(label);
            words.extend(
                t::split(&l, |g| t::ch_eq(g, '-') || t::ch_eq(g, '_'), true)
                    .into_iter()
                    .map(String::from),
            );
        }
        let mut found = Vec::new();
        for (pos, w) in words.iter().enumerate() {
            let Some(idxs) = self.short_keyword_index.get(w) else {
                continue;
            };
            let has_bait = words
                .iter()
                .enumerate()
                .any(|(o, e)| o != pos && self.bait_words.contains(e));
            if !has_bait {
                continue;
            }
            for &i in idxs {
                if !found.contains(&i) {
                    found.push(i);
                }
            }
        }
        found
    }

    /// True when `label` is exactly one of brand `i`'s keywords of four or more letters and the
    /// brand isn't marked ambiguous.
    pub fn is_exact_keyword(&self, label: &str, i: usize) -> bool {
        !self.brands[i].ambiguous && self.long_keywords[i].contains(&t::lower(label))
    }
}

// MARK: CheckContext

/// What every check needs; built once per analyzer.
#[derive(Debug, Clone)]
pub struct CheckContext {
    pub data: std::sync::Arc<BundledData>,
    pub brands: BrandMatcher,
    pub risky_tlds: HashSet<String>,
    pub bait_words: Vec<String>,
}

impl CheckContext {
    pub fn new(data: std::sync::Arc<BundledData>) -> Self {
        CheckContext {
            risky_tlds: data.risky_tlds.iter().map(|r| t::lower(&r.tld)).collect(),
            bait_words: data.bait_words.iter().map(|w| t::lower(w)).collect(),
            brands: BrandMatcher::new(&data),
            data,
        }
    }
}

// MARK: embedded links

/// `http://`, `https://` and `www.` runs up to whitespace or `< > "`, trailing `. , ) ]` dropped,
/// at most 10.
pub mod embedded_links {
    pub const MAX_LINKS: usize = 10;

    pub fn find(text: Option<&str>) -> Vec<String> {
        let Some(text) = text.filter(|s| !s.is_empty()) else {
            return vec![];
        };
        let s: Vec<char> = text.chars().collect();
        let mut out: Vec<String> = Vec::new();
        let mut i = 0;
        while i < s.len() && out.len() < MAX_LINKS {
            let Some(plen) = prefix_length(i, &s) else {
                i += 1;
                continue;
            };
            let mut j = i;
            while j < s.len() && !is_terminator(s[j]) {
                j += 1;
            }
            while j > i + plen && ".,)]".contains(s[j - 1]) {
                j -= 1;
            }
            if j > i + plen {
                let link: String = s[i..j].iter().collect();
                if !out.contains(&link) {
                    out.push(link);
                }
                i = j;
            } else {
                i += plen;
            }
        }
        out
    }

    /// Several fields at once, in order, deduplicated, capped at 10.
    pub fn merge(lists: &[Vec<String>]) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for l in lists.iter().flatten() {
            if !out.contains(l) {
                out.push(l.clone());
            }
        }
        out.truncate(MAX_LINKS);
        out
    }

    fn is_terminator(c: char) -> bool {
        c.is_whitespace() || c == '<' || c == '>' || c == '"' || (c as u32) < 0x20
    }

    fn prefix_length(i: usize, s: &[char]) -> Option<usize> {
        if matches(b"https://", i, s) {
            return Some(8);
        }
        if matches(b"http://", i, s) {
            return Some(7);
        }
        if matches(b"www.", i, s) {
            if i > 0 {
                let p = s[i - 1];
                if p.is_alphabetic() || crate::text::has_numeric_type(p) || p == '.' || p == '-' {
                    return None;
                }
            }
            return Some(4);
        }
        None
    }

    fn matches(p: &[u8], i: usize, s: &[char]) -> bool {
        if i + p.len() > s.len() {
            return false;
        }
        p.iter().enumerate().all(|(k, &b)| {
            let c = s[i + k] as u32;
            let lower = if (65..=90).contains(&c) { c + 32 } else { c };
            lower == b as u32
        })
    }
}

/// Links inside free text: `http://`, `https://` and `www.` runs, at most 10.
#[uniffi::export]
pub fn embedded_links_in(text: String) -> Vec<String> {
    embedded_links::find(Some(&text))
}

// MARK: the checks

pub fn userinfo_trick(link: &LinkInfo) -> Option<Signal> {
    if link.scheme != "http" && link.scheme != "https" {
        return None;
    }
    let u = link.userinfo.as_deref().filter(|u| !u.is_empty())?;
    Some(Signal::with(
        SignalId::UserinfoTrick,
        &[("userinfo", u), ("host", &link.host_unicode)],
    ))
}

pub fn mixed_script(address: &WebAddress) -> Option<Signal> {
    let link = &address.link;
    if address.host_kind != HostKind::Domain {
        return None;
    }
    for label in link.unicode_labels() {
        let scripts = script_mix::scripts(&label);
        if script_mix::is_mixed(&scripts) {
            let mut names: Vec<&str> = scripts.iter().map(|s| s.name()).collect();
            names.sort();
            return Some(Signal::with(
                SignalId::MixedScript,
                &[("label", &label), ("scripts", &names.join("+"))],
            ));
        }
    }
    if address.has_malformed_label {
        return Some(Signal::with(
            SignalId::MixedScript,
            &[("label", &link.host_unicode), ("scripts", "malformed")],
        ));
    }
    None
}

/// `lookalikeBrand`: the registrable domain's first label, or any single word of the address,
/// reads as a brand keyword of four or more letters once look-alike characters are folded,
/// without being that keyword.
pub fn lookalike_brand(link: &LinkInfo, brands: &BrandMatcher) -> Option<Signal> {
    let reg = link.registrable_domain.as_deref()?;
    if brands.is_own_domain(reg) {
        return None;
    }
    if let Some(label) = link.registrable_first_label()
        && !brands.all_labels.contains(&label)
        && let Some(i) = brands.brand_with_skeleton(&brands.skeleton.of(&label))
    {
        return Some(Signal::with(
            SignalId::LookalikeBrand,
            &[("brand", &brands.brands[i].name), ("domain", reg)],
        ));
    }
    let mut host_words: Option<Vec<String>> = None;
    for label in link.labels_left_of_suffix() {
        let l = t::lower(&label);
        for w in t::split(&l, |g| t::ch_eq(g, '-') || t::ch_eq(g, '_'), true) {
            if brands.all_labels.contains(w) {
                continue;
            }
            let Some(i) = brands.brand_with_keyword_skeleton(&brands.skeleton.of(w)) else {
                continue;
            };
            if brands.brands[i].ambiguous {
                let ctx =
                    host_words.get_or_insert_with(|| BrandMatcher::tokens(&link.unicode_labels()));
                if !ctx.iter().any(|w| brands.bait_words.contains(w)) {
                    continue;
                }
            }
            return Some(Signal::with(
                SignalId::LookalikeBrand,
                &[("brand", &brands.brands[i].name), ("domain", reg)],
            ));
        }
    }
    None
}

/// `brandInDomain` (strong), except `<keyword>.<common ending>`, which is `brandOtherEnding`.
pub fn brand_in_domain(
    address: &WebAddress,
    brands: &BrandMatcher,
    risky: &HashSet<String>,
) -> Option<Signal> {
    let link = &address.link;
    let reg = link.registrable_domain.as_deref()?;
    let tokens = BrandMatcher::tokens(&link.labels_left_of_suffix());
    let mut matched = brands.brands_matching(&tokens, None);
    for i in brands.brands_matching_short(&link.unicode_labels()) {
        if !matched.contains(&i) {
            matched.push(i);
        }
    }
    let mut gentle = None;
    for i in matched {
        if brands.is_own_domain_of(Some(reg), i) {
            continue;
        }
        if is_other_ending(address, i, brands, risky) {
            if gentle.is_none() {
                gentle = Some(Signal::with(
                    SignalId::BrandOtherEnding,
                    &[("brand", &brands.brands[i].name), ("domain", reg)],
                ));
            }
        } else {
            return Some(Signal::with(
                SignalId::BrandInDomain,
                &[("brand", &brands.brands[i].name), ("domain", reg)],
            ));
        }
    }
    gentle
}

fn is_other_ending(
    address: &WebAddress,
    i: usize,
    brands: &BrandMatcher,
    risky: &HashSet<String>,
) -> bool {
    let link = &address.link;
    if !address.suffix_is_known || link.suffix_is_private {
        return false;
    }
    let Some(suffix) = link.public_suffix.as_deref().map(t::lower) else {
        return false;
    };
    let Some(label) = link.registrable_first_label() else {
        return false;
    };
    if !brands.is_exact_keyword(&label, i) {
        return false;
    }
    let Some(last_label) = t::split_ch(&suffix, '.', true)
        .last()
        .map(|s| s.to_string())
    else {
        return false;
    };
    let country = last_label.chars().count() == 2
        && last_label
            .chars()
            .all(|c| c.is_ascii() && c.is_alphabetic());
    if !country && !["com", "net", "org"].contains(&suffix.as_str()) {
        return false;
    }
    !risky.contains(&suffix) && !risky.contains(&last_label)
}

/// Not a skeleton match, brand label of 6+ characters, distance exactly 1 from the registrable
/// domain's first label.
pub fn near_brand(link: &LinkInfo, brands: &BrandMatcher) -> Option<Signal> {
    let reg = link.registrable_domain.as_deref()?;
    let label = link.registrable_first_label()?;
    if brands.is_own_domain(reg) || brands.all_labels.contains(&label) {
        return None;
    }
    if brands
        .brand_with_skeleton(&brands.skeleton.of(&label))
        .is_some()
    {
        return None;
    }
    let scalars: Vec<char> = label.chars().collect();
    for e in &brands.label_entries {
        if e.scalars.len() < 6 || e.scalars.len().abs_diff(scalars.len()) > 1 {
            continue;
        }
        if brand_overrides::NEAR_BRAND_OFF.contains(&brands.brands[e.brand].name.as_str()) {
            continue;
        }
        if damerau_levenshtein(&scalars, &e.scalars) == 1 {
            return Some(Signal::with(
                SignalId::NearBrand,
                &[("brand", &brands.brands[e.brand].name), ("domain", reg)],
            ));
        }
    }
    None
}

pub fn ip_host(address: &WebAddress) -> Option<Signal> {
    if address.host_kind == HostKind::Domain {
        None
    } else {
        Some(Signal::with(
            SignalId::IpHost,
            &[("host", &address.link.host_ascii)],
        ))
    }
}

pub fn shortener(link: &LinkInfo, shorteners: &HashSet<String>) -> Option<Signal> {
    if link.is_ip_address {
        return None;
    }
    if shorteners.contains(&link.host_ascii) {
        return Some(Signal::with(
            SignalId::Shortener,
            &[("host", &link.host_ascii)],
        ));
    }
    let r = t::lower(link.registrable_domain.as_deref()?);
    if shorteners.contains(&r) {
        Some(Signal::with(SignalId::Shortener, &[("host", &r)]))
    } else {
        None
    }
}

pub fn plain_http(link: &LinkInfo) -> Option<Signal> {
    (link.scheme == "http").then(|| Signal::new(SignalId::PlainHttp))
}

pub fn risky_tld(link: &LinkInfo, tlds: &HashSet<String>) -> Option<Signal> {
    if link.is_ip_address {
        return None;
    }
    let last = t::split_ch(&link.host_ascii, '.', true).last()?.to_string();
    tlds.contains(&last)
        .then(|| Signal::with(SignalId::RiskyTld, &[("tld", &last)]))
}

pub fn unknown_ending(address: &WebAddress) -> Option<Signal> {
    (address.host_kind == HostKind::Domain
        && !address.suffix_is_known
        && address.link.registrable_domain.is_some())
    .then(|| Signal::new(SignalId::UnknownEnding))
}

pub fn deep_subdomains(link: &LinkInfo) -> Option<Signal> {
    let n = link.subdomain_labels().len();
    (n >= 4).then(|| Signal::with(SignalId::DeepSubdomains, &[("count", &n.to_string())]))
}

/// A bait word in host or path, and (path over 150 characters, or 10+ percent-escapes in the
/// path, or another http URL inside the path; the query and fragment never count).
pub fn obfuscated_path(link: &LinkInfo, bait_words: &[String]) -> Option<Signal> {
    let host = t::lower(&link.host_unicode);
    let path = t::lower(&link.path_and_query);
    let bait = bait_words
        .iter()
        .find(|w| t::contains(&host, w) || t::contains(&path, w))?;
    let raw_path = t::prefix_while(&link.path_and_query, |g| {
        !t::ch_eq(g, '?') && !t::ch_eq(g, '#')
    });
    let path_only = t::lower(raw_path);
    let long = t::count(raw_path) > 150;
    let escapes = percent_escapes(raw_path);
    let nested = ["http://", "https://", "http%3a%2f%2f", "https%3a%2f%2f"]
        .iter()
        .any(|p| t::contains(&path_only, p));
    if !(long || escapes >= 10 || nested) {
        return None;
    }
    Some(Signal::with(SignalId::ObfuscatedPath, &[("word", bait)]))
}

pub fn percent_escapes(s: &str) -> usize {
    let u = s.as_bytes();
    let (mut n, mut i) = (0, 0);
    while i + 2 < u.len() {
        if u[i] == b'%' && u[i + 1].is_ascii_hexdigit() && u[i + 2].is_ascii_hexdigit() {
            n += 1;
            i += 3;
        } else {
            i += 1;
        }
    }
    n
}

pub fn assumed_https(address: &WebAddress) -> Option<Signal> {
    address
        .assumed_https
        .then(|| Signal::new(SignalId::AssumedHttps))
}

pub const DANGEROUS_SCHEMES: &[&str] = &[
    "javascript",
    "data",
    "file",
    "vbscript",
    "blob",
    "itms-services",
];

pub fn dangerous_scheme(scheme: &str) -> Option<Signal> {
    DANGEROUS_SCHEMES
        .contains(&scheme)
        .then(|| Signal::with(SignalId::DangerousScheme, &[("scheme", scheme)]))
}

pub fn unknown_scheme(scheme: &str) -> Signal {
    Signal::with(SignalId::UnknownScheme, &[("scheme", scheme)])
}

pub fn open_wifi(info: &WiFiInfo) -> Option<Signal> {
    info.is_open().then(|| Signal::new(SignalId::OpenWiFi))
}

pub fn payment_checksum(info: &PaymentInfo) -> Option<Signal> {
    (info.checksum == PaymentChecksum::Invalid).then(|| {
        Signal::with(
            SignalId::PaymentChecksumFail,
            &[("format", info.format.raw())],
        )
    })
}

/// Every offline check that applies to an http/https address, in stable order.
pub fn link_signals(address: &WebAddress, ctx: &CheckContext) -> Vec<Signal> {
    let link = &address.link;
    let out: Vec<Signal> = [
        userinfo_trick(link),
        mixed_script(address),
        lookalike_brand(link, &ctx.brands),
        brand_in_domain(address, &ctx.brands, &ctx.risky_tlds),
        near_brand(link, &ctx.brands),
        ip_host(address),
        shortener(link, &ctx.data.shorteners),
        plain_http(link),
        risky_tld(link, &ctx.risky_tlds),
        unknown_ending(address),
        deep_subdomains(link),
        obfuscated_path(link, &ctx.bait_words),
        assumed_https(address),
    ]
    .into_iter()
    .flatten()
    .collect();
    in_stable_order(&out)
}

// MARK: Peek page checks

/// `asksForSecret`: "password", "code" or "card".
pub fn asks_for_secret(inputs: &[HtmlInput]) -> Option<&'static str> {
    for input in inputs {
        let ty = input
            .input_type
            .as_deref()
            .map(t::lower)
            .unwrap_or_default();
        let auto_l = input
            .autocomplete
            .as_deref()
            .map(t::lower)
            .unwrap_or_default();
        let auto = t::split_ch(&auto_l, ' ', true);
        if ty == "password"
            || auto
                .iter()
                .any(|a| ["current-password", "new-password"].contains(a))
        {
            return Some("password");
        }
        if auto.contains(&"one-time-code") {
            return Some("code");
        }
        if auto.iter().any(|a| ["cc-number", "cc-csc"].contains(a)) {
            return Some("card");
        }
        if ty == "hidden" {
            continue;
        }
        let hay: Vec<String> = [&input.name, &input.id, &input.placeholder]
            .iter()
            .filter_map(|v| v.as_deref().map(t::lower))
            .collect();
        for h in &hay {
            if ["card number", "cardnumber", "cvv", "cvc"]
                .iter()
                .any(|w| t::contains(h, w))
            {
                return Some("card");
            }
            if ["otp", "mpin", "passcode", "verification code"]
                .iter()
                .any(|w| t::contains(h, w))
                || contains_word("pin", h)
            {
                return Some("code");
            }
        }
    }
    None
}

/// `\bpin\b`
pub fn contains_word(word: &str, text: &str) -> bool {
    let s: Vec<char> = text.chars().collect();
    let w: Vec<char> = word.chars().collect();
    if s.len() < w.len() {
        return false;
    }
    let is_word = |c: char| c.is_alphabetic() || t::has_numeric_type(c) || c == '_';
    (0..=s.len() - w.len()).any(|i| {
        s[i..i + w.len()] == w[..]
            && !(i > 0 && is_word(s[i - 1]))
            && !(i + w.len() < s.len() && is_word(s[i + w.len()]))
    })
}

/// A page that asks for a secret, whose title or host names a brand that doesn't own the final
/// domain.
pub fn secret_form_brand_mismatch(
    title: Option<&str>,
    final_link: &LinkInfo,
    brands: &BrandMatcher,
) -> Option<String> {
    let lt = t::lower(title.unwrap_or(""));
    let title_tokens: Vec<String> = t::split(&lt, |g| !t::ch_is_letter(g), true)
        .into_iter()
        .map(String::from)
        .collect();
    let host_tokens = BrandMatcher::tokens(&final_link.unicode_labels());
    let all: Vec<String> = title_tokens.into_iter().chain(host_tokens).collect();
    let found = brands.brands_matching(&all, Some(&all));
    let first = *found.first()?;
    if found
        .iter()
        .any(|&i| brands.is_own_domain_of(final_link.registrable_domain.as_deref(), i))
    {
        return None;
    }
    Some(brands.brands[first].name.clone())
}

/// The host of the first form whose action resolves to a different registrable domain.
pub fn form_posts_elsewhere(
    actions: &[Option<String>],
    final_link: &LinkInfo,
    psl: &PublicSuffixList,
) -> Option<String> {
    let page = final_link
        .registrable_domain
        .as_deref()
        .unwrap_or(&final_link.host_ascii);
    for action in actions.iter().flatten().filter(|a| !a.is_empty()) {
        let Some(target) = WebAddress::resolve(action, final_link, psl) else {
            continue;
        };
        if target
            .link
            .registrable_domain
            .as_deref()
            .unwrap_or(&target.link.host_ascii)
            != page
        {
            return Some(target.link.host_unicode);
        }
    }
    None
}
