//! Differential parity: `fixtures/swift-analysis.json` is what the real Swift QRCore returned for
//! every fixture input, a set of adversarial extras (combining marks on separators, canonical
//! equivalents, folding, DST gaps, …) and every Peek fixture pair. Each must come out of the Rust
//! core identically: payload type, level, every signal with its arguments in order, every field and
//! every embedded analysis.

mod common;
use base64::Engine;
use common::*;
use qr_core::*;
use serde_json::{Value, json};

fn sig(s: &Signal) -> Value {
    json!({ "id": s.id.raw(), "args": s.args })
}

fn ana(a: &Analysis) -> Value {
    json!({
        "type": a.payload.type_id(),
        "level": a.level.raw(),
        "signals": a.signals.iter().map(sig).collect::<Vec<_>>(),
        "fields": fields(&a.payload),
        "embedded": a.embedded.iter().map(ana).collect::<Vec<_>>(),
    })
}

fn dump() -> Value {
    serde_json::from_slice(include_bytes!("fixtures/swift-analysis.json")).unwrap()
}

#[test]
fn every_case_matches_swift() {
    let d = dump();
    let cases = d["cases"].as_array().unwrap();
    assert!(cases.len() > 500);
    let mut bad = Vec::new();
    for c in cases {
        let sym = Symbology::from_raw(c["symbology"].as_str().unwrap()).unwrap();
        let code = match c["input"].as_str() {
            Some(t) => ScannedCode::text(t, sym),
            None => {
                let bytes = base64::engine::general_purpose::STANDARD
                    .decode(c["inputBase64"].as_str().unwrap())
                    .unwrap_or_default();
                ScannedCode {
                    text: String::from_utf8(bytes.clone()).ok(),
                    bytes,
                    symbology: sym,
                }
            }
        };
        let a = match c["known"].as_array() {
            None => analyzer().analyze(code),
            Some(k) => {
                let mut known = KnownAddresses::default();
                for (i, d) in k.iter().enumerate() {
                    known.add(d.as_str().unwrap(), at(i as f64));
                }
                Analyzer::with_known(shared(), known).analyze(code)
            }
        };
        let got = ana(&a);
        if got != c["analysis"] {
            bad.push(format!(
                "{}:\n  swift {}\n  rust  {}",
                c["id"], c["analysis"], got
            ));
        }
    }
    assert!(bad.is_empty(), "{} differ:\n{}", bad.len(), bad.join("\n"));
}

#[test]
fn every_peek_pair_matches_swift() {
    let d = dump();
    let peek = PeekAnalyzer::with_data(shared());
    let mut bad = Vec::new();
    for e in d["peek"].as_array().unwrap() {
        let path = e["path"].as_str().unwrap();
        let pair: Value = serde_json::from_slice(&fixture_bytes(path)).unwrap();
        let html =
            std::fs::read(fixtures_dir().join(path.replace(".json", ".html"))).unwrap_or_default();
        let s = |v: &Value| v.as_str().map(String::from);
        let capture = PeekCapture {
            hops: pair["hops"]
                .as_array()
                .unwrap()
                .iter()
                .map(|h| PeekHop {
                    url: s(&h["url"]).unwrap(),
                    status: h["status"].as_i64(),
                })
                .collect(),
            stopped_because: StopReason::from_raw(pair["stoppedBecause"].as_str().unwrap())
                .unwrap_or(StopReason::Finished),
            content_type: s(&pair["contentType"]),
            content_disposition: s(&pair["contentDisposition"]),
            body: html.clone(),
            tls_version: None,
        };
        let r = peek.report(
            analyze(pair["original"].as_str().unwrap()),
            capture,
            pair["ageDays"].as_i64(),
        );
        let sn = qr_core::peek::sniff(&html, pair["contentType"].as_str());
        let mut got = json!({
            "path": path,
            "level": r.level.raw(),
            "signals": r.signals.iter().map(sig).collect::<Vec<_>>(),
            "hopLevels": r.hop_analyses.iter().map(|a| a.level.raw()).collect::<Vec<_>>(),
            "sniff": {
                "formActions": sn.form_actions, "inputs": sn.inputs.len(), "scriptRedirect": sn.script_redirect,
                "scriptCount": sn.script_count, "visibleTextLength": sn.visible_text_length,
            },
        });
        if let Some(t) = &r.title {
            got["title"] = json!(t);
        }
        if let Some(f) = &r.final_link {
            got["finalLink"] = json!(qr_core::fields::link_fields(f));
        }
        if let Some(m) = &sn.meta_refresh_url {
            got["metaRefreshURL"] = json!(m);
        }
        if got != *e {
            bad.push(format!("{path}:\n  swift {e}\n  rust  {got}"));
        }
    }
    assert!(bad.is_empty(), "{} differ:\n{}", bad.len(), bad.join("\n"));
}
