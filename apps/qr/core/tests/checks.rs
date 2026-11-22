//! Swift parity: ChecksBrandTests, ChecksEmbeddedLinksTests, ChecksFixTests and
//! ChecksSkeletonScriptTests.

mod common;
use SignalId::*;
use common::*;
use qr_core::checks::{
    self, BrandMatcher, CheckContext, Skeleton, damerau_levenshtein, embedded_links, script_mix,
};
use qr_core::*;
use std::collections::HashSet;
use std::sync::{Arc, OnceLock};

fn ctx() -> &'static CheckContext {
    static C: OnceLock<CheckContext> = OnceLock::new();
    C.get_or_init(|| CheckContext::new(shared()))
}

fn link(s: &str) -> WebAddress {
    WebAddress::parse(s, &shared().public_suffixes).unwrap_or_else(|| panic!("{s}"))
}

fn sig(s: &str) -> Vec<Signal> {
    checks::link_signals(&link(s), ctx())
}

fn sid(s: &str) -> Vec<SignalId> {
    ids(&sig(s))
}

fn strs(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| s.to_string()).collect()
}

// MARK: ChecksBrandTests.swift

#[test]
fn checks_tokens_split_on_separators_and_digits() {
    assert_eq!(
        BrandMatcher::tokens(&strs(&["secure-paypal_login"])),
        strs(&["secure", "paypal", "login"])
    );
    assert_eq!(
        BrandMatcher::tokens(&strs(&["paypa1", "amazon24x"])),
        strs(&["paypa", "1", "amazon", "24", "x"])
    );
    assert_eq!(
        BrandMatcher::tokens(&strs(&["b\u{00FC}cher"])),
        strs(&["b\u{00FC}cher"])
    );
}

#[test]
fn checks_damerau_levenshtein() {
    let d = |a: &str, b: &str| {
        damerau_levenshtein(
            &a.chars().collect::<Vec<_>>(),
            &b.chars().collect::<Vec<_>>(),
        )
    };
    assert_eq!(d("paypal", "paypal"), 0);
    assert_eq!(d("paypa", "paypal"), 1);
    assert_eq!(d("gogole", "google"), 1);
    assert_eq!(d("amazon", "amazonn"), 1);
    assert_eq!(d("abc", "xyz"), 3);
    assert_eq!(d("", "abc"), 3);
}

#[test]
fn checks_brand_signal_args() {
    let s = sig("https://paypa1.com/");
    assert_eq!(s.first().map(|x| x.id), Some(LookalikeBrand));
    assert_eq!(arg(s.first(), "brand"), Some("PayPal"));
    let u = sig("https://bank.com@evil.site/");
    assert_eq!(
        arg(u.iter().find(|x| x.id == UserinfoTrick), "host"),
        Some("evil.site")
    );
    assert_eq!(arg(sig("https://x.top/").first(), "tld"), Some("top"));
}

#[test]
fn checks_brand_checks_run_on_unknown_suffix_and_skip_ips() {
    let r = sid("https://paypal.com.example/login");
    assert!(r.contains(&BrandInDomain));
    assert!(r.contains(&UnknownEnding));
    assert!(!sid("https://203.0.113.9/paypal").contains(&BrandInDomain));
}

#[test]
fn checks_skeleton_match_never_fires_on_exact_brand_label() {
    assert!(sid("https://paypay.ne.jp/").is_empty());
    assert!(sid("https://paypal.xyz/").contains(&BrandInDomain));
    assert!(!sid("https://paypal.xyz/").contains(&LookalikeBrand));
}

#[test]
fn checks_only_one_signal_per_id_and_stable_order() {
    let s = sig("http://a.b.c.d.paypa1-secure.top/login");
    assert_eq!(
        s.iter().map(|x| x.id).collect::<HashSet<_>>().len(),
        s.len()
    );
    assert_eq!(s, in_stable_order(&s));
}

#[test]
fn checks_brand_overrides_keep_regional_sites_quiet() {
    for u in [
        "https://www.google.co.uk/",
        "https://www.ebay.de/",
        "https://www.yahoo.co.jp/",
        "https://www.paypal.com.au/",
    ] {
        assert!(sid(u).is_empty(), "{u}");
    }
    assert!(sid("https://google.xyz/").contains(&BrandInDomain));
}

#[test]
fn checks_common_words_do_not_misfire() {
    for u in [
        "https://www.smart-home-guide.com/",
        "https://www.zoom.com/",
        "https://store.steampowered.com/",
        "https://www.bookingholdings.com/",
        "https://www.grabcad.com/",
        "https://www.ubereats.com/",
        "https://www.ledger.com/",
        "https://www.orange.com/",
        "https://www.claroshop.com/",
        "https://www.bostonglobe.com/",
        "https://www.mayoclinic.org/",
        "https://www.visa.co.uk/",
        "https://www.targetcorp.com/",
        "https://www.dito.example.com/",
    ] {
        let r = sid(u);
        assert!(
            !r.iter().any(|i| i.severity() == Severity::Strong),
            "{u}: {r:?}"
        );
    }
}

