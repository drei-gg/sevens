//! Swift parity: FixtureRunnerTests, AnalyzerTests, ContractTests and BundledDataTests from
//! `Kit/Tests/QRCoreTests`, one to one under the Swift names.

mod common;
use common::*;
use qr_core::*;
use std::collections::{HashMap, HashSet};
use std::time::Instant;

const KNOWN_TYPES: &[&str] = &[
    "link", "appLink", "payment", "crypto", "wifi", "contact", "event", "phone", "sms", "email",
    "geo", "text", "binary",
];

// MARK: FixtureRunnerTests.swift

#[test]
fn every_fixture_file_is_well_formed() {
    let mut ids = HashSet::new();
    for name in file_names() {
        for c in load(&name) {
            assert!(
                ids.insert(c.id.clone()),
                "duplicate fixture id {} in {name}",
                c.id
            );
            assert!(
                c.input.is_none() != c.input_base64.is_none(),
                "{}: exactly one of input/inputBase64",
                c.id
            );
            assert!(
                KNOWN_TYPES.contains(&c.expect.kind.as_str()),
                "{}: unknown type",
                c.id
            );
            assert!((0..=2).contains(&c.expect.level), "{}", c.id);
            for s in c.expect.info.iter().flatten() {
                assert_eq!(
                    SignalId::from_raw(s).map(|i| i.severity()),
                    Some(Severity::Info),
                    "{}: {s}",
                    c.id
                );
            }
            for s in &c.expect.signals {
                let id = SignalId::from_raw(s);
                assert!(id.is_some(), "{}: unknown signal {s}", c.id);
                assert_ne!(
                    id.unwrap().severity(),
                    Severity::Info,
                    "{}: info signal {s} listed",
                    c.id
                );
            }
            if let Some(sym) = &c.symbology {
                assert!(
                    Symbology::from_raw(sym).is_some(),
                    "{}: unknown symbology {sym}",
                    c.id
                );
            }
        }
    }
}

#[test]
fn every_fixture_passes() {
    let mut problems = Vec::new();
    for c in all() {
        let a = match &c.known {
            None => analyzer().analyze(c.scanned_code()),
            Some(_) => {
                Analyzer::with_known(shared(), c.known_addresses()).analyze(c.scanned_code())
            }
        };
        let p = mismatches(&c, &a);
        if !p.is_empty() {
            problems.push(format!("{}: {}", c.id, p.join("; ")));
        }
    }
    assert!(
        problems.is_empty(),
        "{} failing:\n{}",
        problems.len(),
        problems.join("\n")
    );
}

#[test]
fn every_signal_has_three_fixtures() {
    let mut counts: HashMap<String, usize> = HashMap::new();
    for c in all() {
        let set: HashSet<String> = c
            .expect
            .signals
            .iter()
            .chain(c.expect.info.iter().flatten())
            .cloned()
            .collect();
        for s in set {
            *counts.entry(s).or_default() += 1;
        }
    }
    for sub in ["peek", "peekreport"] {
        for e in std::fs::read_dir(fixtures_dir().join(sub)).unwrap() {
            let path = e.unwrap().path();
            if path.extension().and_then(|x| x.to_str()) != Some("json") {
                continue;
            }
            let Ok(v) = serde_json::from_slice::<serde_json::Value>(&std::fs::read(&path).unwrap())
            else {
                continue;
            };
            let mut set = HashSet::new();
            for key in ["signals", "info"] {
                for s in v["expect"][key].as_array().into_iter().flatten() {
                    set.insert(s.as_str().unwrap_or("").to_string());
                }
            }
            for s in set {
                *counts.entry(s).or_default() += 1;
            }
        }
    }
    for id in SignalId::ALL {
        assert!(
            counts.get(id.raw()).copied().unwrap_or(0) >= 3,
            "G4: {}",
            id.raw()
        );
    }
}

// MARK: AnalyzerTests.swift

