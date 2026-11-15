//! Swift parity: DataShapeTests, ListsBundleTests and ListsLoadTests (with ListsBundleSupport).
//! `ListsStore` (file I/O) stays native, so its two store tests stay with the platforms.

mod common;
use qr_core::data::{self, ListsBundle, ListsFiles, OrderedStrings, RdapFile, resource};
use qr_core::text::lower;
use qr_core::*;
use std::collections::{HashMap, HashSet};

fn decode<T: for<'de> serde::Deserialize<'de>>(name: &str) -> T {
    serde_json::from_slice(resource(name).unwrap()).unwrap()
}

// MARK: ListsBundleSupport.swift

fn files() -> ListsFiles {
    ListsFiles {
        brands: decode("brands.json"),
        shorteners: decode("shorteners.json"),
        risky_tlds: decode("risky-tlds.json"),
        bait_words: decode("bait-words.json"),
        payment_networks: decode("payment-networks.json"),
        confusables: decode::<OrderedStrings>("confusables.json"),
        public_suffix_list: String::from_utf8(resource("public_suffix_list.dat").unwrap().to_vec()).unwrap(),
        rdap: decode::<RdapFile>("rdap-dns.json"),
        tracking_params: Some(decode("tracking-params.json")),
    }
}

fn bundle(version: i64, built: &str) -> ListsBundle {
    ListsBundle { schema: data::CURRENT_SCHEMA, version, built: built.into(), files: files() }
}

fn bytes(b: &ListsBundle) -> Vec<u8> {
    b.encode()
}

fn shipped() -> BundledData {
    BundledData::load()
}

fn newer() -> i64 {
    shipped().lists.version + 1
}

fn bad(file: &str) -> Result<(), ListsBundleError> {
    Err(ListsBundleError::BadShape { file: file.into() })
}

// MARK: DataShapeTests.swift

#[test]
fn brands_have_right_count() {
    let n = shipped().brands.len();
    assert!((120..=200).contains(&n), "{n}");
}

#[test]
fn brands_have_domains_and_lowercase_keywords() {
    let d = shipped();
    let bait: HashSet<&String> = d.bait_words.iter().collect();
    let mut seen: HashMap<String, String> = HashMap::new();
    for b in &d.brands {
        assert!(!b.name.is_empty());
        assert!(!b.domains.is_empty(), "{}", b.name);
        assert!(!b.category.is_empty() && b.category == lower(&b.category));
        assert!(!b.region.is_empty() && b.region == lower(&b.region));
        for dm in &b.domains {
            assert!(*dm == lower(dm) && dm.contains('.') && !dm.contains('/') && !dm.contains(' '), "{}: {dm}", b.name);
        }
        for k in &b.keywords {
            assert_eq!(*k, lower(k));
            assert!(k.chars().count() >= 3);
            assert!(k.chars().count() >= 4 || b.ambiguous, "{}: {k}", b.name);
            assert!(k.chars().all(|c| c.is_lowercase() || qr_core::text::has_numeric_type(c)), "{k}");
            assert!(k.is_ascii());
            assert!(!bait.contains(k));
            if let Some(other) = seen.get(k) {
                panic!("keyword {k} in {other} and {}", b.name);
            }
            seen.insert(k.clone(), b.name.clone());
        }
    }
}

#[test]
fn brand_domains_belong_to_one_brand() {
    let mut owner: HashMap<&str, &str> = HashMap::new();
    let d = shipped();
    for b in &d.brands {
        for dm in &b.domains {
            if let Some(o) = owner.get(dm.as_str()) {
                assert_eq!(*o, b.name, "domain {dm}");
            }
            owner.insert(dm, &b.name);
        }
        assert_eq!(b.domains.iter().collect::<HashSet<_>>().len(), b.domains.len(), "{}", b.name);
    }
}

