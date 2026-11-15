//! Swift parity: StructuredContactTests, StructuredEventTests, StructuredFixtureTests,
//! StructuredHostileTests, StructuredRoundTripTests, StructuredURITests and StructuredWiFiTests.

mod common;
use common::*;
use qr_core::structured::{contact, email, encode, event, geo, phone, sms, wifi};
use qr_core::*;

fn s(v: &str) -> Option<String> {
    Some(v.to_string())
}

fn strs(v: &[&str]) -> Vec<String> {
    v.iter().map(|x| x.to_string()).collect()
}

fn gmt(y: i64, mo: i64, d: i64, h: i64, mi: i64, sec: i64) -> std::time::SystemTime {
    qr_core::structured::ics_wall_as_gmt(y, mo, d, h, mi, sec)
}

// MARK: StructuredContactTests.swift

#[test]
fn folding() {
    assert_eq!(contact::parse("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ann\r\nNOTE:one\r\n two\r\n\tthree\r\nEND:VCARD").unwrap().note, s("onetwothree"));
}

#[test]
fn quoted_printable_charsets() {
    let c = contact::parse("BEGIN:VCARD\nVERSION:2.1\nFN;ENCODING=QUOTED-PRINTABLE;CHARSET=ISO-8859-1:Ren=E9=\ne\nORG;QUOTED-PRINTABLE;CHARSET=UTF-8:=F0=9F=98=80 Co\nTITLE;ENCODING=QUOTED-PRINTABLE:caf=E9\nEND:VCARD").unwrap();
    assert_eq!(c.name, s("Renée"));
    assert_eq!(c.organization, s("😀 Co"));
    assert_eq!(c.title, s("café"));
}

#[test]
fn text_escapes_groups_and_multiples() {
    let c = contact::parse("BEGIN:VCARD\nVERSION:4.0\nitem2.EMAIL;type=work:a@x.org\nEMAIL:b@x.org\nTEL:1\nTEL:2\nORG:A\\;B;Dept\nNOTE:x\\ny\\, z\\\\\nN:;Solo;;;\nEND:VCARD").unwrap();
    assert_eq!(c.emails, strs(&["a@x.org", "b@x.org"]));
    assert_eq!(c.phones, strs(&["1", "2"]));
    assert_eq!(c.organization, s("A;B"));
    assert_eq!(c.note, s("x\ny, z\\"));
    assert_eq!(c.name, s("Solo"));
    assert_eq!(c.family_name, None);
}

#[test]
fn address_order_and_links() {
    let c = contact::parse("BEGIN:VCARD\nADR:;;Street 1;Town;;;DE\nURL:a.example\nURL:b.example\nEND:VCARD").unwrap();
    assert_eq!(c.addresses, strs(&["Street 1\nTown\nDE"]));
    assert_eq!(c.links, strs(&["a.example", "b.example"]));
}

#[test]
fn rejects_non_cards() {
    for t in ["FN:Ann", "BEGIN:VEVENT\nEND:VEVENT", "hello"] {
        assert!(contact::parse(t).is_none(), "{t}");
    }
}

#[test]
fn me_card() {
    let c = contact::parse(r"MECARD:N:Doe,John;TEL:1;TEL:2;ADR:a\, b,c;NOTE:x\:y;;").unwrap();
    assert_eq!(c.name, s("John Doe"));
    assert_eq!(c.phones, strs(&["1", "2"]));
    assert_eq!(c.addresses, strs(&["a, b\nc"]));
    assert_eq!(c.note, s("x:y"));
}

#[test]
fn photo_data_is_ignored() {
    let c = contact::parse("BEGIN:VCARD\nVERSION:2.1\nFN:P\nPHOTO;ENCODING=BASE64;JPEG:/9j/4AAQSkZJRgABAQ\n AAAAAAAAAAAAAAAAAAAA\n\nTEL:5\nEND:VCARD").unwrap();
    assert_eq!(c.name, s("P"));
    assert_eq!(c.phones, strs(&["5"]));
}

// MARK: StructuredEventTests.swift

#[test]
fn utc() {
    let e = event::parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nEND:VEVENT").unwrap();
    assert_eq!(e.start, Some(at(1_794_301_200.0)));
    assert!(!e.is_floating && !e.is_all_day);
    assert_eq!(e.time_zone_id, None);
}