// MARK: ChecksEmbeddedLinksTests.swift

fn find(s: &str) -> Vec<String> {
    embedded_links::find(Some(s))
}

#[test]
fn checks_embedded_finds_three_shapes() {
    let t = "a http://one.example/x b HTTPS://Two.example/y c www.three.example/z d";
    assert_eq!(
        find(t),
        strs(&[
            "http://one.example/x",
            "HTTPS://Two.example/y",
            "www.three.example/z"
        ])
    );
}

#[test]
fn checks_embedded_drops_trailing_punctuation_and_stops_at_angle_and_quote() {
    for s in [
        "(see https://a.example/p).",
        "[https://a.example/p],",
        "<https://a.example/p>",
        "href=\"https://a.example/p\"",
        "https://a.example/p...",
    ] {
        assert_eq!(find(s), strs(&["https://a.example/p"]), "{s}");
    }
}

#[test]
fn checks_embedded_ignores_bare_schemes_and_words_ending_in_www() {
    assert!(find("http:// and https://").is_empty());
    assert!(find("awww.example.com").is_empty());
    assert_eq!(find("see www.example.com").len(), 1);
    assert!(embedded_links::find(None).is_empty());
}

#[test]
fn checks_embedded_caps_at_ten_and_deduplicates() {
    let many = (0..30)
        .map(|i| format!("https://e.example/{i}"))
        .collect::<Vec<_>>()
        .join(" ");
    assert_eq!(find(&many).len(), 10);
    assert_eq!(
        find("https://e.example/a https://e.example/a"),
        strs(&["https://e.example/a"])
    );
}

#[test]
fn checks_embedded_is_linear_on_hostile_input() {
    let hostile = "http://".repeat(3000) + &"www.".repeat(3000);
    assert!(find(&hostile).len() <= 10);
}

// MARK: ChecksFixTests.swift

fn fix_data(bait: &[&str]) -> BundledData {
    let s = shared();
    let brand =
        |name: &str, kw: &str, domains: &[&str], cat: &str, region: &str, amb: bool| Brand {
            name: name.into(),
            keywords: strs(&[kw]),
            domains: strs(domains),
            category: cat.into(),
            region: region.into(),
            ambiguous: amb,
        };
    let mut d = BundledData::new(s.public_suffixes.clone());
    d.brands = vec![
        brand("DHL", "dhl", &["dhl.com"], "shipping", "global", false),
        brand("BPI", "bpi", &["bpi.com.ph"], "bank", "ph", false),
        brand(
            "PayPal",
            "paypal",
            &["paypal.com", "paypal.me"],
            "payments",
            "global",
            false,
        ),
        brand("Apple", "apple", &["apple.com"], "tech", "global", true),
    ];
    d.risky_tlds = ["help", "top", "cc"]
        .iter()
        .map(|t| qr_core::data::RiskyTld {
            tld: t.to_string(),
            source: "https://example.org/report".into(),
        })
        .collect();
    d.confusables = s.confusables.clone();
    d.bait_words = strs(bait);
    d
}

fn fix_ctx() -> &'static CheckContext {
    static C: OnceLock<CheckContext> = OnceLock::new();
    C.get_or_init(|| CheckContext::new(Arc::new(fix_data(&["tracking", "online", "login"]))))
}

fn fix(s: &str) -> Vec<SignalId> {
    ids(&checks::link_signals(&link(s), fix_ctx()))
}

#[test]
fn checks_fix_three_letter_brand_needs_whole_token_and_bait() {
    assert!(fix("https://dhl-tracking.help/").contains(&BrandInDomain));
    assert_eq!(fix("https://bpi-online.verify.site/"), vec![BrandInDomain]);
    assert!(fix("https://dhl.tracking.example.net/").contains(&BrandInDomain));
    assert!(fix("https://tracking.dhl.com/").is_empty());
    assert!(fix("https://dhl.de/").is_empty());
    assert!(fix("https://www.dhl-fans.net/").is_empty());
    assert_eq!(fix("https://dhlx-tracking.help/"), vec![RiskyTld]);
    assert!(fix("https://adhl-online.net/").is_empty());
    assert!(fix("https://www.upsilon-online.net/").is_empty());
}

