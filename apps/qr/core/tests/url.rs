//! Swift parity: URLPublicSuffixTests (with the list's own test_psl.txt vectors),
//! URLPunycodeTests, URLSchemelessResolveTests and URLWebAddressTests.

mod common;
use common::*;
use qr_core::url::{host, punycode};
use qr_core::*;

fn psl() -> std::sync::Arc<BundledData> {
    shared()
}

fn parse(s: &str) -> Option<WebAddress> {
    WebAddress::parse(s, &psl().public_suffixes)
}

fn p(s: &str) -> WebAddress {
    parse(s).unwrap_or_else(|| panic!("{s}"))
}

// MARK: URLPublicSuffixTests.swift

fn vectors() -> Vec<(String, Option<String>)> {
    let text = fixture_text("url/test_psl.txt");
    let mut out = Vec::new();
    for line in text.split('\n').filter(|l| l.starts_with("checkPublicSuffix(")) {
        let inner = &line["checkPublicSuffix(".len()..line.len() - 2];
        let parts: Vec<&str> = inner.split(", ").collect();
        let value = |s: &str| if s == "null" { None } else { Some(s[1..s.len() - 1].to_string()) };
        if parts.len() != 2 {
            continue;
        }
        let Some(input) = value(parts[0]) else { continue };
        out.push((input, value(parts[1])));
    }
    out
}

#[test]
fn vector_file_loaded() {
    assert!(vectors().len() >= 70);
}

#[test]
fn list_own_vector() {
    let list = &psl().public_suffixes;
    for (input, expected) in vectors() {
        let h = host::parse(&input).map(|h| h.ascii).unwrap_or_default();
        let want = expected.and_then(|e| host::parse(&e).map(|h| h.ascii));
        assert_eq!(list.match_host(&h).registrable_domain, want, "{input}");
    }
}

#[test]
fn private_suffix() {
    let m = psl().public_suffixes.match_host("user.github.io");
    assert_eq!(m.public_suffix, "github.io");
    assert_eq!(m.registrable_domain.as_deref(), Some("user.github.io"));
    assert!(m.is_private && m.matched_explicit_rule);
    assert_eq!(m.icann_registrable_domain.as_deref(), Some("github.io"));
}

#[test]
fn icann_suffix_is_not_private() {
    let m = psl().public_suffixes.match_host("www.bbc.co.uk");
    assert_eq!(m.public_suffix, "co.uk");
    assert_eq!(m.registrable_domain.as_deref(), Some("bbc.co.uk"));
    assert!(!m.is_private);
    assert_eq!(m.icann_registrable_domain.as_deref(), Some("bbc.co.uk"));
}

#[test]
fn psl_host_that_is_a_suffix() {
    let m = psl().public_suffixes.match_host("co.uk");
    assert_eq!(m.public_suffix, "co.uk");
    assert_eq!(m.registrable_domain, None);
}

#[test]
fn wildcard_and_exception() {
    let l = &psl().public_suffixes;
    let wild = l.match_host("b.test.ck");
    assert_eq!(wild.public_suffix, "test.ck");
    assert_eq!(wild.registrable_domain.as_deref(), Some("b.test.ck"));
    let exc = l.match_host("www.ck");
    assert_eq!(exc.public_suffix, "ck");
    assert_eq!(exc.registrable_domain.as_deref(), Some("www.ck"));
    assert!(exc.matched_explicit_rule);
}

#[test]
fn default_rule_is_not_explicit() {
    let m = psl().public_suffixes.match_host("a.example.zzzz");
    assert_eq!(m.public_suffix, "zzzz");
    assert_eq!(m.registrable_domain.as_deref(), Some("example.zzzz"));
    assert!(!m.matched_explicit_rule);
}

#[test]
fn empty_list_only_default_rule() {
    let m = PublicSuffixList::new("").match_host("www.example.com");
    assert_eq!(m.registrable_domain.as_deref(), Some("example.com"));
    assert!(!m.matched_explicit_rule);
}

#[test]
fn unicode_rules_are_matched_in_ascii() {
    let list = PublicSuffixList::new("// ===BEGIN ICANN DOMAINS===\n公司.cn\n中国\n// ===END ICANN DOMAINS===\n");
    assert_eq!(list.match_host("xn--85x722f.xn--55qx5d.cn").registrable_domain.as_deref(), Some("xn--85x722f.xn--55qx5d.cn"));
    assert_eq!(list.match_host("xn--55qx5d.cn").registrable_domain, None);
}