#[test]
fn tzid_known_and_unknown() {
    let manila = event::parse("BEGIN:VEVENT\nDTSTART;TZID=Asia/Manila:20261110T090000\nEND:VEVENT").unwrap();
    assert_eq!(manila.start, Some(at(1_794_301_200.0 - 8.0 * 3600.0)));
    assert_eq!(manila.time_zone_id, s("Asia/Manila"));
    let odd = event::parse("BEGIN:VEVENT\nDTSTART;TZID=Nowhere/Land:20261110T090000\nEND:VEVENT").unwrap();
    assert!(odd.is_floating);
    assert_eq!(odd.time_zone_id, None);
    assert_eq!(odd.start, Some(at(1_794_301_200.0)));
}

#[test]
fn all_day_and_durations() {
    let a = event::parse("BEGIN:VEVENT\nDTSTART:20261110\nEND:VEVENT").unwrap();
    assert!(a.is_all_day);
    assert_eq!(a.start, Some(at(1_794_268_800.0)));
    let d = event::parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:P1DT2H30M15S\nEND:VEVENT").unwrap();
    assert_eq!(d.end, Some(at(1_794_301_200.0 + 86_400.0 + 9_000.0 + 15.0)));
    let w = event::parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:P2W\nEND:VEVENT").unwrap();
    assert_eq!(w.end, Some(at(1_794_301_200.0 + 14.0 * 86_400.0)));
    assert_eq!(event::parse("BEGIN:VEVENT\nDTSTART:20261110T090000Z\nDURATION:PT\nEND:VEVENT").unwrap().end, None);
}

#[test]
fn invalid_dates() {
    for t in ["BEGIN:VEVENT\nDTSTART:20261310T090000Z\nEND:VEVENT", "BEGIN:VEVENT\nDTSTART:20260230\nEND:VEVENT", "BEGIN:VEVENT\nDTSTART:soon\nEND:VEVENT"] {
        assert_eq!(event::parse(t).unwrap().start, None);
    }
}

#[test]
fn calendar_wrapper_and_first_event() {
    let e = event::parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:One\nURL:x.example\nEND:VEVENT\nBEGIN:VEVENT\nSUMMARY:Two\nEND:VEVENT\nEND:VCALENDAR").unwrap();
    assert_eq!(e.summary, s("One"));
    assert_eq!(e.links, strs(&["x.example"]));
    assert!(event::parse("BEGIN:VCALENDAR\nBEGIN:VTODO\nEND:VTODO\nEND:VCALENDAR").is_none());
    assert!(event::parse("hello").is_none());
}

#[test]
fn folded_text() {
    assert_eq!(event::parse("begin:vevent\r\nDESCRIPTION:a\\, b\\;\r\n  c\\nd\r\nEND:VEVENT").unwrap().description, s("a, b; c\nd"));
}

// MARK: StructuredFixtureTests.swift

fn payload_for(c: &FixtureCase) -> Option<Payload> {
    let text = c.scanned_code().text.unwrap_or_default();
    match c.expect.kind.as_str() {
        "wifi" => wifi::parse(&text).map(|info| Payload::Wifi { info }),
        "contact" => contact::parse(&text).map(|info| Payload::Contact { info }),
        "event" => event::parse(&text).map(|info| Payload::Event { info }),
        "phone" => phone::parse(&text).map(|info| Payload::Phone { info }),
        "sms" => sms::parse(&text).map(|info| Payload::Sms { info }),
        "email" => email::parse(&text).map(|info| Payload::Email { info }),
        "geo" => geo::parse(&text).map(|info| Payload::Geo { info }),
        _ => None,
    }
}

#[test]
fn fixtures_parse_to_expected_fields() {
    let cases = load("structured.json");
    assert!(cases.len() >= 30);
    for c in &cases {
        let p = payload_for(c).unwrap_or_else(|| panic!("{}: parser returned nil", c.id));
        assert_eq!(p.type_id(), c.expect.kind, "{}", c.id);
        let f = fields(&p);
        for (k, v) in c.expect.fields.iter().flatten() {
            assert_eq!(f.get(k), Some(v), "{}: fields[{k}]", c.id);
        }
    }
}

