//! Swift parity: PeekAnalyzerRulesTests, PeekAnalyzerTests (pairs), PeekFixturePairTests,
//! PeekHTMLSnifferTests, PeekRDAPTests and PeekDomainAgeTests.

mod common;
use common::*;
use qr_core::peek::{RdapBootstrap, domain_age_days, domain_age_days_in, rdap_registration_date_of, sniff};
use qr_core::*;
use serde::Deserialize;
use std::collections::HashSet;
use std::sync::OnceLock;
use std::time::Instant;
use SignalId::*;

fn peek() -> &'static PeekAnalyzer {
    static P: OnceLock<PeekAnalyzer> = OnceLock::new();
    P.get_or_init(|| PeekAnalyzer::with_data(shared()))
}

fn hop(url: &str, status: Option<i64>) -> PeekHop {
    PeekHop { url: url.into(), status }
}

fn capture(hops: Vec<PeekHop>, stop: StopReason) -> PeekCapture {
    PeekCapture { hops, stopped_because: stop, content_type: None, content_disposition: None, body: vec![], tls_version: None }
}

fn run_full(url: &str, body: &str, ct: Option<&str>, disposition: Option<&str>, stop: StopReason, age: Option<i64>) -> PeekReport {
    let c = PeekCapture {
        content_type: ct.map(String::from),
        content_disposition: disposition.map(String::from),
        body: body.as_bytes().to_vec(),
        ..capture(vec![hop(url, Some(200))], stop)
    };
    peek().report(analyze(url), c, age)
}

fn run(body: &str) -> PeekReport {
    run_full("https://page.example.com/", body, Some("text/html"), None, StopReason::Finished, Some(5000))
}

fn form(input: &str) -> String {
    format!("<html><body><p>{}</p><form>{input}</form></body></html>", "text ".repeat(60))
}

// MARK: PeekAnalyzerRulesTests.swift

#[test]
fn peek_analyzer_secret_kinds() {
    for (input, kind) in [
        ("<input type=password name=p>", "password"),
        ("<input type=text autocomplete=current-password>", "password"),
        ("<input type=text autocomplete=one-time-code>", "code"),
        ("<input name=otp>", "code"),
        ("<input name=mpin>", "code"),
        ("<input placeholder=\"Enter your PIN\">", "code"),
        ("<input id=passcode>", "code"),
        ("<input placeholder=\"Verification code\">", "code"),
        ("<input autocomplete=cc-number>", "card"),
        ("<input autocomplete=cc-csc>", "card"),
        ("<input name=\"card number\">", "card"),
        ("<input name=cvv>", "card"),
        ("<input name=cvc>", "card"),
    ] {
        let r = run(&form(input));
        assert_eq!(arg(r.signals.iter().find(|s| s.id == AsksForSecret), "kind"), Some(kind), "{input}");
    }
}

#[test]
fn peek_analyzer_pin_needs_word_boundaries() {
    for input in ["<input name=pinterest>", "<input name=shipping>", "<input name=email>", "<input type=hidden name=pin>"] {
        assert!(!ids(&run(&form(input)).signals).contains(&AsksForSecret), "{input}");
    }
}

#[test]
fn peek_analyzer_sniffs_only_html_or_angle_bracket() {
    let html = format!("<html><head><title>Hello</title></head><body>{}</body></html>", "x ".repeat(150));
    let t = |body: &str, ct: Option<&str>| run_full("https://page.example.com/", body, ct, None, StopReason::Finished, Some(5000)).title;
    assert_eq!(t(&html, Some("text/html")).as_deref(), Some("Hello"));
    assert_eq!(t(&html, Some("application/xhtml+xml")).as_deref(), Some("Hello"));
    assert_eq!(t(&format!("  \n{html}"), Some("application/octet-stream")).as_deref(), Some("Hello"));
    assert_eq!(t(&html, None).as_deref(), Some("Hello"));
    assert_eq!(t("{\"title\":\"<title>No</title>\"}", Some("application/json")), None);
    assert_eq!(t("plain text <title>No</title>", Some("text/plain")), None);
}