#[test]
fn brands_know_their_secondary_domains() {
    let d = shipped();
    let domains = |name: &str| -> HashSet<String> {
        d.brands.iter().find(|b| b.name == name).map(|b| b.domains.iter().cloned().collect()).unwrap_or_default()
    };
    let has = |name: &str, ds: &[&str]| ds.iter().all(|x| domains(name).contains(*x));
    assert!(has("PayPal", &["paypal.com", "paypal.me"]));
    assert!(has("WhatsApp", &["whatsapp.com", "wa.me", "whatsapp.net"]));
    assert!(has("Facebook", &["fb.me", "m.me"]));
    assert!(has("Microsoft", &["microsoft.de", "microsoft.co.uk"]));
    assert!(has("Netflix", &["netflix.com", "netflix.co"]));
    assert!(has("Mastercard", &["mastercard.com.ph"]));
    assert!(has("Google", &["google.co.uk", "google.com.ph"]));
    assert!(has("Amazon", &["amazon.de", "amazon.co.uk"]));
    assert!(!domains("Netflix").contains("netflix.xyz"));
    assert!(!domains("Facebook").contains("facebook.co.uk"));
    assert!(!domains("GCash").contains("gcash.ph"));
}

#[test]
fn brands_have_three_letter_keywords_for_the_most_impersonated_short_names() {
    let d = shipped();
    for (name, keyword) in [("DHL", "dhl"), ("BPI", "bpi"), ("BDO Unibank", "bdo"), ("UPS", "ups")] {
        let b = d.brands.iter().find(|b| b.name == name).unwrap_or_else(|| panic!("{name}"));
        assert!(b.keywords.iter().any(|k| k == keyword), "{name}");
        assert!(b.ambiguous, "{name}");
    }
}

#[test]
fn brand_names_are_unique() {
    let names: Vec<String> = shipped().brands.iter().map(|b| lower(&b.name)).collect();
    assert_eq!(names.iter().collect::<HashSet<_>>().len(), names.len());
}

#[test]
fn brands_span_several_regions_and_categories() {
    let d = shipped();
    let regions: HashSet<&str> = d.brands.iter().map(|b| b.region.as_str()).collect();
    assert!(["ph", "us", "in", "br"].iter().all(|r| regions.contains(r)));
    let cats: HashSet<&str> = d.brands.iter().map(|b| b.category.as_str()).collect();
    assert!(["bank", "courier", "payments", "telecom", "government", "marketplace", "crypto"].iter().all(|c| cats.contains(c)));
}

#[test]
fn shorteners_are_lowercase_and_unique() {
    let s = shipped().shorteners;
    assert!(s.len() >= 60);
    assert!(s.iter().all(|x| *x == lower(x) && x.contains('.') && !x.contains('/')));
    assert!(s.contains("bit.ly"));
    assert_eq!(decode::<Vec<String>>("shorteners.json").len(), s.len());
}

#[test]
fn risky_tlds_are_sourced() {
    let tl = shipped().risky_tlds;
    assert!((15..=40).contains(&tl.len()));
    assert_eq!(tl.iter().map(|r| &r.tld).collect::<HashSet<_>>().len(), tl.len());
    for r in &tl {
        assert!(!r.tld.is_empty() && r.tld == lower(&r.tld) && !r.tld.contains('.') && r.tld.is_ascii());
        assert!(r.source.starts_with("https://") && r.source.chars().count() > 12);
    }
}

#[test]
fn bait_words_are_ascii_and_unique() {
    let raw = shipped().bait_words;
    assert!((30..=60).contains(&raw.len()));
    let set: HashSet<&str> = raw.iter().map(String::as_str).collect();
    assert_eq!(set.len(), raw.len());
    assert!(raw.iter().all(|w| *w == lower(w) && !w.is_empty() && w.is_ascii()));
    assert!(["login", "verify", "secure", "account", "tracking", "delivery", "parcel", "online"].iter().all(|w| set.contains(w)));
}