#[test]
fn analyzer_fixture_file_passes() {
    for file in ["links.json", "classify.json", "false-positives.json"] {
        let cases = load(file);
        assert!(!cases.is_empty());
        for c in cases {
            let p = mismatches(&c, &analyzer().analyze(c.scanned_code()));
            assert!(p.is_empty(), "{}: {}", c.id, p.join("; "));
        }
    }
}

#[test]
fn analyzer_false_positive_set_has_no_strong_signal() {
    let cases = load("false-positives.json");
    assert!(cases.len() >= 100);
    let (mut careful, mut other_ending) = (0, 0);
    for c in &cases {
        let a = analyzer().analyze(c.scanned_code());
        assert_eq!(a.payload.type_id(), "link", "{}", c.id);
        assert!(a.level <= Level::Careful, "{}", c.id);
        assert!(
            !a.all_signals()
                .iter()
                .any(|s| s.severity() == Severity::Strong),
            "{}",
            c.id
        );
        if a.signals.iter().any(|s| s.id == SignalId::BrandOtherEnding) {
            other_ending += 1;
        } else if a.level == Level::Careful {
            careful += 1;
        }
    }
    assert!(careful <= 7);
    assert!(other_ending <= 12);
}

#[test]
fn analyzer_every_offline_signal_has_firing_and_non_firing_fixtures() {
    use SignalId::*;
    let cases = load("links.json");
    for id in [
        DangerousScheme,
        UserinfoTrick,
        MixedScript,
        LookalikeBrand,
        BrandInDomain,
        NearBrand,
        IpHost,
        Shortener,
        PlainHttp,
        RiskyTld,
        DeepSubdomains,
        ObfuscatedPath,
        UnknownScheme,
        AssumedHttps,
    ] {
        let fires = cases
            .iter()
            .filter(|c| {
                c.expect.signals.iter().any(|s| s == id.raw())
                    || c.expect.info.iter().flatten().any(|s| s == id.raw())
            })
            .count();
        assert!(fires >= 3, "{id:?} has {fires}");
    }
    assert!(
        cases
            .iter()
            .filter(|c| c.id.starts_with("link-near-"))
            .count()
            >= 30
    );
}

#[test]
fn analyzer_fixture_counts_report() {
    let total: usize = ["links.json", "classify.json", "false-positives.json"]
        .iter()
        .map(|f| load(f).len())
        .sum();
    assert!(total >= 300);
}

fn run(s: &str, sym: Symbology) -> Analysis {
    analyzer().analyze(ScannedCode::text(s, sym))
}

#[test]
fn analyzer_level_rule() {
    let s = Signal::new;
    assert_eq!(level_for(&[]), Level::Normal);
    assert_eq!(level_for(&[s(SignalId::AssumedHttps)]), Level::Normal);
    assert_eq!(level_for(&[s(SignalId::PlainHttp)]), Level::Careful);
    assert_eq!(
        level_for(&[s(SignalId::PlainHttp), s(SignalId::IpHost)]),
        Level::Careful
    );
    assert_eq!(
        level_for(&[
            s(SignalId::PlainHttp),
            s(SignalId::IpHost),
            s(SignalId::Shortener)
        ]),
        Level::LikelyScam
    );
    assert_eq!(level_for(&[s(SignalId::UserinfoTrick)]), Level::LikelyScam);
}

#[test]
fn analyzer_three_warnings_make_likely_scam() {
    assert_eq!(
        run("http://203.0.113.9/", Symbology::Qr).level,
        Level::Careful
    );
    let b = run("http://bit.ly.deep.a.b.c.example.top/", Symbology::Qr);
    let i = ids(&b.signals);
    assert!(
        i.contains(&SignalId::DeepSubdomains)
            && i.contains(&SignalId::RiskyTld)
            && i.contains(&SignalId::PlainHttp)
    );
    assert_eq!(b.level, Level::LikelyScam);
}