#[test]
fn private_section_marker() {
    let list = PublicSuffixList::new("com\n// ===BEGIN PRIVATE DOMAINS===\nblogspot.com\n// ===END PRIVATE DOMAINS===\n");
    let m = list.match_host("x.blogspot.com");
    assert!(m.is_private);
    assert_eq!(m.icann_registrable_domain.as_deref(), Some("blogspot.com"));
    assert!(!list.match_host("x.example.com").is_private);
}

// MARK: URLPunycodeTests.swift

const VECTORS: &[(&str, &str, &str)] = &[
    ("Arabic", "\u{644}\u{64A}\u{647}\u{645}\u{627}\u{628}\u{62A}\u{643}\u{644}\u{645}\u{648}\u{634}\u{639}\u{631}\u{628}\u{64A}\u{61F}", "egbpdaj6bu4bxfgehfvwxn"),
    ("Chinese simplified", "\u{4ED6}\u{4EEC}\u{4E3A}\u{4EC0}\u{4E48}\u{4E0D}\u{8BF4}\u{4E2D}\u{6587}", "ihqwcrb4cv8a8dqg056pqjye"),
    ("Chinese traditional", "\u{4ED6}\u{5011}\u{7232}\u{4EC0}\u{9EBD}\u{4E0D}\u{8AAA}\u{4E2D}\u{6587}", "ihqwctvzc91f659drss3x8bo0yb"),
    ("Czech", "Pro\u{10D}prost\u{11B}nemluv\u{ED}\u{10D}esky", "Proprostnemluvesky-uyb24dma41a"),
    ("Hebrew", "\u{5DC}\u{5DE}\u{5D4}\u{5D4}\u{5DD}\u{5E4}\u{5E9}\u{5D5}\u{5D8}\u{5DC}\u{5D0}\u{5DE}\u{5D3}\u{5D1}\u{5E8}\u{5D9}\u{5DD}\u{5E2}\u{5D1}\u{5E8}\u{5D9}\u{5EA}", "4dbcagdahymbxekheh6e0a7fei0b"),
    ("Hindi", "\u{92F}\u{939}\u{932}\u{94B}\u{917}\u{939}\u{93F}\u{928}\u{94D}\u{926}\u{940}\u{915}\u{94D}\u{92F}\u{94B}\u{902}\u{928}\u{939}\u{940}\u{902}\u{92C}\u{94B}\u{932}\u{938}\u{915}\u{924}\u{947}\u{939}\u{948}\u{902}", "i1baa7eci9glrd9b2ae1bj0hfcgg6iyaf8o0a1dig0cd"),
    ("Japanese", "\u{306A}\u{305C}\u{307F}\u{3093}\u{306A}\u{65E5}\u{672C}\u{8A9E}\u{3092}\u{8A71}\u{3057}\u{3066}\u{304F}\u{308C}\u{306A}\u{3044}\u{306E}\u{304B}", "n8jok5ay5dzabd5bym9f0cm5685rrjetr6pdxa"),
    ("Russian", "\u{43F}\u{43E}\u{447}\u{435}\u{43C}\u{443}\u{436}\u{435}\u{43E}\u{43D}\u{438}\u{43D}\u{435}\u{433}\u{43E}\u{432}\u{43E}\u{440}\u{44F}\u{442}\u{43F}\u{43E}\u{440}\u{443}\u{441}\u{441}\u{43A}\u{438}", "b1abfaaepdrnnbgefbadotcwatmq2g4l"),
    ("Spanish", "Porqu\u{E9}nopuedensimplementehablarenEspa\u{F1}ol", "PorqunopuedensimplementehablarenEspaol-fmd56a"),
    ("Vietnamese", "T\u{1EA1}isaoh\u{1ECD}kh\u{F4}ngth\u{1EC3}ch\u{1EC9}n\u{F3}iti\u{1EBF}ngVi\u{1EC7}t", "TisaohkhngthchnitingVit-kjcr8268qyxafd2f1b9g"),
    ("3nen B gumi", "3\u{5E74}B\u{7D44}\u{91D1}\u{516B}\u{5148}\u{751F}", "3B-ww4c5e180e575a65lsy2b"),
    ("hitotsu yane", "\u{3072}\u{3068}\u{3064}\u{5C4B}\u{6839}\u{306E}\u{4E0B}2", "2-u9tlzr9756bt3uc0v"),
];