#[test]
fn tracking_params_are_sourced_and_well_formed() {
    let list = shipped().tracking_params;
    assert!(list.len() >= 15);
    let names: HashSet<&str> = list.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(names.len(), list.len());
    for p in &list {
        let body = p.name.strip_suffix('*').unwrap_or(&p.name);
        assert!(!body.is_empty() && body.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_'), "{}", p.name);
        assert!(p.source.starts_with("https://") && p.source.chars().count() > 12 && !p.source.contains(' '));
    }
    for n in ["utm_*", "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid", "yclid", "twclid", "ttclid", "li_fat_id", "_hsenc", "_hsmi", "mkt_tok"] {
        assert!(names.contains(n), "{n}");
    }
    assert!(list.iter().find(|p| p.name == "utm_*").unwrap().matches("UTM_Source"));
    assert_eq!(decode::<Vec<TrackingParam>>("tracking-params.json").len(), list.len());
}

#[test]
fn confusables_are_lowercase_ascii_and_have_the_pairs() {
    let c = shipped().confusables;
    assert!(c.len() > 40);
    for (k, v) in &c {
        assert!(!k.is_empty() && *k == lower(k));
        assert!(!v.is_empty() && *v == lower(v) && v.is_ascii());
    }
    for (k, v) in [("0", "o"), ("1", "l"), ("i", "l"), ("rn", "m"), ("vv", "w"), ("\u{0430}", "a"), ("\u{03BF}", "o"), ("\u{FF41}", "a")] {
        assert_eq!(c.get(k).map(String::as_str), Some(v));
    }
}

#[test]
fn emv_networks_are_sourced() {
    let n = shipped().payment_networks.emv;
    assert!(n.len() >= 5);
    assert_eq!(n.iter().map(|e| lower(&e.id)).collect::<HashSet<_>>().len(), n.len());
    for e in &n {
        assert!(!e.id.is_empty() && !e.name.is_empty());
        assert!(e.country.chars().count() == 2 && e.country == e.country.to_uppercase());
        assert!(["person", "business", "unknown"].contains(&e.party.as_str()));
        assert!(e.source.starts_with("https://") && e.source.chars().count() > 12);
    }
}

#[test]
fn crypto_schemes_are_lowercase_and_unique() {
    let s = shipped().payment_networks.crypto_schemes;
    let set: HashSet<&str> = s.iter().map(|x| x.scheme.as_str()).collect();
    assert_eq!(set.len(), s.len());
    assert!(s.iter().all(|x| x.scheme == lower(&x.scheme) && !x.scheme.contains(':') && !x.name.is_empty()));
    assert!(["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"].iter().all(|x| set.contains(x)));
}

#[test]
fn currencies_are_iso4217() {
    let c = shipped().currencies;
    assert!(c.len() >= 150);
    for (k, v) in &c {
        assert!(k.len() == 3 && k.bytes().all(|b| b.is_ascii_digit()), "{k}");
        assert!(v.len() == 3 && v.bytes().all(|b| b.is_ascii_uppercase()), "{v}");
    }
    assert_eq!(c.get("608").map(String::as_str), Some("PHP"));
    assert_eq!(c.get("840").map(String::as_str), Some("USD"));
    assert_eq!(c.get("978").map(String::as_str), Some("EUR"));
}

// MARK: ListsBundleTests.swift

#[test]
fn lists_bundled_files_form_a_valid_bundle() {
    assert_eq!(data::validate(&bundle(newer(), "2027-01-02")), Ok(()));
}

#[test]
fn lists_bundle_round_trips_through_json() {
    let b = bundle(newer(), "2027-01-02");
    assert_eq!(ListsBundle::decode(&bytes(&b)), Some(b));
}

#[test]
fn lists_unknown_schema_is_refused() {
    let mut b = bundle(newer(), "2027-01-02");
    b.schema = 2;
    assert_eq!(data::validate(&b), Err(ListsBundleError::UnknownSchema { schema: 2 }));
}

#[test]
fn lists_bad_built_date_is_refused() {
    let mut b = bundle(newer(), "yesterday");
    assert_eq!(data::validate(&b), bad("built"));
    b.built = "2026-13-40".into();
    assert_eq!(data::validate(&b), bad("built"));
}

#[test]
fn lists_each_broken_file_names_itself() {
    let broken = |change: &dyn Fn(&mut ListsFiles)| {
        let mut b = bundle(newer(), "2027-01-02");
        change(&mut b.files);
        data::validate(&b)
    };
    let tp = |name: &str, source: &str| Some(vec![TrackingParam { name: name.into(), source: source.into() }]);
    assert_eq!(broken(&|f| f.brands.truncate(10)), bad("brands"));
    assert_eq!(broken(&|f| f.brands[0].keywords = vec!["ab".into()]), bad("brands"));
    assert_eq!(broken(&|f| f.brands[0].keywords = vec!["Paypal".into()]), bad("brands"));
    assert_eq!(broken(&|f| f.brands[0].domains = vec!["no-dot".into()]), bad("brands"));
    assert_eq!(broken(&|f| f.shorteners = vec!["bit.ly".into()]), bad("shorteners"));
    assert_eq!(broken(&|f| f.shorteners[1] = f.shorteners[0].clone()), bad("shorteners"));
    assert_eq!(broken(&|f| f.risky_tlds[0].source = "http://x".into()), bad("riskyTLDs"));
    assert_eq!(broken(&|f| f.bait_words.clear()), bad("baitWords"));
    assert_eq!(broken(&|f| f.payment_networks.emv[0].party = "robot".into()), bad("paymentNetworks"));
    assert_eq!(broken(&|f| f.confusables.set("0", "O")), bad("confusables"));
    assert_eq!(broken(&|f| f.public_suffix_list = "com".into()), bad("publicSuffixList"));
    assert_eq!(broken(&|f| f.rdap.services.clear()), bad("rdap"));
    assert_eq!(broken(&|f| f.tracking_params = tp("UTM_*", "https://example.org/utm")), bad("trackingParams"));
    assert_eq!(broken(&|f| f.tracking_params = tp("utm_*", "http://example.org")), bad("trackingParams"));
}

#[test]
fn lists_tracking_names_allow_letters_digits_underscore_and_one_star() {
    let p = |n: &str, s: &str| TrackingParam { name: n.into(), source: s.into() };
    let mut b = bundle(newer(), "2027-01-02");
    b.files.tracking_params = Some(vec![
        p("utm_*", "https://example.org/utm"),
        p("fbclid", "https://example.org/fb"),
        p("mc_cid2", "https://example.org/mc"),
    ]);
    assert_eq!(data::validate(&b), Ok(()));
    b.files.tracking_params = Some(vec![p("a-b", "https://example.org/x")]);
    assert_eq!(data::validate(&b), bad("trackingParams"));
    b.files.tracking_params = Some(vec![p("*", "https://example.org/x")]);
    assert_eq!(data::validate(&b), bad("trackingParams"));
}

#[test]
fn lists_bundle_data_keeps_currencies_and_takes_the_header() {
    let b = bundle(newer(), "2027-01-02");
    let s = shipped();
    let d = BundledData::from_bundle(&b, &s);
    assert_eq!(d.lists, ListsVersion { schema: 1, version: newer(), built: "2027-01-02".into() });
    assert_eq!(d.currencies, s.currencies);
    assert_eq!(d.brands, b.files.brands);
    assert_eq!(d.shorteners, b.files.shorteners.iter().cloned().collect());
    assert!(d.rdap.base_url("com").is_some());
    assert_eq!(d.public_suffixes.match_host("www.example.co.uk").registrable_domain.as_deref(), Some("example.co.uk"));
}

#[test]
fn lists_missing_tracking_params_fall_back_to_the_bundled_ones() {
    let mut b = bundle(newer(), "2027-01-02");
    b.files.tracking_params = None;
    assert_eq!(data::validate(&b), Ok(()));
    assert_eq!(BundledData::from_bundle(&b, &shipped()).tracking_params, shipped().tracking_params);
}

// MARK: ListsLoadTests.swift

#[test]
fn lists_no_download_gives_the_bundled_lists() {
    assert_eq!(BundledData::load_downloaded(None).lists, shipped().lists);
    assert_eq!(BundledData::bundled_lists_version(), shipped().lists);
}

#[test]
fn lists_newer_download_wins() {
    let v = shipped().lists.version + 5;
    let d = BundledData::load_downloaded(Some(&bytes(&bundle(v, "2027-03-04"))));
    assert_eq!(d.lists.version, v);
    assert_eq!(d.lists.built, "2027-03-04");
}

#[test]
fn lists_bundled_newer_or_equal_wins() {
    let v = shipped().lists.version;
    assert_eq!(BundledData::load_downloaded(Some(&bytes(&bundle(v, "2027-01-02")))).lists, shipped().lists);
    assert_eq!(BundledData::load_downloaded(Some(&bytes(&bundle(v - 1, "2027-01-02")))).lists, shipped().lists);
}

#[test]
fn lists_bad_downloads_give_the_bundled_lists() {
    let s = shipped();
    let mut b = bundle(s.lists.version + 1, "2027-01-02");
    b.files.brands.clear();
    assert_eq!(BundledData::load_downloaded(Some(&bytes(&b))).lists, s.lists);
    let mut b = bundle(s.lists.version + 1, "2027-01-02");
    b.schema = 9;
    assert_eq!(BundledData::load_downloaded(Some(&bytes(&b))).lists, s.lists);
    assert_eq!(BundledData::load_downloaded(Some(b"not json")).lists, s.lists);
    assert_eq!(BundledData::load_downloaded(Some(b"")).lists, s.lists);
    assert_eq!(BundledData::load_downloaded(Some(&vec![0u8; data::LISTS_MAX_BYTES + 1])).lists, s.lists);
}

#[test]
fn lists_reload_swaps_the_shared_lists() {
    let s = shipped();
    BundledData::reload(Some(&bytes(&bundle(s.lists.version + 7, "2027-01-02"))));
    assert_eq!(BundledData::shared().lists.version, s.lists.version + 7);
    BundledData::reload(None);
    assert_eq!(BundledData::shared().lists, s.lists);
}

#[test]
fn lists_candidate_bundle_from_the_script_is_valid() {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../Kit/.build/lists-candidate.json");
    let Ok(raw) = std::fs::read(path) else { return };
    assert!(raw.len() <= data::LISTS_MAX_BYTES);
    let b = ListsBundle::decode(&raw).unwrap();
    assert_eq!(data::validate(&b), Ok(()));
    assert_eq!(BundledData::from_bundle(&b, &shipped()).lists, b.header());
}

// The acceptance order QRUI's ListsUpdater applies after the signature (`lists_bundle_check`).
#[test]
fn lists_bundle_check_follows_the_updater_order() {
    let s = shipped();
    let good = bytes(&bundle(s.lists.version + 1, "2027-01-02"));
    assert_eq!(lists_bundle_check(good.clone(), s.lists.version).map(|h| h.version), Ok(s.lists.version + 1));
    assert_eq!(lists_bundle_check(vec![b' '; data::LISTS_MAX_BYTES + 1], 0), Err(ListsBundleError::TooLarge));
    assert_eq!(lists_bundle_check(b"[]".to_vec(), 0), Err(ListsBundleError::Unreadable));
    assert_eq!(
        lists_bundle_check(br#"{"schema":2,"version":1,"built":"x"}"#.to_vec(), 0),
        Err(ListsBundleError::UnknownSchema { schema: 2 })
    );
    assert_eq!(
        lists_bundle_check(good, s.lists.version + 1),
        Err(ListsBundleError::NotNewer { installed: s.lists.version + 1, offered: s.lists.version + 1 })
    );
    let mut b = bundle(s.lists.version + 1, "2027-01-02");
    b.files.bait_words.clear();
    assert_eq!(lists_bundle_check(bytes(&b), 0).map(|_| ()), bad("baitWords"));
}