#[test]
fn analyzer_embedded_links_fill_info_links_and_level() {
    let a = run(
        "pay https://paypa1.com/x or https://example.com/ok",
        Symbology::Qr,
    );
    let Payload::Text { info } = &a.payload else {
        panic!("not text")
    };
    assert_eq!(
        info.links,
        vec!["https://paypa1.com/x", "https://example.com/ok"]
    );
    assert_eq!(a.embedded.len(), 2);
    assert!(a.signals.is_empty());
    assert_eq!(a.level, Level::LikelyScam);
    assert!(a.embedded[0].level == Level::LikelyScam && a.embedded[1].level == Level::Normal);
    assert_eq!(a.embedded[0].code.symbology, Symbology::Qr);
}

#[test]
fn analyzer_embedded_keeps_symbology_and_never_recurses() {
    let a = run("see www.example.com", Symbology::Pdf417);
    assert_eq!(
        a.embedded.first().map(|e| e.code.symbology),
        Some(Symbology::Pdf417)
    );
    assert!(a.embedded.iter().all(|e| e.embedded.is_empty()));
    let b = run(
        &format!(
            "https://a.example/ https://b.example/ {}",
            "http://c.example/ ".repeat(5)
        ),
        Symbology::Qr,
    );
    assert!(b.embedded.len() <= 10);
}

#[test]
fn analyzer_checks_every_embedded_link_in_the_window() {
    // The verdict sees the 25th link too (SPEC §4.2: links found inside get checked).
    let mut links: Vec<String> = (0..24)
        .map(|i| format!("https://h{i}.example.com/"))
        .collect();
    links.push("javascript:alert(1)".into());
    let text = format!("links: {}", links.join(" "));
    let a = run(&text, Symbology::Qr);
    assert_eq!(a.embedded.len(), 25);
    let Payload::Text { info } = &a.payload else {
        panic!()
    };
    assert_eq!(info.links.len(), 25);
    assert_eq!(a.level, Level::LikelyScam);
    assert!(ids(&a.embedded[24].signals).contains(&SignalId::DangerousScheme));
}

#[test]
fn analyzer_links_past_eight_kilobytes_are_not_searched() {
    let text = format!("{} https://paypa1.com/x", "word ".repeat(1700));
    assert!(text.len() < 9000 && text.len() > 8192);
    let a = run(&text, Symbology::Qr);
    assert!(a.embedded.is_empty());
    assert_eq!(a.level, Level::Normal);
}

#[test]
fn analyzer_many_links_stay_fast() {
    let text = "www.a.com ".repeat(819);
    let start = std::time::Instant::now();
    let a = run(&text, Symbology::Qr);
    assert_eq!(a.embedded.len(), 1); // deduplicated
    let distinct: String = (0..800).map(|i| format!("www.a{i}.com ")).collect();
    let b = run(&distinct[..8000], Symbology::Qr);
    assert!(b.embedded.len() > 600);
    assert!(start.elapsed().as_secs_f64() < 5.0, "{:?}", start.elapsed());
}

#[test]
fn analyzer_dangerous_schemes_inside_text_reach_the_verdict() {
    for (text, scheme) in [
        ("Tap javascript:fetch('//x.example') now", "javascript"),
        ("img data:text/html,<script>", "data"),
        ("open FILE:///etc/passwd", "file"),
        ("x data:;base64,AAAA", "data"),
    ] {
        let a = run(text, Symbology::Qr);
        assert_eq!(a.level, Level::LikelyScam, "{text}");
        let e = &a.embedded[0];
        assert_eq!(arg(e.signals.first(), "scheme"), Some(scheme), "{text}");
    }
    // Prose that only looks like it.
    for text in [
        "Mobile data: 5 GB",
        "the metadata:x/y field",
        "profile file: notes",
        "see file:notes.txt",
        "Read JavaScript: the good parts",
        "used data:5GB",
    ] {
        let a = run(text, Symbology::Qr);
        assert!(a.embedded.is_empty(), "{text}: {:?}", a.embedded);
        assert_eq!(a.level, Level::Normal, "{text}");
    }
}

