//! Swift parity: HistoryTests and KnownAddressesTests.

mod common;
use common::*;
use qr_core::*;
use std::str::FromStr;

fn item(
    payload: &str,
    kind: HistoryKind,
    ty: &str,
    level: Level,
    signals: &[&str],
    t: f64,
    note: Option<&str>,
    enc: PayloadEncoding,
) -> HistoryItem {
    HistoryItem {
        id: Uuid::new_v4(),
        kind,
        payload: payload.into(),
        payload_encoding: enc,
        symbology: Symbology::Qr,
        type_id: ty.into(),
        level,
        signal_ids: signals.iter().map(|s| s.to_string()).collect(),
        date: at(t),
        note: note.map(String::from),
    }
}

fn simple(payload: &str, t: f64, note: Option<&str>) -> HistoryItem {
    item(
        payload,
        HistoryKind::Scanned,
        "link",
        Level::Normal,
        &[],
        t,
        note,
        PayloadEncoding::Text,
    )
}

fn typed(payload: &str, ty: &str, t: f64, note: Option<&str>) -> HistoryItem {
    item(
        payload,
        HistoryKind::Scanned,
        ty,
        Level::Normal,
        &[],
        t,
        note,
        PayloadEncoding::Text,
    )
}

fn decode(s: &str) -> HistoryDecoded {
    decode_history(s.as_bytes())
}

// MARK: HistoryFileTests

#[test]
fn round_trip() {
    let items = vec![
        simple("https://example.com", 1_700_000_000.0, Some("n")),
        item(
            "héllo 🙂",
            HistoryKind::Made,
            "text",
            Level::Careful,
            &["ipHost"],
            1_700_000_100.0,
            None,
            PayloadEncoding::Text,
        ),
        item(
            "AAEC",
            HistoryKind::Scanned,
            "link",
            Level::Normal,
            &[],
            1_700_000_200.0,
            None,
            PayloadEncoding::Base64,
        ),
    ];
    let data = encode_history(&items);
    assert_eq!(
        decode_history(&data),
        HistoryDecoded::Items {
            items: items.clone()
        }
    );
    let json = String::from_utf8(data.clone()).unwrap();
    assert!(json.contains("\"schema\":1"));
    assert!(json.contains("\"level\":0"));
    assert!(!json.contains('\n'));
    assert_eq!(encode_history(&items), data);
}

#[test]
fn empty_round_trip() {
    assert_eq!(
        decode_history(&encode_history(&[])),
        HistoryDecoded::Items { items: vec![] }
    );
}

#[test]
fn unknown_schema_is_not_decoded_further() {
    assert_eq!(
        decode(r#"{"schema":2,"items":"whatever shape"}"#),
        HistoryDecoded::UnknownSchema { schema: 2 }
    );
    assert_eq!(
        decode(r#"{"schema":0,"items":[]}"#),
        HistoryDecoded::UnknownSchema { schema: 0 }
    );
}

#[test]
fn garbage_is_unreadable() {
    for t in [
        "",
        "not json",
        "[]",
        "42",
        "null",
        "{}",
        r#"{"schema":"1","items":[]}"#,
        r#"{"schema":1}"#,
        r#"{"schema":1,"items":{}}"#,
        r#"{"schema":1.5,"items":[]}"#,
    ] {
        assert_eq!(decode(t), HistoryDecoded::Unreadable, "{t}");
    }
}

#[test]
fn one_corrupt_item_keeps_the_rest() {
    let (g1, g2) = (
        simple("one", 1_700_000_000.0, None),
        simple("two", 1_700_000_500.0, None),
    );
    let file = String::from_utf8(encode_history(&[g1.clone(), g2.clone()])).unwrap();
    let file = file.replacen(
        "\"items\":[",
        r#""items":[{"id":"nope","kind":"scanned"},7,null,{"kind":"weird"},"#,
        1,
    );
    assert_eq!(
        decode(&file),
        HistoryDecoded::Items {
            items: vec![g1, g2]
        }
    );
}

#[test]
fn fractional_second_dates_decode() {
    let json = r#"{"schema":1,"items":[{"id":"3F2504E0-4F89-11D3-9A0C-0305E82C3301","kind":"made","payload":"x",
"payloadEncoding":"text","symbology":"qr","typeID":"text","level":1,"signalIDs":[],
"date":"2023-11-14T22:13:20.250Z"}]}"#;
    let HistoryDecoded::Items { items } = decode(json) else {
        panic!("did not decode")
    };
    assert_eq!(items.len(), 1);
    assert!((secs(items[0].date) - 1_700_000_000.25).abs() < 0.001);
    assert_eq!(items[0].note, None);
}