#[test]
fn encode_vector() {
    for (name, unicode, ascii) in VECTORS {
        assert_eq!(punycode::encode(unicode).as_deref(), Some(*ascii), "{name}");
    }
}

#[test]
fn decode_vector() {
    for (name, unicode, ascii) in VECTORS {
        assert_eq!(punycode::decode(ascii).as_deref(), Some(*unicode), "{name}");
    }
}

#[test]
fn bucher_round_trip() {
    assert_eq!(punycode::encode("bücher").as_deref(), Some("bcher-kva"));
    assert_eq!(punycode::decode("bcher-kva").as_deref(), Some("bücher"));
    assert_eq!(punycode::decode("BCHER-KVA").as_deref(), Some("BüCHER"));
}

#[test]
fn bad_input_is_nil() {
    for s in ["", "ab!c", "99999999999", "bücher-kva"] {
        assert_eq!(punycode::decode(s), None, "{s}");
    }
    assert_eq!(punycode::encode(""), None);
}

// MARK: URLSchemelessResolveTests.swift

fn schemeless(s: &str) -> Option<WebAddress> {
    WebAddress::parse_schemeless(s, &psl().public_suffixes)
}

#[test]
fn schemeless_links() {
    for s in ["www.example.com", "example.com/x", "example.com?x=1", "Example.COM", "bücher.de/x", "a.b.example.co.uk#f"] {
        let a = schemeless(s).unwrap_or_else(|| panic!("{s}"));
        assert!(a.assumed_https);
        assert_eq!(a.link.scheme, "https");
        assert_eq!(a.link.original, s);
        assert!(a.link.registrable_domain.is_some());
    }
}

#[test]
fn schemeless_normalized() {
    let a = schemeless("www.example.com").unwrap();
    assert_eq!(a.link.normalized, "https://www.example.com/");
    assert_eq!(a.link.host_ascii, "www.example.com");
}

#[test]
fn schemeless_not_links() {
    for s in [
        "readme.txt", "hello world.com", "me@example.com", "localhost", "/path.com", ".example.com", "example.com:8080/x",
        "mailto:x@example.com", "foo.zzzz", "1.2.3.4", "example..com", "a_b.example.com", "https://example.com", "example.com.", "",
        " ", "example.com/a b",
    ] {
        assert!(schemeless(s).is_none(), "{s}");
    }
}

fn base() -> LinkInfo {
    p("https://example.com/a/b/c?x=1#f").link
}

fn resolve(r: &str, b: &LinkInfo) -> Option<WebAddress> {
    WebAddress::resolve(r, b, &psl().public_suffixes)
}

#[test]
fn resolved() {
    for (r, want) in [
        ("/x", "https://example.com/x"),
        ("d", "https://example.com/a/b/d"),
        ("./d", "https://example.com/a/b/d"),
        ("../d", "https://example.com/a/d"),
        ("../../../d", "https://example.com/d"),
        ("d/e/../f", "https://example.com/a/b/d/f"),
        ("d/..", "https://example.com/a/b/"),
        ("?q=2", "https://example.com/a/b/c?q=2"),
        ("#z", "https://example.com/a/b/c?x=1#z"),
        ("", "https://example.com/a/b/c?x=1"),
        ("d?y#z", "https://example.com/a/b/d?y#z"),
        ("//evil.com/p", "https://evil.com/p"),
        (r"\\evil.com\p", "https://evil.com/p"),
        (r"/\evil.com", "https://evil.com/"),
        ("http://other.org/q", "http://other.org/q"),
        ("HTTPS://Other.ORG", "https://other.org/"),
        ("  /x\n", "https://example.com/x"),
        (r"/a\b", "https://example.com/a/b"),
        ("/a/%2e%2e/z", "https://example.com/z"),
    ] {
        assert_eq!(resolve(r, &base()).map(|a| a.link.normalized).as_deref(), Some(want), "{r}");
    }
}