#[test]
fn peek_analyzer_stop_reasons() {
    let one = |stop: StopReason, hops: Vec<PeekHop>| {
        let original = analyze(&hops[0].url);
        ids(&peek().report(original, capture(hops, stop), Some(5000)).signals)
    };
    let a = || hop("https://a.example.com/", Some(301));
    assert!(one(StopReason::TooManyRedirects, vec![a()]).contains(&TooManyRedirects));
    assert!(one(StopReason::HttpTarget, vec![a(), hop("http://b.example.com/", None)]).contains(&RedirectsToHttp));
    assert!(one(StopReason::NonWebTarget, vec![a(), hop("tel:+631", None)]).contains(&RedirectLeavesWeb));
    assert!(one(StopReason::TlsFailure, vec![a(), hop("https://b.example.com/", None)]).contains(&TlsProblem));
    assert!(one(StopReason::Timeout, vec![a()]).contains(&PeekIncomplete));
    assert!(one(StopReason::NetworkError, vec![a()]).contains(&PeekIncomplete));
    assert!(!one(StopReason::Finished, vec![a()]).contains(&PeekIncomplete));
}

#[test]
fn peek_analyzer_final_link_is_last_fetched_hop() {
    let hops = vec![hop("https://a.example.com/", Some(302)), hop("https://b.example.org/x", Some(200)), hop("http://c.example.net/", None)];
    let r = peek().report(analyze(&hops[0].url), capture(hops.clone(), StopReason::HttpTarget), Some(400));
    assert_eq!(r.final_link.as_ref().map(|l| l.host_ascii.as_str()), Some("b.example.org"));
    assert_eq!(r.hop_analyses.len(), 3);
    assert!(ids(&r.hop_analyses[2].signals).contains(&PlainHttp));
    let none = peek().report(analyze(&hops[0].url), capture(vec![hop(&hops[0].url, None)], StopReason::Timeout), None);
    assert!(none.final_link.is_none());
    assert!(!ids(&none.signals).contains(&AgeUnknown));
}

#[test]
fn peek_analyzer_level_combines_original_and_hops() {
    let r = run_full(
        "https://bit.ly/abc",
        &format!("<html><body>{}</body></html>", "word ".repeat(80)),
        Some("text/html"),
        None,
        StopReason::Finished,
        Some(5000),
    );
    assert_eq!(r.level, Level::Careful);
    let hops = vec![hop("https://example.com/a", Some(302)), hop("https://paypa1.com/", Some(200))];
    let c = PeekCapture { content_type: Some("text/html".into()), ..capture(hops.clone(), StopReason::Finished) };
    let r2 = peek().report(analyze(&hops[0].url), c, Some(5000));
    assert_eq!(r2.level, Level::LikelyScam);
    assert!(r2.signals.iter().all(|s| s.severity() == Severity::Info));
}

#[test]
fn peek_analyzer_age_boundaries() {
    let r = |age: Option<i64>| run_full("https://page.example.com/", "", None, None, StopReason::Finished, age);
    for (age, expected) in [(0, DomainVeryNew), (29, DomainVeryNew), (30, DomainNew), (182, DomainNew)] {
        let rep = r(Some(age));
        assert!(ids(&rep.signals).contains(&expected), "age {age}");
        assert_eq!(arg(rep.signals.iter().find(|s| s.id == expected), "days"), Some(age.to_string().as_str()));
    }
    assert!(!ids(&r(Some(183)).signals).contains(&DomainNew));
    assert!(ids(&r(None).signals).contains(&AgeUnknown));
}

#[test]
fn peek_analyzer_downloads() {
    let has = |url: &str, ct: Option<&str>, d: Option<&str>| {
        ids(&run_full(url, "", ct, d, StopReason::Finished, Some(5000)).signals).contains(&DownloadsFile)
    };
    assert!(has("https://f.example.com/x", Some("application/zip"), Some("Attachment; filename=x")));
    for ext in ["apk", "ipa", "mobileconfig", "exe", "msi", "dmg", "pkg", "zip", "scr", "bat"] {
        assert!(has(&format!("https://f.example.com/a.{ext}?v=1#x"), None, None), "{ext}");
    }
    assert!(!has("https://f.example.com/a.pdf", Some("application/pdf"), None));
    assert!(!has("https://f.example.com/zip", None, None));
}

#[test]
fn peek_analyzer_shared_hosting_uses_final_suffix() {
    let r = |u: &str| run_full(u, "", Some("text/html"), None, StopReason::Finished, Some(5000));
    assert!(ids(&r("https://user.github.io/").signals).contains(&SharedHostingDomain));
    assert!(!ids(&r("https://example.com/").signals).contains(&SharedHostingDomain));
}