#[test]
fn fixture_ids_and_levels_follow_the_convention() {
    let prefixes = ["wifi-", "vcard-", "mecard-", "vevent-", "tel-", "sms-", "smsto-", "mailto-", "matmsg-", "geo-"];
    let cases = load("structured.json");
    assert_eq!(cases.iter().map(|c| &c.id).collect::<std::collections::HashSet<_>>().len(), cases.len());
    for c in &cases {
        assert!(prefixes.iter().any(|p| c.id.starts_with(p)), "{}", c.id);
        let open = c.expect.signals == ["openWiFi"];
        assert_eq!(c.expect.level, if open { 1 } else { 0 }, "{}", c.id);
        if let Some(Payload::Wifi { info }) = payload_for(c) {
            assert_eq!(info.is_open(), open, "{}", c.id);
        }
    }
}

// MARK: StructuredHostileTests.swift

fn parse_any(t: &str) {
    wifi::parse(t);
    contact::parse(t);
    event::parse(t);
    phone::parse(t);
    sms::parse(t);
    email::parse(t);
    geo::parse(t);
}

#[test]
fn garbage_never_crashes() {
    let heads = ["WIFI:", "BEGIN:VCARD\n", "MECARD:", "BEGIN:VEVENT\n", "BEGIN:VCALENDAR\n", "tel:", "sms:", "smsto:", "mailto:", "MATMSG:", "geo:", ""];
    let tails = [
        "\\", ";;;;;", "=", "N;QUOTED-PRINTABLE:=\n=\n=", ":::", "\"\"\"", "%", "%zz%4", "\r", "\u{0}\u{FEFF}",
        "DTSTART:99999999999999999999T", "DURATION:P99999999999999999999D", "\u{301};\u{301}:",
    ];
    for h in heads {
        for t in tails {
            parse_any(&format!("{h}{t}"));
            parse_any(&format!("{h}{}", t.repeat(50)));
        }
    }
}

#[test]
fn huge_input_is_capped() {
    let big = format!("BEGIN:VCARD\nNOTE:{}\nEND:VCARD", "a".repeat(100_000));
    assert!(contact::parse(&big).and_then(|c| c.note).map_or(0, |n| n.len()) <= 8192);
    let tail = format!("WIFI:S:x;{}P:late;;", ";".repeat(20_000));
    assert_eq!(wifi::parse(&tail).unwrap().password, None);
    let card = format!("BEGIN:VCARD\n{}TEL:123\nEND:VCARD", "X:y\n".repeat(5_000));
    assert!(contact::parse(&card).unwrap().phones.is_empty());
}

#[test]
fn truncated_inputs_still_parse() {
    assert_eq!(contact::parse("BEGIN:VCARD\nFN:Ann").unwrap().name, s("Ann"));
    assert_eq!(event::parse("BEGIN:VEVENT\nSUMMARY:x").unwrap().summary, s("x"));
}

// MARK: StructuredRoundTripTests.swift

const AWKWARD: &[&str] = &[
    "plain", "semi;colon", "com,ma", "co:lon", r"back\slash", r#"quo"te"#, "multi\nline", "tab\there", "emoji 😀🎉", "日本語のテキスト",
    "Привет, мир", "ไทย;ภาษา", "%41 %zz", "a&b=c?d#e", "x\\;y\\,z\\\\",
];

#[test]
fn round_trip_wifi() {
    let w = |ssid: &str, sec: Option<&str>, pw: Option<&str>| WiFiInfo { ssid: ssid.into(), security: sec.map(String::from), password: pw.map(String::from), ..Default::default() };
    let mut cases: Vec<WiFiInfo> = AWKWARD.iter().filter(|x| !x.contains('\n') && !x.contains('\t')).map(|x| w(x, Some("WPA"), Some(x))).collect();
    cases.push(w("Open", Some("NOPASS"), None));
    cases.push(w("NoSecurity", None, None));
    cases.push(WiFiInfo { is_hidden: true, ..w("deadbeef", Some("WPA"), Some("0011aabb")) });
    cases.push(WiFiInfo { is_hidden: true, eap_method: s("PEAP"), identity: s("a;b"), ..w("Corp", Some("WPA2-EAP"), None) });
    cases.push(w(" spaced ", Some("SAE"), Some(" p ")));
    for c in cases {
        let text = encode(&MakeRequest::Wifi { info: c.clone() });
        assert!(text.starts_with("WIFI:"));
        assert_eq!(wifi::parse(&text), Some(c), "{text}");
    }
    assert_eq!(encode(&MakeRequest::Wifi { info: WiFiInfo { is_hidden: true, ..w("N", Some("WPA"), Some("p")) } }), "WIFI:T:WPA;S:N;P:p;H:true;;");
    assert_eq!(encode(&MakeRequest::Wifi { info: w("N", Some("NOPASS"), None) }), "WIFI:T:nopass;S:N;;");
}