#[test]
fn checks_fix_three_letter_brand_is_never_gentle_or_lookalike() {
    let r = fix("https://online.dhl.de/");
    assert!(r.contains(&BrandInDomain));
    assert!(!r.contains(&BrandOtherEnding));
    for u in [
        "https://dh1-tracking.help/",
        "https://dh1.com/",
        "https://bp1-online.net/",
        "https://dhi-login.net/",
    ] {
        assert!(!fix(u).contains(&LookalikeBrand), "{u}");
        assert!(!fix(u).contains(&NearBrand), "{u}");
    }
}

#[test]
fn checks_fix_the_bait_word_must_be_another_token() {
    let mut d = fix_data(&["dhl"]);
    d.brands.truncate(1);
    d.risky_tlds.clear();
    let c = CheckContext::new(Arc::new(d));
    assert!(checks::link_signals(&link("https://dhl.net/"), &c).is_empty());
}

#[test]
fn checks_fix_other_ending_rule() {
    assert_eq!(fix("https://paypal.pt/"), vec![BrandOtherEnding]);
    assert_eq!(fix("https://login.paypal.pt/"), vec![BrandOtherEnding]);
    assert_eq!(fix("https://www.paypal.com.ph/"), vec![BrandOtherEnding]);
    assert_eq!(fix("https://paypal.co.il/"), vec![BrandOtherEnding]);
    assert_eq!(fix("https://paypal.org/"), vec![BrandOtherEnding]);
    assert!(fix("https://paypal.me/").is_empty());
    assert!(fix("https://paypal.help/").contains(&BrandInDomain));
    assert!(fix("https://paypal.cc/").contains(&BrandInDomain));
    assert_eq!(fix("https://paypal.xyz/"), vec![BrandInDomain]);
    assert_eq!(fix("https://paypal-login.ph/"), vec![BrandInDomain]);
    assert_eq!(fix("https://paypal.evil.ph/"), vec![BrandInDomain]);
    assert_eq!(fix("https://paypal.github.io/"), vec![BrandInDomain]);
    assert!(fix("https://apple.ph/").is_empty());
    assert_eq!(fix("https://login.apple.ph/"), vec![BrandInDomain]);
}

#[test]
fn checks_fix_strong_beats_gentle_when_two_brands_match() {
    assert!(fix("https://paypal.dhl-login.net/").contains(&BrandInDomain));
    let r = fix("https://dhl-login.paypal.ph/");
    assert!(r.contains(&BrandInDomain) && !r.contains(&BrandOtherEnding));
}

#[test]
fn checks_fix_unknown_ending_falls_back_to_last_label() {
    let a = link("https://paypal.evil-site.newtldxyz/");
    assert_eq!(
        a.link.registrable_domain.as_deref(),
        Some("evil-site.newtldxyz")
    );
    let r = checks::link_signals(&a, fix_ctx());
    assert_eq!(ids(&r), vec![BrandInDomain, UnknownEnding]);
    assert_eq!(
        r.iter()
            .find(|s| s.id == UnknownEnding)
            .map(|s| s.severity()),
        Some(Severity::Info)
    );
    assert!(!fix("https://example.com/").contains(&UnknownEnding));
    assert!(fix("https://localhost/").is_empty());
    assert_eq!(fix("https://10.0.0.1/"), vec![IpHost]);
    assert!(
        WebAddress::parse_schemeless("www.example.newtldxyz", &shared().public_suffixes).is_none()
    );
}

#[test]
fn checks_fix_lookalike_words_inside_labels() {
    assert_eq!(fix("https://secure-paypa1.com/"), vec![LookalikeBrand]);
    assert_eq!(fix("https://paypa1.evil.com/"), vec![LookalikeBrand]);
    assert_eq!(fix("https://my-paypal.com/"), vec![BrandInDomain]);
    assert_eq!(fix("https://app1e-login.com/"), vec![LookalikeBrand]);
    assert!(fix("https://app1e-garden.com/").is_empty());
    assert!(fix("https://secure-garden.com/").is_empty());
}

#[test]
fn checks_fix_nested_link_only_counts_in_the_path() {
    let fires = |s: &str| {
        WebAddress::parse(s, &shared().public_suffixes)
            .is_some_and(|a| checks::obfuscated_path(&a.link, &strs(&["login"])).is_some())
    };
    assert!(!fires(
        "https://example.com/login?next=https://other.example/"
    ));
    assert!(!fires(
        "https://example.com/login?next=https%3A%2F%2Fother.example"
    ));
    assert!(!fires("https://example.com/login#https://other.example/"));
    assert!(fires("https://example.com/login/https://other.example/"));
    assert!(fires(
        "https://example.com/login/http%3A%2F%2Fother.example"
    ));
    assert!(!fires(
        "https://example.com/login/x?y=1&u=http://z.example/"
    ));
    assert!(!fires(&format!(
        "https://example.com/login?x={}",
        "a".repeat(200)
    )));
    assert!(!fires(
        "https://example.com/login?x=%41%42%43%44%45%46%47%48%49%4A%4B"
    ));
    assert!(fires(&format!(
        "https://example.com/login/{}",
        "a".repeat(200)
    )));
    assert!(fires(
        "https://example.com/login/%41%42%43%44%45%46%47%48%49%4A%4B"
    ));
}