#[test]
fn unknown_signal_ids_survive() {
    let items = vec![item(
        "https://example.com",
        HistoryKind::Scanned,
        "link",
        Level::Normal,
        &["someFutureSignal"],
        1_700_000_000.0,
        None,
        PayloadEncoding::Text,
    )];
    assert_eq!(
        decode_history(&encode_history(&items)),
        HistoryDecoded::Items { items }
    );
}

// MARK: HistorySearchTests

fn abc() -> (HistoryItem, HistoryItem, HistoryItem) {
    (
        typed("https://Example.com/Pay", "link", 100.0, Some("Lunch")),
        typed("WIFI:S:Café;;", "wifi", 300.0, None),
        typed("hello", "text", 200.0, Some("see Café menu")),
    )
}

#[test]
fn empty_query_returns_all_newest_first() {
    let (a, b, c) = abc();
    let all = [a.clone(), b.clone(), c.clone()];
    assert_eq!(
        search(&all, "")
            .iter()
            .map(|i| i.payload.clone())
            .collect::<Vec<_>>(),
        vec![b.payload, c.payload, a.payload]
    );
    assert_eq!(search(&all, "  \n ").len(), 3);
}

#[test]
fn case_insensitive_over_payload_note_and_type() {
    let (a, b, c) = abc();
    let all = [a.clone(), b.clone(), c];
    assert_eq!(search(&all, "EXAMPLE"), vec![a.clone()]);
    assert_eq!(search(&all, "lunch"), vec![a]);
    assert_eq!(search(&all, "WiFi"), vec![b]);
    assert!(search(&all, "nothing").is_empty());
}

#[test]
fn diacritics_and_order() {
    let (a, b, c) = abc();
    let all = [a, b.clone(), c.clone()];
    assert_eq!(search(&all, "cafe"), vec![b.clone(), c.clone()]);
    assert_eq!(search(&all, " café "), vec![b, c]);
}

#[test]
fn equal_dates_keep_input_order() {
    let (x, y) = (simple("x1", 5.0, None), simple("x2", 5.0, None));
    assert_eq!(search(&[x.clone(), y.clone()], "x"), vec![x, y]);
}

// MARK: HistoryExportTests

#[test]
fn json_is_the_file_shape() {
    let items = vec![simple("https://example.com", 1_700_000_000.0, None)];
    assert_eq!(export_json(&items, None), encode_history(&items));
}

#[test]
fn csv_header_rows_and_crlf() {
    let i = HistoryItem {
        id: Uuid::from_str("3F2504E0-4F89-11D3-9A0C-0305E82C3301").unwrap(),
        kind: HistoryKind::Scanned,
        payload: "https://x.test".into(),
        payload_encoding: PayloadEncoding::Text,
        symbology: Symbology::Qr,
        type_id: "link".into(),
        level: Level::LikelyScam,
        signal_ids: vec!["ipHost".into(), "plainHTTP".into()],
        date: at(1_700_000_000.0),
        note: None,
    };
    assert_eq!(
        export_csv(&[i]),
        "date,kind,type,level,payload,note,signals,symbology,id\r\n2023-11-14T22:13:20Z,scanned,link,likelyScam,https://x.test,,ipHost plainHTTP,qr,3F2504E0-4F89-11D3-9A0C-0305E82C3301\r\n"
    );
    assert_eq!(
        export_csv(&[]),
        "date,kind,type,level,payload,note,signals,symbology,id\r\n"
    );
}

#[test]
fn csv_quoting() {
    let csv = export_csv(&[simple("a,b \"q\"\r\nline2", 1_700_000_000.0, Some("x\ny"))]);
    assert!(csv.contains("\"a,b \"\"q\"\"\r\nline2\""));
    assert!(csv.contains("\"x\ny\""));
}

#[test]
fn csv_formula_guard() {
    for payload in ["=1+1", "+1", "-2", "@SUM(A1)"] {
        let csv = export_csv(&[simple(payload, 1_700_000_000.0, Some(payload))]);
        assert!(
            csv.contains(&format!(",'{payload},'{payload},")),
            "{payload}"
        );
    }
}

#[test]
fn csv_formula_guard_then_quoting() {
    assert!(export_csv(&[simple("=a,b", 1_700_000_000.0, None)]).contains("\"'=a,b\""));
}

#[test]
fn csv_unicode_and_base64_stay_as_stored() {
    let csv = export_csv(&[
        simple("日本語 🙂 é", 1_700_000_000.0, None),
        item(
            "AAEC/w==",
            HistoryKind::Scanned,
            "link",
            Level::Normal,
            &[],
            1_700_000_000.0,
            None,
            PayloadEncoding::Base64,
        ),
    ]);
    assert!(csv.contains(",日本語 🙂 é,"));
    assert!(csv.contains(",AAEC/w==,"));
}