#[test]
fn round_trip_contact() {
    let mut cases: Vec<ContactInfo> = AWKWARD
        .iter()
        .map(|x| ContactInfo {
            name: s(x),
            given_name: s(x),
            family_name: s(x),
            organization: s(x),
            title: s(x),
            phones: vec![x.to_string(), "+63 917 1".into()],
            emails: strs(&["a+b@x.org"]),
            urls: strs(&["https://example.com/a,b;c"]),
            addresses: vec![format!("{x}\nline two")],
            note: Some(format!("{x}\nsecond")),
            birthday: s("1990-01-02"),
            links: strs(&["https://example.com/a,b;c"]),
        })
        .collect();
    cases.push(ContactInfo { name: s("Only Name"), ..Default::default() });
    cases.push(ContactInfo { name: s("Given Family"), given_name: s("Given"), family_name: s("Family"), ..Default::default() });
    cases.push(ContactInfo { name: s("Long"), note: Some("日本語😀 long note, ".repeat(40) + "end"), ..Default::default() });
    for c in cases {
        let text = encode(&MakeRequest::Contact { info: c.clone() });
        assert!(text.starts_with("BEGIN:VCARD\r\nVERSION:3.0\r\n"));
        assert_eq!(contact::parse(&text), Some(c), "{text}");
    }
    let derived = ContactInfo { given_name: s("Ann"), family_name: s("Lee"), ..Default::default() };
    assert_eq!(contact::parse(&encode(&MakeRequest::Contact { info: derived })).unwrap().name, s("Ann Lee"));
}

#[test]
fn v_card_lines_are_folded_at75_octets() {
    let text = encode(&MakeRequest::Contact { info: ContactInfo { name: s("Fold"), note: Some("日本語😀é".repeat(80)), ..Default::default() } });
    let lines: Vec<&str> = text.split("\r\n").collect();
    assert!(lines.len() > 6);
    assert!(lines.iter().all(|l| l.len() <= 75));
    assert!(text.contains("\r\n "));
    assert!(!text.replace("\r\n", "").contains('\n'));
}

#[test]
fn round_trip_event() {
    let mut cases: Vec<EventInfo> = AWKWARD
        .iter()
        .map(|x| EventInfo {
            summary: s(x),
            start: Some(gmt(2026, 11, 10, 9, 0, 0)),
            end: Some(gmt(2026, 11, 10, 10, 30, 5)),
            location: s(x),
            description: Some(format!("{x}\nmore")),
            url: s("https://example.com/e?a=1;b=2"),
            links: strs(&["https://example.com/e?a=1;b=2"]),
            ..Default::default()
        })
        .collect();
    cases.push(EventInfo { summary: s("Floating"), start: Some(gmt(2026, 11, 10, 23, 59, 59)), is_floating: true, ..Default::default() });
    cases.push(EventInfo { summary: s("All day"), start: Some(gmt(2026, 12, 31, 0, 0, 0)), end: Some(gmt(2027, 1, 1, 0, 0, 0)), is_all_day: true, ..Default::default() });
    cases.push(EventInfo { summary: s("Old"), start: Some(gmt(1969, 7, 20, 20, 17, 0)), end: Some(gmt(1969, 7, 21, 0, 0, 0)), ..Default::default() });
    cases.push(EventInfo { summary: s("Leap"), start: Some(gmt(2028, 2, 29, 12, 0, 0)), ..Default::default() });
    cases.push(EventInfo::default());
    for c in cases {
        let text = encode(&MakeRequest::Event { info: c.clone() });
        assert!(text.starts_with("BEGIN:VEVENT\r\n"));
        assert_eq!(event::parse(&text), Some(c), "{text}");
    }
    assert!(encode(&MakeRequest::Event { info: EventInfo { start: Some(gmt(2026, 11, 10, 9, 0, 0)), ..Default::default() } }).contains("DTSTART:20261110T090000Z"));
    assert!(encode(&MakeRequest::Event { info: EventInfo { start: Some(gmt(2026, 11, 10, 0, 0, 0)), is_all_day: true, ..Default::default() } })
        .contains("DTSTART;VALUE=DATE:20261110"));
    assert!(encode(&MakeRequest::Event { info: EventInfo { start: Some(gmt(2026, 11, 10, 9, 0, 0)), is_floating: true, ..Default::default() } })
        .contains("DTSTART:20261110T090000\r\n"));
}