#[test]
fn peek_analyzer_signal_order_and_uniqueness() {
    let body = "<html><head><meta http-equiv=refresh content=\"0;url=https://x.example.net/\"></head><body><form action=\"https://z.example.net/\"><input type=password></form><script>top.location='x'</script></body></html>";
    let r = run_full("https://page.example.com/", body, Some("text/html"), None, StopReason::Finished, Some(3));
    assert_eq!(r.signals, in_stable_order(&r.signals));
    let i = ids(&r.signals);
    assert_eq!(i.iter().collect::<HashSet<_>>().len(), i.len());
    assert!(i.contains(&DomainVeryNew) && i.contains(&FormPostsElsewhere) && i.contains(&ScriptRedirect));
}

#[test]
fn peek_analyzer_hostile_bodies_do_not_crash() {
    let junk: Vec<u8> = (0..20_000u32).map(|i| (i.wrapping_mul(31).wrapping_add(7)) as u8).collect();
    let c = PeekCapture {
        content_type: Some("text/html".into()),
        body: junk,
        ..capture(vec![hop("https://x.example.com/", Some(200))], StopReason::Finished)
    };
    peek().report(analyze("https://x.example.com/"), c, Some(1));
    let r = peek().report(analyze("https://x.example.com/"), capture(vec![], StopReason::NetworkError), None);
    assert!(r.final_link.is_none() && r.hop_analyses.is_empty());
}

// MARK: PeekAnalyzerTests.swift (fixture pairs)