#[test]
fn resolve_rejects_non_http() {
    for s in ["javascript:alert(1)", "ftp://example.com/x", "mailto:a@b.co", "data:text/html,hi", "http:///"] {
        assert!(resolve(s, &base()).is_none(), "{s}");
    }
}

#[test]
fn resolve_keeps_userinfo_and_port() {
    let r = resolve("z", &p("http://u@example.com:8080/x/y").link).unwrap();
    assert_eq!(r.link.normalized, "http://u@example.com:8080/x/z");
    assert_eq!(r.link.userinfo.as_deref(), Some("u"));
    assert_eq!(r.link.port, Some(8080));
}

#[test]
fn resolve_against_ip_base() {
    let r = resolve("c", &p("http://[::1]:3000/a/b").link).unwrap();
    assert_eq!(r.link.normalized, "http://[::1]:3000/a/c");
    assert_eq!(r.host_kind, HostKind::Ipv6);
}

#[test]
fn resolve_gives_fresh_psl_fields() {
    let r = resolve("//user.github.io/x", &base()).unwrap();
    assert!(r.link.suffix_is_private);
    assert_eq!(r.link.registrable_domain.as_deref(), Some("user.github.io"));
}

// MARK: URLWebAddressTests.swift

#[test]
fn userinfo_trick() {
    let a = p("https://bank.com@evil.site/login");
    assert_eq!(a.link.userinfo.as_deref(), Some("bank.com"));
    assert_eq!(a.link.host_ascii, "evil.site");
    assert_eq!(a.link.registrable_domain.as_deref(), Some("evil.site"));
    assert_eq!(a.link.path_and_query, "/login");
    assert_eq!(a.link.normalized, "https://bank.com@evil.site/login");
}

#[test]
fn multiple_at_signs_use_the_last() {
    let a = p("https://a@b@evil.site/");
    assert_eq!(a.link.userinfo.as_deref(), Some("a@b"));
    assert_eq!(a.link.host_ascii, "evil.site");
}

#[test]
fn user_and_password_with_port() {
    let a = p("http://user:pw@example.com:8080/x");
    assert_eq!(a.link.userinfo.as_deref(), Some("user:pw"));
    assert_eq!(a.link.port, Some(8080));
}

#[test]
fn empty_userinfo_is_nil() {
    assert_eq!(p("https://@example.com/").link.userinfo, None);
}

#[test]
fn backslashes_after_scheme() {
    let a = p(r"https:\\evil.com\path");
    assert_eq!(a.link.host_ascii, "evil.com");
    assert_eq!(a.link.path_and_query, "/path");
}

#[test]
fn backslash_ends_authority() {
    let a = p(r"https://good.com\@evil.com");
    assert_eq!(a.link.host_ascii, "good.com");
    assert_eq!(a.link.userinfo, None);
    assert_eq!(a.link.path_and_query, "/@evil.com");
}

#[test]
fn backslashes_in_path_only_before_query() {
    assert_eq!(p(r"https://example.com/a\b?x=\y#f\g").link.path_and_query, r"/a/b?x=\y#f\g");
}

#[test]
fn no_slashes_at_all() {
    assert_eq!(p("https:evil.com/x").link.host_ascii, "evil.com");
    assert_eq!(p("http:////evil.com").link.host_ascii, "evil.com");
}

#[test]
fn percent_encoded_host() {
    assert_eq!(p("https://exa%6Dple.com/").link.host_ascii, "example.com");
    assert!(parse("https://exa%20mple.com/").is_none());
    assert!(parse("https://exa%zzmple.com/").is_none());
}

#[test]
fn fragment_instead_of_path() {
    assert_eq!(p("https://example.com#frag").link.path_and_query, "/#frag");
    assert_eq!(p("https://example.com?q=1#f").link.path_and_query, "/?q=1#f");
}

#[test]
fn tabs_and_newlines_are_removed() {
    let a = p("ht\ttps://exa\nmple.com/a\r\nb");
    assert_eq!(a.link.scheme, "https");
    assert_eq!(a.link.host_ascii, "example.com");
    assert_eq!(a.link.path_and_query, "/ab");
}

#[test]
fn controls_and_spaces_are_trimmed() {
    let raw = "  \u{1}https://example.com/x \u{7} ";
    let a = p(raw);
    assert_eq!(a.link.normalized, "https://example.com/x");
    assert_eq!(a.link.original, raw);
}