#[test]
fn round_trip_email() {
    let e = |a: &str, sub: Option<&str>, body: Option<String>| EmailInfo { address: a.into(), subject: sub.map(String::from), body, links: vec![] };
    let mut cases = vec![e("a@example.com", None, None), e("a+tag@example.com", Some("Hi"), None)];
    cases.extend(AWKWARD.iter().map(|x| e("x@example.com", Some(x), Some(format!("{x}\nbody")))));
    for c in cases {
        let text = encode(&MakeRequest::Email { info: c.clone() });
        assert!(text.starts_with("mailto:"));
        assert_eq!(email::parse(&text), Some(c), "{text}");
    }
    assert_eq!(encode(&MakeRequest::Email { info: e("a@b.c", Some("S x"), s("B")) }), "mailto:a@b.c?subject=S%20x&body=B");
}

#[test]
fn round_trip_sms() {
    let m = |n: &str, b: Option<String>| SmsInfo { number: n.into(), body: b, links: vec![] };
    let mut cases = vec![m("+639171234567", None), m("+63 917 123 4567", s("hello"))];
    cases.extend(AWKWARD.iter().map(|x| m("+1 (555) 010-0100", Some(format!("{x}\nnext: 5:30")))));
    for c in cases {
        let text = encode(&MakeRequest::Sms { info: c.clone() });
        assert_eq!(sms::parse(&text), Some(c), "{text}");
    }
}

#[test]
fn round_trip_phone() {
    for n in ["+639171234567", "+63 (917) 123-4567", "*123#", "0917;ext=5", "1,2", "٣٤٥", "a%b", "x?y"] {
        let text = encode(&MakeRequest::Phone { number: n.into() });
        assert!(text.starts_with("TEL:"));
        assert_eq!(phone::parse(&text), Some(PhoneInfo { number: n.into() }), "{text}");
    }
}

#[test]
fn round_trip_geo() {
    let g = |la: &str, lo: &str, al: Option<&str>, q: Option<&str>| GeoInfo { latitude: la.into(), longitude: lo.into(), altitude: al.map(String::from), query: q.map(String::from) };
    for c in [
        g("14.5995", "120.9842", None, None),
        g("-33.86", "151.21", Some("30"), None),
        g("0", "0", None, Some("Rizal Park")),
        g("1.5", "2.5", Some("3"), Some("Café & Bar, 日本 😀 (a=b?c)")),
    ] {
        let text = encode(&MakeRequest::Geo { info: c.clone() });
        assert_eq!(geo::parse(&text), Some(c), "{text}");
    }
}

#[test]
fn link_and_text() {
    assert_eq!(encode(&MakeRequest::Link { text: "  https://example.com/a b \n".into() }), "https://example.com/a b");
    assert_eq!(encode(&MakeRequest::Text { text: " keep \n me ".into() }), " keep \n me ");
}

// MARK: StructuredURITests.swift

fn sm(n: &str, b: Option<&str>) -> Option<SmsInfo> {
    Some(SmsInfo { number: n.into(), body: b.map(String::from), links: vec![] })
}

#[test]
fn uri_phone() {
    assert_eq!(phone::parse("tel:+63 917 1"), Some(PhoneInfo { number: "+63 917 1".into() }));
    assert_eq!(phone::parse("TEL:%2B1%20555 "), Some(PhoneInfo { number: "+1 555".into() }));
    assert!(phone::parse("tel:").is_none());
    assert!(phone::parse("sms:1").is_none());
}

#[test]
fn uri_sms() {
    assert_eq!(sms::parse("sms:+1?body=a%26b"), sm("+1", Some("a&b")));
    assert_eq!(sms::parse("sms:+1&body=x"), sm("+1", Some("x")));
    assert_eq!(sms::parse("SMS:+1"), sm("+1", None));
    assert_eq!(sms::parse("smsto:+1:hi: there %41"), sm("+1", Some("hi: there %41")));
    assert_eq!(sms::parse("SMSTO:+1:"), sm("+1", None));
    assert!(sms::parse("sms:?body=x").is_none());
    assert!(sms::parse("smsto:").is_none());
}