#[test]
fn checks_fix_host_is_folded_before_any_check() {
    let psl = &shared().public_suffixes;
    for s in [
        "https://ｐａｙｐａｌ.com/",
        "https://paypal。com/",
        "https://paypal．com/",
        "https://paypal｡com/",
        "https://ＰＡＹＰＡＬ.COM/",
    ] {
        let a = WebAddress::parse(s, psl).unwrap_or_else(|| panic!("{s}"));
        assert_eq!(a.link.host_ascii, "paypal.com", "{s}");
        assert_eq!(a.link.registrable_domain.as_deref(), Some("paypal.com"));
        assert!(!a.has_malformed_label);
    }
    assert_eq!(
        WebAddress::parse("https://\u{FB01}le.example.com/", psl)
            .unwrap()
            .link
            .host_ascii,
        "file.example.com"
    );
    assert!(WebAddress::parse("https://example.com\u{FF0F}paypal.com/", psl).is_none());
}

// MARK: ChecksSkeletonScriptTests.swift

fn skeleton() -> Skeleton {
    Skeleton::new(&shared().confusables)
}

#[test]
fn checks_skeleton_maps_confusables() {
    let s = skeleton();
    assert_eq!(s.of("paypa1"), s.of("paypal"));
    assert_eq!(s.of("rnicrosoft"), s.of("microsoft"));
    assert_eq!(s.of("g00gle"), s.of("google"));
    assert_eq!(s.of("vvikipedia"), s.of("wikipedia"));
    assert_eq!(
        s.of("\u{0430}\u{0440}\u{0440}\u{04CF}\u{0435}"),
        s.of("apple")
    );
    assert_eq!(
        s.of("\u{FF50}\u{FF41}\u{FF59}\u{FF50}\u{FF41}\u{FF4C}"),
        s.of("paypal")
    );
}

#[test]
fn checks_skeleton_strips_hyphens_and_lowercases() {
    let s = skeleton();
    assert_eq!(s.of("Pay-Pal"), s.of("paypal"));
    assert_eq!(s.of("a-b-c"), "abc");
}

#[test]
fn checks_skeleton_longest_key_first() {
    let s = skeleton();
    assert_eq!(s.of("rn"), "m");
    assert_eq!(s.of("vv"), "w");
    assert_eq!(s.of("rnn"), "mn");
}

#[test]
fn checks_skeleton_different_words_stay_different() {
    let s = skeleton();
    assert_ne!(s.of("example"), s.of("paypal"));
    assert_eq!(s.of(""), "");
}

fn mixed(s: &str) -> bool {
    script_mix::is_mixed(&script_mix::scripts(s))
}

#[test]
fn checks_script_mix_single_scripts_are_fine() {
    for s in [
        "example",
        "b\u{00FC}cher",
        "\u{043F}\u{0440}\u{0438}\u{043C}\u{0435}\u{0440}",
        "\u{03B1}\u{03B2}\u{03B3}",
        "\u{0645}\u{062B}\u{0627}\u{0644}",
        "\u{4F8B}\u{3048}",
        "\u{D55C}\u{AD6D}",
        "my-site-2026",
        "123",
        "\u{0E15}\u{0E31}\u{0E27}\u{0E2D}\u{0E22}\u{0E48}\u{0E32}\u{0E07}",
    ] {
        assert!(!mixed(s), "{s}");
    }
}

#[test]
fn checks_script_mix_allowed_combinations() {
    assert!(!mixed("shop\u{4F8B}\u{3048}"));
    assert!(!mixed("abc\u{30AB}\u{30BF}\u{30AB}\u{30CA}\u{6F22}"));
    assert!(!mixed("abc\u{D55C}\u{AD6D}\u{6F22}"));
    assert!(!mixed("abc\u{3105}\u{6F22}"));
}

#[test]
fn checks_script_mix_forbidden_combinations() {
    for s in [
        "ex\u{0430}mple",
        "g\u{03BF}\u{03BF}d",
        "abc\u{0645}",
        "\u{043F}\u{03B1}",
        "\u{D55C}\u{3042}",
        "abc\u{D55C}\u{3042}",
        "abc\u{1200}",
    ] {
        assert!(mixed(s), "{s}");
    }
}

#[test]
fn checks_script_mix_ignores_digits_hyphens_and_marks() {
    assert!(!mixed("\u{043F}\u{0440}-2026"));
    assert!(!mixed("e\u{0301}cole"));
}