#[test]
fn uppercase_scheme_and_host() {
    let a = p("HTTPS://WWW.EXAMPLE.COM/Path");
    assert_eq!(a.link.scheme, "https");
    assert_eq!(a.link.host_ascii, "www.example.com");
    assert_eq!(a.link.path_and_query, "/Path");
    assert_eq!(a.link.registrable_domain.as_deref(), Some("example.com"));
    assert_eq!(a.link.public_suffix.as_deref(), Some("com"));
}

#[test]
fn trailing_dot_dropped() {
    assert_eq!(p("https://example.com./").link.host_ascii, "example.com");
}

#[test]
fn ideographic_full_stops() {
    for s in ["https://example\u{3002}com/", "https://example\u{FF0E}com/", "https://example\u{FF61}com/"] {
        assert_eq!(p(s).link.host_ascii, "example.com");
    }
}

#[test]
fn empty_or_missing_host() {
    for s in ["http:///", "https://", "https://@/", "https://:80/"] {
        assert!(parse(s).is_none(), "{s}");
    }
}

#[test]
fn not_http_is_nil() {
    for s in ["ftp://example.com/", "javascript:alert(1)", "example.com", ""] {
        assert!(parse(s).is_none(), "{s}");
    }
}

#[test]
fn oversized_input_is_bounded() {
    assert!(p(&format!("https://example.com/{}", "a".repeat(20000))).link.path_and_query.len() <= 8192);
    assert!(p(&format!("https://example.com/{}", "é".repeat(10000))).link.path_and_query.len() <= 8192);
    assert!(parse(&"x".repeat(100_000)).is_none());
}

#[test]
fn scheme_detected() {
    for (s, want) in [("https://x.com", "https"), ("HTTP://x", "http"), ("  javascript:alert(1)", "javascript"), ("mailto:a@b.c", "mailto"), ("web+app:x", "web+app"), ("example.com:8080/x", "example.com")] {
        assert_eq!(WebAddress::scheme_of(s).as_deref(), Some(want), "{s}");
    }
}

#[test]
fn scheme_absent() {
    for s in ["hello", "1abc:x", ":x", "a b:c", "path/to:x", "x?y=a:b", "", "é:x"] {
        assert_eq!(WebAddress::scheme_of(s), None, "{s}");
    }
}

#[test]
fn default_ports_are_dropped() {
    for s in ["http://example.com:80/", "https://example.com:443", "https://example.com:"] {
        assert_eq!(p(s).link.port, None, "{s}");
    }
}

#[test]
fn custom_ports_kept() {
    assert_eq!(p("https://example.com:8443/x").link.port, Some(8443));
    let a = p("http://example.com:443/");
    assert_eq!(a.link.port, Some(443));
    assert_eq!(a.link.normalized, "http://example.com:443/");
}

#[test]
fn bad_ports() {
    for s in ["https://example.com:99999/", "https://example.com:80a/", "https://example.com:1:2/"] {
        assert!(parse(s).is_none(), "{s}");
    }
}

#[test]
fn ipv4_forms() {
    for (s, want) in [
        ("http://3232235777/", "192.168.1.1"),
        ("http://0x7f.1/", "127.0.0.1"),
        ("http://0177.0.0.1/", "127.0.0.1"),
        ("http://127.1/", "127.0.0.1"),
        ("http://192.168.1.1./", "192.168.1.1"),
        ("http://0xC0A80101/", "192.168.1.1"),
        ("http://10.0.0.1:81/x", "10.0.0.1"),
    ] {
        let a = p(s);
        assert_eq!(a.host_kind, HostKind::Ipv4);
        assert_eq!(a.link.host_ascii, want);
        assert!(a.link.is_ip_address);
        assert_eq!(a.link.registrable_domain, None);
        assert_eq!(a.link.public_suffix, None);
    }
}

#[test]
fn ipv4_out_of_range() {
    for s in ["http://256.1.1.1/", "http://1.2.3.4.5/", "http://4294967296/", "http://1.2.3.08/", "http://999999999999999/"] {
        assert!(parse(s).is_none(), "{s}");
    }
}