#[test]
fn uri_mailto() {
    let e = email::parse("mailto:a@b.c,d@e.f?subject=S%20x&Body=B%26C&cc=z@z.z");
    assert_eq!(e, Some(EmailInfo { address: "a@b.c".into(), subject: s("S x"), body: s("B&C"), links: vec![] }));
    assert_eq!(email::parse("mailto:?to=q@r.s").unwrap().address, "q@r.s");
    assert!(email::parse("mailto:").is_none());
    assert_eq!(email::parse("mailto:%61@b.c").unwrap().address, "a@b.c");
}

#[test]
fn uri_matmsg() {
    assert_eq!(
        email::parse(r"matmsg:TO:a@b.c;SUB:x\;y;BODY:l1 \\ l2;;"),
        Some(EmailInfo { address: "a@b.c".into(), subject: s("x;y"), body: s(r"l1 \ l2"), links: vec![] })
    );
    assert!(email::parse("MATMSG:SUB:x;;").is_none());
}

#[test]
fn uri_geo() {
    let g = |la: &str, lo: &str, al: Option<&str>| Some(GeoInfo { latitude: la.into(), longitude: lo.into(), altitude: al.map(String::from), query: None });
    assert_eq!(geo::parse("geo:14.5,120.9"), g("14.5", "120.9", None));
    assert_eq!(geo::parse("geo:-1,2,3;u=35"), g("-1", "2", Some("3")));
    assert_eq!(geo::parse("geo:0,0?q=a%20b&z=3").unwrap().query, s("a b"));
    for t in ["geo:abc,1", "geo:1", "geo:91,0", "geo:1,181", "geo:1e2,3", "geo:1,2,3,4", "geo:"] {
        assert!(geo::parse(t).is_none(), "{t}");
    }
}

// MARK: StructuredWiFiTests.swift

fn wi(ssid: &str, sec: Option<&str>, pw: Option<&str>, hidden: bool) -> Option<WiFiInfo> {
    Some(WiFiInfo { ssid: ssid.into(), security: sec.map(String::from), password: pw.map(String::from), is_hidden: hidden, ..Default::default() })
}

#[test]
fn basic_and_any_order() {
    assert_eq!(wifi::parse("WIFI:T:WPA;S:Net;P:pw;;"), wi("Net", Some("WPA"), Some("pw"), false));
    assert_eq!(wifi::parse("WIFI:P:pw;H:true;S:Net;T:WEP;;"), wi("Net", Some("WEP"), Some("pw"), true));
    assert_eq!(wifi::parse("  wifi:S:x;;").unwrap().ssid, "x");
}

#[test]
fn escapes_and_quotes() {
    assert_eq!(wifi::parse(r#"WIFI:S:a\;b\,c\:d\\e\"f;P:\;;;"#), wi(r#"a;b,c:d\e"f"#, None, Some(";"), false));
    assert_eq!(wifi::parse(r#"WIFI:S:"abcd";P:"0011";;"#), wi("abcd", None, Some("0011"), false));
    assert_eq!(wifi::parse("WIFI:S:has:colon;;").unwrap().ssid, "has:colon");
}

#[test]
fn security_and_open() {
    assert_eq!(wifi::parse("WIFI:T:nopass;S:x;;").unwrap().security, s("NOPASS"));
    assert!(wifi::parse("WIFI:T:nopass;S:x;;").unwrap().is_open());
    assert_eq!(wifi::parse("WIFI:T:;S:x;;").unwrap().security, None);
    assert!(wifi::parse("WIFI:S:x;;").unwrap().is_open());
    assert!(!wifi::parse("WIFI:T:WPA;S:x;P:y;;").unwrap().is_open());
}

#[test]
fn enterprise() {
    let w = wifi::parse("WIFI:T:WPA2-EAP;S:Corp;E:TTLS;I:ann;;").unwrap();
    assert_eq!(w.eap_method, s("TTLS"));
    assert_eq!(w.identity, s("ann"));
    assert!(w.is_enterprise());
}

#[test]
fn rejects() {
    for t in ["WIFI:T:WPA;P:x;;", "WIFI:S:;;", "WIFI:", "hello WIFI:S:x;;", ""] {
        assert!(wifi::parse(t).is_none(), "{t}");
    }
}