#[test]
fn analyzer_contact_and_event_links_listed() {
    let c = run(
        "BEGIN:VCARD\nVERSION:3.0\nFN:A\nURL:https://example.com\nNOTE:also www.example.org\nEND:VCARD",
        Symbology::Qr,
    );
    let Payload::Contact { info } = &c.payload else {
        panic!("not contact")
    };
    assert_eq!(info.links, vec!["https://example.com", "www.example.org"]);
    assert_eq!(c.embedded.len(), 2);
    let e = run(
        "BEGIN:VEVENT\nSUMMARY:x\nDTSTART:20261110T090000Z\nURL:https://example.com\nDESCRIPTION:http://e.example/1\nLOCATION:https://e.example/2\nEND:VEVENT",
        Symbology::Qr,
    );
    let Payload::Event { info } = &e.payload else {
        panic!("not event")
    };
    assert_eq!(
        info.links,
        vec![
            "https://example.com",
            "http://e.example/1",
            "https://e.example/2"
        ]
    );
}

#[test]
fn analyzer_eight_kilobyte_cap() {
    let big = format!("{} https://paypa1.com/x", "\u{E9}".repeat(6000));
    let a = run(&big, Symbology::Qr);
    let Payload::Text { info } = &a.payload else {
        panic!()
    };
    assert!(info.is_truncated);
    assert!(info.text.len() <= 8192);
    assert!(a.embedded.is_empty());
    let Payload::Text { info } = run(&"a".repeat(8192), Symbology::Qr).payload else {
        panic!()
    };
    assert!(!info.is_truncated);
}

#[test]
fn analyzer_long_structured_codes_keep_their_structure() {
    // SPEC §4.2: only the link search stops at 8 KB; a big vCard is still a contact.
    let note = "Lorem ipsum dolor sit amet. ".repeat(500);
    let card = format!(
        "BEGIN:VCARD\nVERSION:3.0\nFN:Ana Reyes\nNOTE:{note} https://paypa1.com/late\nTEL:+639171234567\nURL:https://example.com/\nEND:VCARD"
    );
    assert!(card.len() > 12_000);
    let a = run(&card, Symbology::Qr);
    let Payload::Contact { info } = &a.payload else {
        panic!("{:?}", a.payload.type_id())
    };
    assert_eq!(info.name.as_deref(), Some("Ana Reyes"));
    assert_eq!(info.phones, vec!["+639171234567".to_string()]);
    // The URL field is a link of its own; the note's late link is past its 8 KB window.
    assert_eq!(info.links, vec!["https://example.com/".to_string()]);
    assert_eq!(a.level, Level::Normal);
    let event = format!(
        "BEGIN:VEVENT\nSUMMARY:Launch\nDTSTART:20261110T090000Z\nDESCRIPTION:{note}\nLOCATION:Hall https://e.example/2\nEND:VEVENT"
    );
    let e = run(&event, Symbology::Qr);
    let Payload::Event { info } = &e.payload else {
        panic!()
    };
    assert_eq!(info.summary.as_deref(), Some("Launch"));
    assert_eq!(info.links, vec!["https://e.example/2".to_string()]);
}

#[test]
fn analyzer_long_links_and_fixed_formats_are_text() {
    let link = format!("https://example.com/{}", "a".repeat(9000));
    let Payload::Text { info } = run(&link, Symbology::Qr).payload else {
        panic!()
    };
    assert!(info.is_truncated && info.text.len() <= 8192);
    // The link's own first 8 KB is still searched.
    assert_eq!(info.links.len(), 1);
    let wifi = format!("WIFI:T:nopass;S:Big;;{}", "a".repeat(9000));
    assert_eq!(run(&wifi, Symbology::Qr).payload.type_id(), "text");
    // Past the 64 KB input cap even a contact is shown cut, as text.
    let huge = format!("BEGIN:VCARD\nFN:A\nNOTE:{}\nEND:VCARD", "a".repeat(70_000));
    let Payload::Text { info } = run(&huge, Symbology::Qr).payload else {
        panic!()
    };
    assert!(info.is_truncated);
}