// MARK: HistoryItemTests

#[test]
fn init_from_analysis_text() {
    use SignalId::*;
    let embedded = Analysis {
        code: ScannedCode::text("https://b.test", Symbology::Qr),
        payload: Payload::Text {
            info: TextInfo {
                text: "b".into(),
                is_truncated: false,
                links: vec![],
            },
        },
        signals: [PlainHttp, IpHost, AssumedHttps]
            .into_iter()
            .map(Signal::new)
            .collect(),
        embedded: vec![],
        level: Level::Careful,
    };
    let top = Analysis {
        code: ScannedCode::text("see https://b.test", Symbology::Aztec),
        payload: Payload::Text {
            info: TextInfo {
                text: "see".into(),
                is_truncated: false,
                links: vec![],
            },
        },
        signals: [OpenWiFi, PeekIncomplete, PlainHttp]
            .into_iter()
            .map(Signal::new)
            .collect(),
        embedded: vec![embedded],
        level: Level::Careful,
    };
    let date = at(1_700_000_000.0);
    let h = HistoryItem::from_analysis(&top, HistoryKind::Made, date);
    assert_eq!(h.payload, "see https://b.test");
    assert_eq!(h.payload_encoding, PayloadEncoding::Text);
    assert_eq!(h.symbology, Symbology::Aztec);
    assert_eq!(h.type_id, "text");
    assert_eq!(h.level, Level::Careful);
    assert_eq!(h.kind, HistoryKind::Made);
    assert_eq!(h.date, date);
    assert_eq!(h.note, None);
    assert_eq!(h.signal_ids, vec!["openWiFi", "plainHTTP", "ipHost"]);
    assert_eq!(h.scanned_code(), top.code);
    assert_ne!(
        HistoryItem::from_analysis(&top, HistoryKind::Made, date).id,
        h.id
    );
}

#[test]
fn init_from_analysis_binary() {
    let bytes = vec![0xFF, 0x00, 0xFE, 0x80];
    let a = Analysis {
        code: ScannedCode {
            text: None,
            bytes: bytes.clone(),
            symbology: Symbology::Pdf417,
        },
        payload: Payload::Binary { byte_count: 4 },
        signals: vec![],
        embedded: vec![],
        level: Level::Normal,
    };
    let h = HistoryItem::from_analysis(&a, HistoryKind::Scanned, at(0.0));
    assert_eq!(h.payload_encoding, PayloadEncoding::Base64);
    assert_eq!(h.payload, "/wD+gA==");
    assert_eq!(h.type_id, "binary");
    assert_eq!(
        h.scanned_code(),
        ScannedCode {
            text: None,
            bytes,
            symbology: Symbology::Pdf417
        }
    );
}

#[test]
fn scanned_code_from_base64() {
    let mut h = item(
        "aGkg8J+Zgg==",
        HistoryKind::Scanned,
        "link",
        Level::Normal,
        &[],
        0.0,
        None,
        PayloadEncoding::Base64,
    );
    assert_eq!(h.scanned_code().text.as_deref(), Some("hi 🙂"));
    assert_eq!(h.scanned_code().bytes, "hi 🙂".as_bytes());
    h.payload = "!!!not base64!!!".into();
    assert!(h.scanned_code().bytes.is_empty());
    assert_eq!(h.scanned_code().text, None);
}

// MARK: KnownAddressesTests.swift

fn known(domains: &[&str]) -> KnownAddresses {
    let mut k = KnownAddresses::default();
    for (i, d) in domains.iter().enumerate() {
        k.add(d, at(i as f64));
    }
    k
}

#[test]
fn add_contains_remove_are_case_and_dot_insensitive() {
    let mut k = KnownAddresses::default();
    k.add("Example.COM.", at(1.0));
    assert!(k.contains("example.com"));
    assert_eq!(
        k.entries
            .iter()
            .map(|e| e.domain.as_str())
            .collect::<Vec<_>>(),
        vec!["example.com"]
    );
    k.add("other.org", at(2.0));
    k.add("example.com", at(3.0));
    assert_eq!(
        k.entries
            .iter()
            .map(|e| e.domain.as_str())
            .collect::<Vec<_>>(),
        vec!["example.com", "other.org"]
    );
    k.remove("EXAMPLE.com");
    assert!(!k.contains("example.com") && k.contains("other.org"));
    k.add("  ", at(4.0));
    assert_eq!(k.entries.len(), 1);
    k.entries.clear();
    assert!(k.is_empty() && !k.contains(""));
}

#[test]
fn unicode_domains_compare_in_unicode_form() {
    assert!(known(&["bücher.example"]).contains("BÜCHER.example"));
}