#[test]
fn ipv6() {
    let a = p("http://[::1]:8080/");
    assert_eq!(a.host_kind, HostKind::Ipv6);
    assert_eq!(a.link.host_ascii, "[::1]");
    assert_eq!(a.link.port, Some(8080));
    assert!(a.link.is_ip_address);
    assert_eq!(p("https://[2001:DB8::1]/").link.host_ascii, "[2001:db8::1]");
    assert!(parse("http://[::1/").is_none());
    assert!(parse("http://[zz]/").is_none());
}

#[test]
fn last_label_not_numeric_is_domain() {
    assert_eq!(p("http://1.2.3.4x/").host_kind, HostKind::Domain);
}

#[test]
fn unicode_host_gets_punycode() {
    let a = p("https://bücher.de/x");
    assert_eq!(a.link.host_ascii, "xn--bcher-kva.de");
    assert_eq!(a.link.host_unicode, "bücher.de");
    assert_eq!(a.link.registrable_domain.as_deref(), Some("bücher.de"));
    assert_eq!(a.link.public_suffix.as_deref(), Some("de"));
    assert_eq!(a.link.normalized, "https://xn--bcher-kva.de/x");
    assert!(!a.has_malformed_label);
}

#[test]
fn punycode_host_decodes() {
    let a = p("https://xn--bcher-kva.de");
    assert_eq!(a.link.host_unicode, "bücher.de");
    assert_eq!(a.link.host_ascii, "xn--bcher-kva.de");
}

#[test]
fn uppercase_and_decomposed_unicode() {
    assert_eq!(p("https://BÜCHER.de").link.host_ascii, "xn--bcher-kva.de");
    assert_eq!(p("https://bu\u{308}cher.de").link.host_ascii, "xn--bcher-kva.de");
}

#[test]
fn malformed_punycode_label() {
    for s in ["https://xn--ab!c.com/", "https://xn--a-.com/", "https://xn--.com/"] {
        let a = p(s);
        assert!(a.has_malformed_label, "{s}");
        assert!(a.link.host_ascii.starts_with("xn--"));
    }
}

#[test]
fn idn_suffix_is_reported_in_unicode() {
    let a = p("https://食狮.中国/");
    assert_eq!(a.link.host_ascii, "xn--85x722f.xn--fiqs8s");
    assert_eq!(a.link.public_suffix.as_deref(), Some("中国"));
    assert_eq!(a.link.registrable_domain.as_deref(), Some("食狮.中国"));
}

#[test]
fn private_suffix_host() {
    let a = p("https://user.github.io/");
    assert_eq!(a.link.registrable_domain.as_deref(), Some("user.github.io"));
    assert_eq!(a.link.public_suffix.as_deref(), Some("github.io"));
    assert!(a.link.suffix_is_private && a.suffix_is_known);
}

#[test]
fn host_that_is_a_suffix() {
    let a = p("https://co.uk/");
    assert_eq!(a.link.registrable_domain, None);
    assert_eq!(a.link.public_suffix.as_deref(), Some("co.uk"));
}

#[test]
fn wildcard_and_exception_through_parse() {
    assert_eq!(p("https://a.b.test.ck/").link.registrable_domain.as_deref(), Some("b.test.ck"));
    assert_eq!(p("https://www.ck/").link.registrable_domain.as_deref(), Some("www.ck"));
    assert_eq!(p("https://test.ck/").link.registrable_domain, None);
}

#[test]
fn unknown_suffix_falls_back_to_the_last_label() {
    let a = p("https://www.example.zzzz/");
    assert_eq!(a.link.registrable_domain.as_deref(), Some("example.zzzz"));
    assert_eq!(a.link.public_suffix.as_deref(), Some("zzzz"));
    assert_eq!(p("https://zzzz/").link.registrable_domain, None);
    assert_eq!(p("https://203.0.113.9/").link.registrable_domain, None);
    assert!(!a.suffix_is_known && !a.assumed_https);
}

// Swift compares and splits by Character: a separator carrying a combining mark is not a separator.
#[test]
fn separators_with_combining_marks_are_not_separators() {
    // "@" + U+0301 is one character, not "@": no userinfo, and the host keeps a forbidden "@".
    assert!(parse("https://bank.com@\u{301}evil.site/").is_none());
    // "." + U+0301 does not end a label.
    assert_eq!(p("https://paypal.\u{301}com/").link.unicode_labels().len(), 1);
}