#[test]
fn analyzer_binary_and_empty() {
    let a = analyzer().analyze(ScannedCode {
        text: None,
        bytes: vec![1, 2, 3],
        symbology: Symbology::DataMatrix,
    });
    assert_eq!(a.payload, Payload::Binary { byte_count: 3 });
    assert!(a.level == Level::Normal && a.signals.is_empty());
    assert_eq!(
        run("", Symbology::Qr).payload,
        Payload::Binary { byte_count: 0 }
    );
}

#[test]
fn analyzer_never_duplicates_signal_ids() {
    for c in all() {
        let a = analyzer().analyze(c.scanned_code());
        for x in std::iter::once(&a).chain(a.embedded.iter()) {
            assert_eq!(
                x.signals.iter().map(|s| s.id).collect::<HashSet<_>>().len(),
                x.signals.len(),
                "{}",
                c.id
            );
            assert_eq!(x.signals, in_stable_order(&x.signals), "{}", c.id);
        }
    }
}

#[test]
fn analyzer_hostile_inputs_do_not_crash() {
    let mut inputs: Vec<String> = [
        "http://",
        "https://@",
        "https://[",
        "https://[::1",
        "http://:80",
        "http://%",
        "javascript:",
        "data:",
        ":",
        "::::",
        "a:b",
        "WIFI:;;",
        "BEGIN:VCARD",
        "BEGIN:VEVENT",
        "000201",
        "BCD",
        "SPC",
        "upi://",
        "mailto:",
        "tel:",
        "sms:",
        "geo:",
        "bitcoin:",
        "\u{0}\u{1}\u{2}",
        "\u{FEFF}https://x.com",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    inputs.push("(".repeat(5000));
    inputs.push("https://".repeat(1500));
    for s in inputs {
        run(&s, Symbology::Qr);
    }
}

#[test]
fn analyzer_thousand_mixed_codes_under_two_seconds() {
    let cases = all();
    let codes: Vec<ScannedCode> = (0..1000)
        .map(|i| cases[i % cases.len()].scanned_code())
        .collect();
    let a = analyzer();
    let start = Instant::now();
    let levels: i64 = codes.into_iter().map(|c| a.analyze(c).level.raw()).sum();
    let elapsed = start.elapsed().as_secs_f64();
    println!(
        "analyzer: 1000 mixed codes in {} ms ({levels})",
        (elapsed * 1000.0) as i64
    );
    // Debug builds are much slower than Swift's optimised test build; the bar is for release.
    assert!(elapsed < if cfg!(debug_assertions) { 20.0 } else { 2.0 });
}

// MARK: ContractTests.swift

#[test]
fn verdict_rule() {
    let s = Signal::new;
    let a = Analyzer::with_known(
        std::sync::Arc::new(BundledData::new(PublicSuffixList::new(""))),
        KnownAddresses::default(),
    );
    assert_eq!(a.level(vec![]), Level::Normal);
    assert_eq!(
        a.level(vec![s(SignalId::AssumedHttps), s(SignalId::AgeUnknown)]),
        Level::Normal
    );
    assert_eq!(a.level(vec![s(SignalId::PlainHttp)]), Level::Careful);
    assert_eq!(
        a.level(vec![s(SignalId::PlainHttp), s(SignalId::IpHost)]),
        Level::Careful
    );
    assert_eq!(
        a.level(vec![
            s(SignalId::PlainHttp),
            s(SignalId::IpHost),
            s(SignalId::RiskyTld)
        ]),
        Level::LikelyScam
    );
    assert_eq!(a.level(vec![s(SignalId::UserinfoTrick)]), Level::LikelyScam);
}

#[test]
fn signal_order_is_strong_warning_info_then_declaration() {
    use SignalId::*;
    let s: Vec<Signal> = [
        AssumedHttps,
        PlainHttp,
        UserinfoTrick,
        IpHost,
        DangerousScheme,
    ]
    .into_iter()
    .map(Signal::new)
    .collect();
    assert_eq!(
        ids(&in_stable_order(&s)),
        vec![
            DangerousScheme,
            UserinfoTrick,
            IpHost,
            PlainHttp,
            AssumedHttps
        ]
    );
}

#[test]
fn every_signal_has_a_severity() {
    let count = |sev| SignalId::ALL.iter().filter(|i| i.severity() == sev).count();
    assert_eq!(count(Severity::Strong), 9);
    assert_eq!(count(Severity::Warning), 18);
    assert_eq!(count(Severity::Info), 7);
}

#[test]
fn payload_fields_are_flat() {
    let mut info = PaymentInfo::new(PaymentFormat::Emv);
    info.payee_name = Some("Ana".into());
    info.is_dynamic = Some(false);
    info.checksum = PaymentChecksum::Valid;
    info.extra.insert("networkID".into(), "x".into());
    let p = Payload::Payment { info };
    assert_eq!(p.type_id(), "payment");
    let f = fields(&p);
    assert_eq!(f.get("payeeName").map(String::as_str), Some("Ana"));
    assert_eq!(f.get("isDynamic").map(String::as_str), Some("false"));
    assert_eq!(f.get("extra.networkID").map(String::as_str), Some("x"));
    assert_eq!(f.get("amount"), None);
    let e = EventInfo {
        start: Some(at(0.0)),
        is_all_day: true,
        ..Default::default()
    };
    assert_eq!(
        fields(&Payload::Event { info: e })
            .get("start")
            .map(String::as_str),
        Some("1970-01-01")
    );
}

#[test]
fn bit_matrix_bounds() {
    let m = BitMatrix::new(2, 1, vec![true, false]);
    assert!(m.get(0, 0) && !m.get(1, 0) && !m.get(5, 5));
    assert_eq!(BitMatrix::new(2, 2, vec![true]).width, 0);
}

#[test]
fn bundled_data_loads_without_files() {
    let _ = BundledData::load();
}

// MARK: BundledDataTests.swift

#[test]
fn bundled_data_loads_every_file_from_the_bundle() {
    let start = Instant::now();
    let d = BundledData::load();
    let elapsed = start.elapsed().as_secs_f64();
    println!("bundled data: loaded in {} ms", (elapsed * 1000.0) as i64);
    assert!(d.brands.len() >= 120);
    assert!(
        !d.shorteners.is_empty()
            && !d.risky_tlds.is_empty()
            && !d.confusables.is_empty()
            && !d.bait_words.is_empty()
    );
    assert!(
        !d.payment_networks.emv.is_empty()
            && !d.payment_networks.crypto_schemes.is_empty()
            && !d.currencies.is_empty()
    );
    assert_eq!(
        d.public_suffixes
            .match_host("www.example.com")
            .registrable_domain
            .as_deref(),
        Some("example.com")
    );
    assert!(d.rdap.base_url("com").is_some());
    assert!(elapsed < if cfg!(debug_assertions) { 5.0 } else { 1.0 });
}

#[test]
fn bundled_data_shared_is_the_same_across_threads() {
    let a = std::thread::spawn(|| BundledData::shared().brands.len());
    let b = std::thread::spawn(|| BundledData::shared().brands.len());
    let (x, y) = (a.join().unwrap(), b.join().unwrap());
    assert!(x == y && x >= 120);
}

#[test]
fn bundled_data_preload_returns_immediately() {
    // Swift's `preload()` starts a background task; the platform does the same around `shared()`.
    let start = Instant::now();
    let h = std::thread::spawn(BundledData::shared);
    assert!(start.elapsed().as_secs_f64() < 0.5);
    h.join().unwrap();
}

#[test]
fn bundled_data_brands_have_domains_and_lowercase_keywords() {
    for b in &shared().brands {
        assert!(!b.domains.is_empty(), "{}", b.name);
        assert!(
            b.keywords.iter().all(|k| *k == qr_core::text::lower(k)),
            "{}",
            b.name
        );
    }
}

#[test]
fn bundled_data_loads_tracking_params() {
    let d = shared();
    assert!(d.tracking_params.iter().any(|p| p.matches("utm_campaign")));
    assert!(d.tracking_params.iter().any(|p| p.matches("fbclid")));
    assert!(
        !d.tracking_params
            .iter()
            .any(|p| p.matches("q") || p.matches("id") || p.matches("page"))
    );
}