#[derive(Deserialize)]
struct Hop {
    url: String,
    status: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairExpect {
    title: Option<String>,
    signals: Vec<String>,
    info: Option<Vec<String>>,
    level: Option<i64>,
    final_host: Option<String>,
    hop_levels: Option<Vec<i64>>,
    sniff: Option<SniffExpect>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct SniffExpect {
    form_actions: Option<Vec<String>>,
    script_redirect: Option<bool>,
    #[serde(rename = "metaRefreshURL")]
    meta_refresh_url: Option<String>,
    input_types: Option<Vec<String>>,
    script_count: Option<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pair {
    id: String,
    original: String,
    hops: Vec<Hop>,
    stopped_because: String,
    content_type: Option<String>,
    content_disposition: Option<String>,
    age_days: Option<i64>,
    expect: PairExpect,
}

fn names(folder: &str) -> Vec<String> {
    let mut v: Vec<String> = std::fs::read_dir(fixtures_dir().join(folder))
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".json"))
        .map(|n| format!("{folder}/{}", &n[..n.len() - 5]))
        .collect();
    v.sort();
    v
}

fn load_pair(path: &str) -> (Pair, Vec<u8>) {
    let pair: Pair = serde_json::from_slice(&fixture_bytes(&format!("{path}.json"))).unwrap();
    let html = std::fs::read(fixtures_dir().join(format!("{path}.html"))).unwrap_or_default();
    (pair, html)
}

#[test]
fn pair_matches_expectation() {
    for path in names("peek").into_iter().chain(names("peekreport")) {
        let (pair, body) = load_pair(&path);
        let c = PeekCapture {
            hops: pair.hops.iter().map(|h| hop(&h.url, h.status)).collect(),
            stopped_because: StopReason::from_raw(&pair.stopped_because).unwrap_or(StopReason::Finished),
            content_type: pair.content_type.clone(),
            content_disposition: pair.content_disposition.clone(),
            body,
            tls_version: None,
        };
        let r = peek().report(analyze(&pair.original), c, pair.age_days);
        let id = &pair.id;
        let non_info: HashSet<&str> = r.signals.iter().filter(|s| s.severity() != Severity::Info).map(|s| s.id.raw()).collect();
        assert_eq!(non_info, pair.expect.signals.iter().map(String::as_str).collect(), "{id} signals");
        assert_eq!(r.signals.iter().map(|s| s.id).collect::<HashSet<_>>().len(), r.signals.len(), "{id}");
        if let Some(info) = &pair.expect.info {
            let got: HashSet<&str> = r.signals.iter().filter(|s| s.severity() == Severity::Info).map(|s| s.id.raw()).collect();
            assert_eq!(got, info.iter().map(String::as_str).collect(), "{id} info");
        }
        assert_eq!(r.title, pair.expect.title, "{id} title");
        if let Some(level) = pair.expect.level {
            assert_eq!(r.level.raw(), level, "{id} level");
        }
        if path.starts_with("peekreport") {
            assert_eq!(r.final_link.map(|l| l.host_unicode), pair.expect.final_host, "{id} final host");
        }
        if let Some(h) = &pair.expect.hop_levels {
            assert_eq!(&r.hop_analyses.iter().map(|a| a.level.raw()).collect::<Vec<_>>(), h, "{id} hop levels");
        }
        assert_eq!(r.hop_analyses.len(), pair.hops.len());
        assert_eq!(r.signals, in_stable_order(&r.signals));
    }
}

#[test]
fn every_peek_pair_is_covered() {
    assert!(names("peek").len() >= 11);
    assert!(names("peekreport").len() >= 40);
}

// MARK: PeekFixturePairTests.swift

#[test]
fn pairs_exist() {
    let n = names("peek");
    for required in ["plain", "login", "brand-mismatch-login", "cross-domain-form", "meta-refresh", "script-redirect", "js-only-shell", "non-utf8", "card-form", "otp-form", "download"] {
        assert!(n.contains(&format!("peek/{required}")), "missing {required}");
    }
}

#[test]
fn sniff_matches_expectation() {
    for path in names("peek") {
        let (pair, html) = load_pair(&path);
        assert_eq!(pair.id, format!("peek-{}", &path[5..]));
        let r = sniff(&html, pair.content_type.as_deref());
        assert_eq!(r.title, pair.expect.title, "{}", pair.id);
        let Some(e) = pair.expect.sniff else { continue };
        if let Some(v) = e.form_actions {
            assert_eq!(r.form_actions.iter().map(|a| a.clone().unwrap_or_default()).collect::<Vec<_>>(), v);
        }
        if let Some(v) = e.script_redirect {
            assert_eq!(r.script_redirect, v, "{}", pair.id);
        }
        if let Some(v) = e.script_count {
            assert_eq!(r.script_count, v);
        }
        if let Some(v) = e.input_types {
            assert_eq!(r.inputs.iter().map(|i| i.input_type.clone().unwrap_or_default()).collect::<Vec<_>>(), v);
        }
        assert_eq!(r.meta_refresh_url, e.meta_refresh_url, "{}", pair.id);
    }
}

#[test]
fn js_only_shell_is_short_with_a_script() {
    let (pair, html) = load_pair("peek/js-only-shell");
    let r = sniff(&html, pair.content_type.as_deref());
    assert!(r.visible_text_length < 200 && r.script_count >= 1);
    let (p2, plain) = load_pair("peek/plain");
    assert!(sniff(&plain, p2.content_type.as_deref()).visible_text_length >= 200);
}

#[test]
fn non_utf8_pair_is_really_not_utf8() {
    assert!(std::str::from_utf8(&load_pair("peek/non-utf8").1).is_err());
}

// MARK: PeekHTMLSnifferTests.swift

fn sn(html: &str) -> HtmlSniff {
    sniff(html.as_bytes(), Some("text/html; charset=utf-8"))
}

#[test]
fn title_decodes_entities_and_collapses_whitespace() {
    let r = sn("<TITLE>\n  Tom &amp; Jerry&nbsp;&#169; &#x41;\t&lt;ok&gt; &bogus; </title>");
    assert_eq!(r.title.as_deref(), Some("Tom & Jerry \u{A9} A <ok> &bogus;"));
}

#[test]
fn title_caps_at_200() {
    assert_eq!(sn(&format!("<title>{}</title>", "a".repeat(500))).title.map(|t| t.chars().count()), Some(200));
}

#[test]
fn empty_or_missing_title_is_nil() {
    assert_eq!(sn("<title>  </title>").title, None);
    assert_eq!(sn("<p>hi</p>").title, None);
}

#[test]
fn forms_and_inputs_with_all_quote_styles() {
    let r = sn("<FORM ACTION='/a?x=1&amp;y=2'><form><form action=https://o.example/p>\n<input type=password NAME=\"pw\" id='p1' Autocomplete=\"current-password\" placeholder=Secret>\n<input name=q>");
    assert_eq!(r.form_actions, vec![Some("/a?x=1&y=2".into()), None, Some("https://o.example/p".into())]);
    assert_eq!(r.inputs.len(), 2);
    assert_eq!(
        r.inputs[0],
        HtmlInput {
            input_type: Some("password".into()),
            name: Some("pw".into()),
            id: Some("p1".into()),
            autocomplete: Some("current-password".into()),
            placeholder: Some("Secret".into())
        }
    );
    assert_eq!(r.inputs[1], HtmlInput { name: Some("q".into()), ..Default::default() });
}

#[test]
fn meta_refresh_forms() {
    assert_eq!(sn(r#"<meta http-equiv="refresh" content="5; url=https://a.example/x">"#).meta_refresh_url.as_deref(), Some("https://a.example/x"));
    assert_eq!(sn(r#"<META HTTP-EQUIV=REFRESH CONTENT="0;URL='/next'">"#).meta_refresh_url.as_deref(), Some("/next"));
    assert_eq!(sn(r#"<meta http-equiv="refresh" content="0; https://b.example">"#).meta_refresh_url.as_deref(), Some("https://b.example"));
    assert_eq!(sn(r#"<meta http-equiv="refresh" content="30">"#).meta_refresh_url, None);
    assert_eq!(sn(r#"<meta name="x" content="5; url=/y">"#).meta_refresh_url, None);
}

#[test]
fn script_redirect_patterns() {
    for js in ["location.href='/x'", "location.replace('/x')", "location.assign('/x')", "window.location = '/x'", "window.location='/x'", "LOCATION.HREF = 1"] {
        assert!(sn(&format!("<script>{js}</script>")).script_redirect, "{js}");
    }
    assert!(!sn("<script>if (window.location == 'a') {}</script>").script_redirect);
    assert!(!sn("<script>var a = 1;</script>").script_redirect);
    assert!(!sn(r#"<script src="location.href"></script>"#).script_redirect);
    assert!(!sn("<p>location.href</p>").script_redirect);
}

#[test]
fn script_count_and_visible_text() {
    let r = sn("<html><head><style>p { color: red }</style><script>var a = \"not shown\";</script><script src=x.js></script></head>\n<!-- <script>location.href='/hidden'</script> hidden comment text -->\n<body>Hello   <b>big</b>\nworld&nbsp;&amp; <noscript>enable JS</noscript></body></html>");
    assert_eq!(r.script_count, 2);
    assert!(!r.script_redirect);
    assert_eq!(r.visible_text_length, "Hello big world & enable JS".chars().count() as i64);
}

#[test]
fn comments_are_ignored_everywhere() {
    let r = sn("<!-- <title>no</title><form action='/x'><input type=password> --><title>yes</title>");
    assert_eq!(r.title.as_deref(), Some("yes"));
    assert!(r.form_actions.is_empty() && r.inputs.is_empty());
}

#[test]
fn cut_mid_tag_terminates_without_crash() {
    for p in ["<form action=\"/x", "<input type=\"pass", "<script>location.", "<!-- open", "<title>abc", "<a href='", "<", "</", "<!", "<meta http-equiv=refresh content=\"0; url=", "<p attr", "<p attr=", "<p a=b"] {
        sn(&format!("<p>ok</p>{p}"));
    }
    assert_eq!(sn("<title>ok</title><form action=\"/x").title.as_deref(), Some("ok"));
    assert!(!sn("<p>ok</p><script>location.").script_redirect);
}

#[test]
fn empty_and_garbage_bodies() {
    assert_eq!(sniff(&[], None), HtmlSniff::default());
    let g: Vec<u8> = (0..5000u32).map(|i| i.wrapping_mul(31) as u8).collect();
    sniff(&g, Some("text/html; charset=bogus"));
}

#[test]
fn windows1252_by_header() {
    let mut bytes = b"<title>caf".to_vec();
    bytes.extend([0xE9, 0x20, 0x80, 0x20, 0x93]);
    bytes.extend(b"x");
    bytes.push(0x94);
    bytes.extend(b"</title>");
    let r = sniff(&bytes, Some("text/html; Charset=\"windows-1252\""));
    assert_eq!(r.title.as_deref(), Some("caf\u{E9} \u{20AC} \u{201C}x\u{201D}"));
    assert_eq!(sniff(&bytes, Some("text/html; charset=ISO-8859-1")).title, r.title);
}

#[test]
fn windows1252_by_meta_in_first1024_bytes() {
    let mut b = b"<meta charset=windows-1252><title>na".to_vec();
    b.push(0xEF);
    b.extend(b"ve</title>");
    assert_eq!(sniff(&b, Some("text/html")).title.as_deref(), Some("na\u{EF}ve"));
    let mut h = br#"<meta http-equiv="Content-Type" content="text/html; charset=iso-8859-1"><title>"#.to_vec();
    h.push(0xFC);
    h.extend(b"</title>");
    assert_eq!(sniff(&h, None).title.as_deref(), Some("\u{FC}"));
    let mut late = vec![b' '; 1100];
    late.extend(b"<meta charset=windows-1252><title>");
    late.push(0xE9);
    late.extend(b"</title>");
    assert_eq!(sniff(&late, None).title.as_deref(), Some("\u{FFFD}"));
}

#[test]
fn header_charset_beats_meta() {
    let mut b = b"<meta charset=windows-1252><title>".to_vec();
    b.extend([0xC3, 0xA9]);
    b.extend(b"</title>");
    assert_eq!(sniff(&b, Some("text/html; charset=utf-8")).title.as_deref(), Some("\u{E9}"));
}

#[test]
fn utf16_with_bom() {
    let units: Vec<u16> = "<title>Hi \u{20AC}</title>".encode_utf16().collect();
    let mut le = vec![0xFF, 0xFE];
    le.extend(units.iter().flat_map(|u| [(u & 0xFF) as u8, (u >> 8) as u8]));
    let mut be = vec![0xFE, 0xFF];
    be.extend(units.iter().flat_map(|u| [(u >> 8) as u8, (u & 0xFF) as u8]));
    assert_eq!(sniff(&le, Some("text/html")).title.as_deref(), Some("Hi \u{20AC}"));
    assert_eq!(sniff(&be, Some("text/html; charset=utf-8")).title.as_deref(), Some("Hi \u{20AC}"));
}

#[test]
fn utf8_bom_is_dropped() {
    let mut b = vec![0xEF, 0xBB, 0xBF];
    b.extend(b"<title>x</title>");
    assert_eq!(sniff(&b, None).title.as_deref(), Some("x"));
}

#[test]
fn invalid_utf8_gets_replacement_and_shift_jis_does_not_crash() {
    let mut bad = b"<title>a".to_vec();
    bad.extend([0xFF, 0xFE, 0xFD]);
    bad.extend(b"b</title>");
    assert!(sniff(&bad, None).title.unwrap().starts_with('a'));
    let mut sjis = b"<meta charset=shift_jis><title>".to_vec();
    sjis.extend([0x83, 0x65, 0x83, 0x58, 0x83, 0x67]);
    sjis.extend(b"</title>");
    let _ = sniff(&sjis, None).title;
}

#[test]
fn body_cut_at512_kb_mid_tag() {
    let mut html = "<html><head><title>Big page</title></head><body>".to_string();
    while html.len() < 512 * 1024 - 40 {
        html += "<p>filler paragraph with some words in it</p>\n";
    }
    html += "<form action=\"https://evil.example/collect\"><input type=\"passw";
    let mut bytes = html.into_bytes();
    bytes.extend(vec![0x61; 4000]);
    let r = sniff(&bytes, Some("text/html"));
    assert_eq!(r.title.as_deref(), Some("Big page"));
    assert!(r.inputs.is_empty());
    assert!(r.visible_text_length > 100_000);
}

#[test]
fn many_unclosed_tags_stay_linear() {
    assert_eq!(sniff("<a ".repeat(150_000).as_bytes(), None).script_count, 0);
    assert_eq!(sniff("<script>".repeat(60_000).as_bytes(), None).script_count, 1);
}

#[test]
fn script_redirect_fires_on_assignments_and_calls() {
    let yes = [
        "location = '/x'", "location='/x'", "location.href = '/x'", "location . href = '/x'", "window.location = '/x'",
        "window.location.href = '/x'", "window . location . href = '/x'", "document.location = '/x'", "document.location.href='/x'",
        "top.location = '/x'", "top.location.href = '/x'", "self.location = '/x'", "self.location.replace('/x')", "parent.location = '/x'",
        "location.replace('/x')", "location.replace ('/x')", "location.assign('/x')", "window.location.replace('/x')",
        "window.location.assign( '/x' )", "location[\"href\"] = '/x'", "location['href']='/x'", "window.location[ \"href\" ] = 1",
        "location[\"replace\"]('/x')", "window.location = 1", "setTimeout(function(){ location.href = u }, 5)",
        "if (a) location = b; else x()", "var u = 1;\nlocation\n=\n'/x'", "document.location.replace('/x')", "Location.Replace('/x')",
        "x=1;window.location=x",
    ];
    for js in yes {
        assert!(sn(&format!("<script>{js}</script>")).script_redirect, "{js}");
    }
}

#[test]
fn script_redirect_ignores_reads_comparisons_and_lookalikes() {
    let no = [
        "var u = location.href", "var u = window.location.href;", "if (location.href.indexOf('a') > -1) {}",
        "ga('send', 'pageview', location.href)", "location.href == x", "location.href === x", "location.href != x",
        "location.href !== x", "window.location == 'a'", "window.location === a", "window.location != a", "mylocation = 1",
        "geolocation = 1", "navigator.geolocation.getCurrentPosition(f)", "var location_name = 1", "location.hostname = 'a'",
        "location.search", "location.hash = '#x'", "foo(location)", "x = location", "console.log(location.replace)", "a.location = 1",
        "this.location = 1", "obj.window.location = 1", "location => 1", "relocation.href = 1", "location.reload()",
        "location.href.replace('a','b')", "location[\"hash\"] = 1", "location.href", "var s = 'location is here'", "",
    ];
    for js in no {
        assert!(!sn(&format!("<script>{js}</script>")).script_redirect, "{js}");
    }
}

#[test]
fn script_redirect_needs_inline_script() {
    assert!(!sn("<script src=a.js>location.href = '/x'</script>").script_redirect);
    assert!(sn("<script>var a=1;</script><script>location.replace('/x')</script>").script_redirect);
    assert!(!sn("<p>location.href = '/x'</p>").script_redirect);
    assert!(!sn("<script>location.").script_redirect);
}

#[test]
fn script_redirect_meta_refresh_unchanged() {
    let r = sn(r#"<meta http-equiv="refresh" content="0; url=/x"><script>var u = location.href</script>"#);
    assert_eq!(r.meta_refresh_url.as_deref(), Some("/x"));
    assert!(!r.script_redirect);
}

#[test]
fn script_redirect_adversarial_input_stays_fast() {
    let cases = [
        "location".repeat(65_000),
        "location ".repeat(58_000),
        "window.location.".repeat(32_000),
        "a . ".repeat(100_000) + "location",
        "location".to_string() + &" ".repeat(500_000),
        "location[\"".repeat(50_000),
        ".location".repeat(58_000),
        "x ".repeat(250_000),
    ];
    for js in cases {
        let body = format!("<script>{js}</script>");
        let b = &body.as_bytes()[..body.len().min(512 * 1024)];
        let start = Instant::now();
        sniff(b, None);
        assert!(start.elapsed().as_secs_f64() < 2.0);
    }
}

// MARK: PeekRDAPTests.swift

fn rdap(name: &str) -> Vec<u8> {
    fixture_bytes(&format!("peek/rdap/{name}"))
}

#[test]
fn real_verisign_com() {
    assert_eq!(rdap_registration_date_of(&rdap("com.json")), Some(utc(1997, 9, 15, 4, 0, 0)));
}

#[test]
fn real_pir_org_with_fractional_seconds() {
    let d = rdap_registration_date_of(&rdap("org.json")).unwrap();
    assert!((secs(d) - secs(utc(2001, 1, 13, 0, 12, 14)) - 0.754).abs() < 0.001);
}

#[test]
fn real_google_registry_dev() {
    let d = rdap_registration_date_of(&rdap("dev.json")).unwrap();
    assert!((secs(d) - secs(utc(2018, 10, 29, 15, 57, 39)) - 0.435).abs() < 0.001);
}

#[test]
fn real_nominet_uk() {
    assert_eq!(rdap_registration_date_of(&rdap("uk.json")), Some(utc(1994, 12, 13, 3, 49, 48)));
}

#[test]
fn not_found_body_has_no_date() {
    assert_eq!(rdap_registration_date_of(&rdap("404.json")), None);
}

#[test]
fn offsets_and_odd_forms() {
    let reg = |s: &str| rdap_registration_date_of(format!(r#"{{"events":[{{"eventAction":"Registration","eventDate":"{s}"}}]}}"#).as_bytes());
    assert_eq!(reg("2020-03-04T05:06:07+02:00"), Some(utc(2020, 3, 4, 3, 6, 7)));
    assert_eq!(reg("2020-03-04T05:06:07-0530"), Some(utc(2020, 3, 4, 10, 36, 7)));
    assert!(reg("2020-03-04T05:06:07.123456789Z").is_some());
    assert_eq!(reg("2020-03-04T05:06:07z"), Some(utc(2020, 3, 4, 5, 6, 7)));
    for s in ["2020-02-30T00:00:00Z", "2020-03-04T05:06:07", "yesterday", ""] {
        assert_eq!(reg(s), None, "{s}");
    }
}

#[test]
fn skips_other_events_and_bad_shapes() {
    assert_eq!(rdap_registration_date_of(br#"{"events":[{"eventAction":"expiration","eventDate":"2030-01-01T00:00:00Z"}]}"#), None);
    assert_eq!(
        rdap_registration_date_of(br#"{"events":[{"eventAction":"registration"},{"eventAction":"registration","eventDate":"2010-01-01T00:00:00Z"}]}"#),
        Some(utc(2010, 1, 1, 0, 0, 0))
    );
    for bad in ["", "[]", "{}", "null", r#"{"events":"x"}"#, r#"{"events":[1,null,"a"]}"#, "{{{", r#"{"events":[{"eventAction":5,"eventDate":5}]}"#] {
        assert_eq!(rdap_registration_date_of(bad.as_bytes()), None, "{bad}");
    }
}

#[test]
fn oversized_body_is_ignored() {
    let json = format!(r#"{{"pad":"{}","events":[{{"eventAction":"registration","eventDate":"2010-01-01T00:00:00Z"}}]}}"#, "x".repeat(300 * 1024));
    assert_eq!(rdap_registration_date_of(json.as_bytes()), None);
}

const SAMPLE: &str = r#"{"description":"x","publication":"2026-01-01T00:00:00Z","services":[
  [["com","net"],["http://rdap.example.com/","https://rdap.example.com/v1"]],
  [["ORG"],["http://only-http.example/"]],
  [["uk"],["https://rdap.nominet.example/uk/"]],
  [["xn--p1ai"],["https://rdap.cctld.example/"]],
  [["com"],["https://dup.example/"]],
  [[],[]], ["junk"], 5
]}"#;

#[test]
fn bootstrap_prefers_https_and_adds_slash() {
    let b = RdapBootstrap::new(SAMPLE.as_bytes());
    assert_eq!(b.base_url("com").as_deref(), Some("https://rdap.example.com/v1/"));
    assert_eq!(b.base_url("net").as_deref(), Some("https://rdap.example.com/v1/"));
    assert_eq!(b.base_url("org").as_deref(), Some("http://only-http.example/"));
    assert_eq!(b.base_url("COM").as_deref(), Some("https://rdap.example.com/v1/"));
    assert_eq!(b.base_url("zzz"), None);
}

#[test]
fn bootstrap_query_url_uses_last_label() {
    let b = RdapBootstrap::new(SAMPLE.as_bytes());
    assert_eq!(b.query_url("example.com").as_deref(), Some("https://rdap.example.com/v1/domain/example.com"));
    assert_eq!(b.query_url("Shop.Example.CO.UK").as_deref(), Some("https://rdap.nominet.example/uk/domain/shop.example.co.uk"));
    assert_eq!(b.query_url("xn--80aswg.xn--p1ai").as_deref(), Some("https://rdap.cctld.example/domain/xn--80aswg.xn--p1ai"));
    assert_eq!(b.query_url("example.zzz"), None);
    assert_eq!(b.query_url(""), None);
    assert!(b.query_url("com").is_some());
    for bad in ["exa mple.com", "example.com/x", "münchen.com", "example.com.", "a.com?x=1", "ex@mple.com"] {
        assert_eq!(b.query_url(bad), None, "{bad}");
    }
}

#[test]
fn bad_bootstrap_json_knows_nothing() {
    for bad in ["", "{}", "[]", "nonsense", r#"{"services":5}"#, r#"{"services":[[["com"]]]}"#] {
        let b = RdapBootstrap::new(bad.as_bytes());
        assert_eq!(b.base_url("com"), None);
        assert_eq!(b.query_url("example.com"), None);
    }
}

#[test]
fn shipped_iana_file_parses() {
    let b = RdapBootstrap::new(qr_core::data::resource("rdap-dns.json").unwrap());
    for tld in ["com", "org", "net", "uk", "dev", "app", "xyz"] {
        let base = b.base_url(tld).unwrap_or_else(|| panic!("{tld}"));
        assert!(base.starts_with("https://") && base.ends_with('/'), "{tld} {base}");
    }
    assert_eq!(b.query_url("example.com").as_deref(), Some("https://rdap.verisign.com/com/v1/domain/example.com"));
}

// MARK: PeekDomainAgeTests

#[test]
fn whole_days_floor_never_negative() {
    let t = at(1_700_000_000.0);
    let plus = |s: f64| at(1_700_000_000.0 + s);
    assert_eq!(domain_age_days(t, t), 0);
    assert_eq!(domain_age_days(t, plus(86_399.0)), 0);
    assert_eq!(domain_age_days(t, plus(86_400.0)), 1);
    assert_eq!(domain_age_days(t, plus(86_400.0 * 30.0 + 3600.0)), 30);
    assert_eq!(domain_age_days(t, plus(-5.0 * 86_400.0)), 0);
    // Foundation's distantPast/distantFuture are ±63 113 904 000 s around 2001.
    assert!(domain_age_days_in(2.0 * 63_113_904_000.0) > 1_000_000);
    assert_eq!(domain_age_days_in(f64::NAN), 0);
}