#[test]
fn size_is_bounded() {
    let mut k = KnownAddresses::default();
    for i in 0..KNOWN_MAX_ENTRIES + 50 {
        k.add(&format!("d{i}.example"), at(i as f64));
    }
    assert_eq!(k.entries.len(), KNOWN_MAX_ENTRIES);
    assert!(k.contains(&format!("d{}.example", KNOWN_MAX_ENTRIES + 49)));
    assert!(!k.contains("d0.example"));
}

#[test]
fn offer_for_a_list_only_result() {
    let a = analyze("https://bit.ly/abc");
    assert!(a.signals.iter().any(|s| s.id == SignalId::Shortener));
    assert_eq!(
        KnownAddresses::default().offer(&a).as_deref(),
        Some("bit.ly")
    );
    assert_eq!(known(&["bit.ly"]).offer(&a), None);
    let again = Analyzer::with_known(shared(), known(&["bit.ly"]))
        .analyze(ScannedCode::text("https://bit.ly/abc", Symbology::Qr));
    assert_eq!(known(&["bit.ly"]).offer(&again), None);
}

#[test]
fn no_offer_with_a_strong_non_list_signal_or_clean_or_ip_or_other_payload() {
    for t in [
        "https://paypal.com@bit.ly/x",
        "javascript:alert(1)",
        "https://www.google.com/",
        "http://192.168.1.1/",
        "hello there",
        "WIFI:T:nopass;S:Cafe;;",
    ] {
        assert_eq!(KnownAddresses::default().offer(&analyze(t)), None, "{t}");
    }
}

#[test]
fn no_offer_when_another_warning_is_present() {
    let a = analyze("http://bit.ly/abc");
    assert!(a.signals.iter().any(|s| s.id == SignalId::PlainHttp));
    assert_eq!(KnownAddresses::default().offer(&a), None);
}

#[test]
fn apply_drops_list_signals_and_keeps_the_rest() {
    use SignalId::*;
    let payload = analyze("http://bit.ly/abc").payload;
    let signals: Vec<Signal> = [Shortener, PlainHttp, UnknownEnding]
        .into_iter()
        .map(Signal::new)
        .collect();
    assert_eq!(
        ids(&known(&["bit.ly"]).apply(&signals, &payload)),
        vec![PlainHttp, UnknownEnding, KnownByYou]
    );
    assert_eq!(KnownAddresses::default().apply(&signals, &payload), signals);
    assert_eq!(known(&["other.org"]).apply(&signals, &payload), signals);
}

#[test]
fn apply_leaves_other_payloads_alone() {
    let text = Payload::Text {
        info: TextInfo {
            text: "bit.ly".into(),
            is_truncated: false,
            links: vec![],
        },
    };
    let signals = vec![Signal::new(SignalId::Shortener)];
    assert_eq!(known(&["bit.ly"]).apply(&signals, &text), signals);
}

#[test]
fn file_round_trip_and_hostile_input() {
    let k = known(&["a.example", "b.example"]);
    assert_eq!(
        decode_known_file(&encode_known_file(&k)),
        KnownAddressesDecoded::Addresses { known: k }
    );
    assert_eq!(
        decode_known_file(br#"{"schema":2,"entries":1}"#),
        KnownAddressesDecoded::UnknownSchema { schema: 2 }
    );
    for t in [
        "",
        "nope",
        "[]",
        "{}",
        r#"{"schema":1}"#,
        r#"{"schema":1,"entries":{}}"#,
    ] {
        assert_eq!(
            decode_known_file(t.as_bytes()),
            KnownAddressesDecoded::Unreadable,
            "{t}"
        );
    }
    let lossy = br#"{"schema":1,"entries":[{"domain":"ok.example","added":"2024-01-01T00:00:00Z"},{"domain":5}]}"#;
    let KnownAddressesDecoded::Addresses { known: got } = decode_known_file(lossy) else {
        panic!("no addresses")
    };
    assert_eq!(
        got.entries
            .iter()
            .map(|e| e.domain.as_str())
            .collect::<Vec<_>>(),
        vec!["ok.example"]
    );
}

#[test]
fn history_export_carries_known_addresses_and_stays_readable() {
    let k = known(&["a.example"]);
    let i = simple("https://x.example", 1_700_000_000.0, None);
    let data = export_json(std::slice::from_ref(&i), Some(&k));
    assert_eq!(
        decode_history(&data),
        HistoryDecoded::Items {
            items: vec![i.clone()]
        }
    );
    assert_eq!(export_known_addresses(&data), k);
    assert_eq!(
        export_json(std::slice::from_ref(&i), None),
        encode_history(std::slice::from_ref(&i))
    );
    assert!(export_known_addresses(&encode_history(&[i])).is_empty());
    assert!(export_known_addresses(b"junk").is_empty());
}
