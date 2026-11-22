//! Swift parity: RenderTests (SVG golden files shared with the Swift suite) and
//! MakeLinkCompactorTests (the shared `make/link-compaction.json` fixtures).

mod common;
use common::*;
use qr_core::data::TrackingParam;
use qr_core::make::{capitalized, compacted, removing_tracking, tracking_parameters, trimmed};
use qr_core::*;
use serde::Deserialize;

// MARK: RenderTests.swift

fn matrix(rows: &str) -> BitMatrix {
    let lines: Vec<&str> = rows.split('/').collect();
    let w = lines.first().map_or(0, |l| l.len()) as i64;
    BitMatrix::new(
        w,
        lines.len() as i64,
        lines
            .iter()
            .flat_map(|l| l.chars().map(|c| c == '#'))
            .collect(),
    )
}

fn three_by_three() -> BitMatrix {
    matrix("#.#/.#./#.#")
}

fn barcode() -> BitMatrix {
    matrix("#.##.#...#.#.##")
}

fn qr_like() -> BitMatrix {
    let mut bits = vec![false; 21 * 21];
    for (ox, oy) in [(0i64, 0i64), (14, 0), (0, 14)] {
        for y in 0..7i64 {
            for x in 0..7i64 {
                let ring = (x - 3).abs().max((y - 3).abs());
                bits[((oy + y) * 21 + ox + x) as usize] = ring != 2;
            }
        }
    }
    for i in (8..=12).filter(|i| i % 2 == 0) {
        bits[6 * 21 + i] = true;
        bits[i * 21 + 6] = true;
    }
    BitMatrix::new(21, 21, bits)
}

const BLACK: RgbColor = RgbColor::BLACK;
const WHITE: RgbColor = RgbColor::WHITE;

#[test]
fn golden_files() {
    for (name, scale) in [("3x3", 2), ("qr21", 4), ("bar1d", 1)] {
        let m = match name {
            "3x3" => three_by_three(),
            "qr21" => qr_like(),
            _ => barcode(),
        };
        assert_eq!(
            svg(m.clone(), scale, BLACK, None),
            fixture_text(&format!("render/{name}.svg")),
            "{name}"
        );
        assert_eq!(
            svg(m, scale, BLACK, Some(WHITE)),
            fixture_text(&format!("render/{name}-bg.svg")),
            "{name}-bg"
        );
    }
}

#[test]
fn empty_matrix_has_no_path() {
    let s = svg(BitMatrix::light(0, 0), 4, BLACK, None);
    assert!(s.starts_with("<svg ") && !s.contains("<path") && s.ends_with("</svg>\n"));
}

#[test]
fn all_light_matrix_has_no_path() {
    assert!(!svg(BitMatrix::light(3, 3), 1, BLACK, None).contains("<path"));
}

#[test]
fn module_size_below_one_counts_as_one() {
    assert_eq!(
        svg(three_by_three(), 0, BLACK, None),
        svg(three_by_three(), 1, BLACK, None)
    );
}

#[test]
fn exactly_one_path_and_hex_colors() {
    let s = svg(
        qr_like(),
        3,
        RgbColor {
            red: 1.0,
            green: 0.0,
            blue: 0.0,
        },
        Some(WHITE),
    );
    assert_eq!(s.matches("<path").count(), 1);
    assert!(s.contains("fill=\"#ff0000\""));
    assert!(s.contains("viewBox=\"0 0 63 63\""));
}

#[test]
fn deterministic() {
    assert_eq!(
        svg(qr_like(), 2, BLACK, None),
        svg(qr_like(), 2, BLACK, None)
    );
}

#[test]
fn hex_clamps_and_rounds() {
    let c = |r: f64, g: f64, b: f64| {
        RgbColor {
            red: r,
            green: g,
            blue: b,
        }
        .hex()
    };
    assert_eq!(BLACK.hex(), "#000000");
    assert_eq!(WHITE.hex(), "#ffffff");
    assert_eq!(c(-1.0, 2.0, 0.5), "#00ff80");
    assert_eq!(c(f64::NAN, f64::INFINITY, 0.2), "#00ff33");
    assert_eq!(c(10.0 / 255.0, 171.0 / 255.0, 1.0 / 255.0), "#0aab01");
}

#[test]
fn extremes() {
    assert!((contrast_ratio(BLACK, WHITE) - 21.0).abs() < 1e-9);
    assert!((contrast_ratio(WHITE, BLACK) - 21.0).abs() < 1e-9);
    assert!((contrast_ratio(WHITE, WHITE) - 1.0).abs() < 1e-9);
}

#[test]
fn known_grey() {
    let g = RgbColor {
        red: 0x77 as f64 / 255.0,
        green: 0x77 as f64 / 255.0,
        blue: 0x77 as f64 / 255.0,
    };
    assert!((contrast_ratio(g, WHITE) - 4.48).abs() < 0.01);
}

#[test]
fn black_on_white_is_fine() {
    assert!(contrast_warnings(BLACK, WHITE).is_empty());
}

#[test]
fn low_contrast_warns() {
    let w = contrast_warnings(
        RgbColor {
            red: 0.6,
            green: 0.6,
            blue: 0.6,
        },
        WHITE,
    );
    assert_eq!(w.len(), 1);
    assert!(matches!(w[0], ContrastWarning::LowContrast { ratio } if ratio < 4.0));
}

#[test]
fn inverted_warns_alone() {
    assert_eq!(
        contrast_warnings(WHITE, BLACK),
        vec![ContrastWarning::Inverted]
    );
}

#[test]
fn both_apply_low_contrast_first() {
    let w = contrast_warnings(
        WHITE,
        RgbColor {
            red: 0.9,
            green: 0.9,
            blue: 0.9,
        },
    );
    assert_eq!(w.len(), 2);
    assert!(matches!(w[0], ContrastWarning::LowContrast { .. }));
    assert_eq!(w[1], ContrastWarning::Inverted);
}

#[test]
fn module_counts() {
    let q = |s| {
        let m = quiet_zone_modules(s);
        (m.horizontal, m.vertical)
    };
    use Symbology::*;
    assert_eq!(q(Qr), (4, 4));
    assert_eq!(q(MicroQr), (2, 2));
    assert_eq!(q(Aztec), (1, 1));
    assert_eq!(q(Pdf417), (2, 2));
    assert_eq!(q(MicroPdf417), (2, 2));
    assert_eq!(q(DataMatrix), (1, 1));
    assert_eq!(q(Code128), (10, 0));
    assert_eq!(q(Ean13), (10, 0));
    assert_eq!(q(Other), (4, 4));
}

#[test]
fn every_symbology_covered() {
    for s in Symbology::ALL {
        let q = quiet_zone_modules(s);
        assert!(q.horizontal >= 0 && q.vertical >= 0);
    }
}

#[test]
fn add_pads_with_light_modules() {
    let m = three_by_three();
    let p = quiet_zone_add(m.clone(), 2, 1);
    assert!(p.width == 7 && p.height == 5);
    for y in 0..5 {
        for x in 0..7 {
            let inside = (2..5).contains(&x) && (1..4).contains(&y);
            assert_eq!(p.get(x, y), inside && m.get(x - 2, y - 1));
        }
    }
}

#[test]
fn zero_and_negative_and_empty() {
    let m = three_by_three();
    assert_eq!(quiet_zone_add(m.clone(), 0, 0), m);
    assert_eq!(quiet_zone_add(m.clone(), -3, -1), m);
    let e = BitMatrix::light(0, 0);
    assert_eq!(quiet_zone_add(e.clone(), 4, 4), e);
}

#[test]
fn paper_sizes() {
    assert_eq!(
        paper_size_points(PaperSize::A4),
        PageSize {
            width: 595.28,
            height: 841.89
        }
    );
    assert_eq!(
        paper_size_points(PaperSize::Letter),
        PageSize {
            width: 612.0,
            height: 792.0
        }
    );
}

#[test]
fn regions() {
    for r in [
        "US", "CA", "MX", "PH", "CL", "CO", "VE", "CR", "GT", "PA", "PR", "SV", "NI", "BZ", "us",
    ] {
        assert_eq!(
            paper_size_for_region(Some(r.into())),
            PaperSize::Letter,
            "{r}"
        );
    }
    for r in [
        Some("GB"),
        Some("DE"),
        Some("FR"),
        Some("JP"),
        Some("AU"),
        Some("ZZ"),
        Some(""),
        None,
    ] {
        assert_eq!(
            paper_size_for_region(r.map(String::from)),
            PaperSize::A4,
            "{r:?}"
        );
    }
}

#[test]
fn grid_dimensions() {
    assert_eq!(
        (SheetGrid::Grid2x3.columns(), SheetGrid::Grid2x3.rows()),
        (2, 3)
    );
    assert_eq!(
        (SheetGrid::Grid3x4.columns(), SheetGrid::Grid3x4.rows()),
        (3, 4)
    );
    assert_eq!(
        (SheetGrid::Grid4x6.columns(), SheetGrid::Grid4x6.rows()),
        (4, 6)
    );
}

#[test]
fn layout_invariants() {
    let eps = 1e-9;
    for grid in [SheetGrid::Grid2x3, SheetGrid::Grid3x4, SheetGrid::Grid4x6] {
        for paper in [PaperSize::A4, PaperSize::Letter] {
            let cells = sheet_cells(grid, paper);
            let page = paper_size_points(paper);
            assert_eq!(cells.len() as i64, grid.columns() * grid.rows());
            let first = cells[0];
            for c in &cells {
                assert!(
                    (c.width - first.width).abs() < eps && (c.height - first.height).abs() < eps
                );
                assert!(c.width > 0.0 && c.height > 0.0);
                assert!(c.x >= 36.0 - eps && c.y >= 36.0 - eps);
                assert!(c.x + c.width <= page.width - 36.0 + eps);
                assert!(c.y + c.height <= page.height - 36.0 + eps);
            }
            for i in 0..cells.len() {
                for j in i + 1..cells.len() {
                    let (a, b) = (cells[i], cells[j]);
                    let overlap = a.x < b.x + b.width - eps
                        && b.x < a.x + a.width - eps
                        && a.y < b.y + b.height - eps
                        && b.y < a.y + a.height - eps;
                    assert!(!overlap);
                }
            }
            for (i, c) in cells.iter().enumerate() {
                let (col, row) = (i as i64 % grid.columns(), i as i64 / grid.columns());
                assert!((c.x - (36.0 + col as f64 * (first.width + 12.0))).abs() < eps);
                assert!((c.y - (36.0 + row as f64 * (first.height + 12.0))).abs() < eps);
            }
            let last = cells.last().unwrap();
            assert!((last.x + last.width - (page.width - 36.0)).abs() < 1e-6);
            assert!((last.y + last.height - (page.height - 36.0)).abs() < 1e-6);
        }
    }
}

// MARK: MakeLinkCompactorTests.swift

#[derive(Deserialize)]
struct Case {
    id: String,
    #[allow(dead_code)]
    note: String,
    input: String,
    trimmed: String,
    compacted: Option<String>,
    #[allow(dead_code)]
    gains: bool,
}

fn cases() -> Vec<Case> {
    serde_json::from_slice(&fixture_bytes("make/link-compaction.json")).unwrap()
}

fn list() -> Vec<TrackingParam> {
    ["utm_*", "fbclid", "gclid"]
        .iter()
        .map(|n| TrackingParam {
            name: n.to_string(),
            source: "x".into(),
        })
        .collect()
}

#[test]
fn fixtures_load() {
    let c = cases();
    assert!(c.len() >= 25);
    assert_eq!(
        c.iter()
            .map(|x| &x.id)
            .collect::<std::collections::HashSet<_>>()
            .len(),
        c.len()
    );
}

#[test]
fn fixture() {
    for c in cases() {
        assert_eq!(trimmed(&c.input), c.trimmed, "{}", c.id);
        assert_eq!(compacted(&c.input), c.compacted, "{}", c.id);
    }
}

#[test]
fn compacted_parses_to_the_same_link() {
    let psl = &shared().public_suffixes;
    for c in cases().into_iter().filter(|c| c.compacted.is_some()) {
        let before = WebAddress::parse(&c.input, psl).unwrap();
        let after = WebAddress::parse(c.compacted.as_deref().unwrap(), psl).unwrap();
        assert_eq!(after.link.normalized, before.link.normalized, "{}", c.id);
        assert_eq!(after.link.host_ascii, before.link.host_ascii);
        assert_eq!(
            after.link.registrable_domain,
            before.link.registrable_domain
        );
    }
}

#[test]
fn compacting_twice_changes_nothing() {
    for c in cases() {
        if let Some(once) = compacted(&c.input) {
            assert_eq!(compacted(&once), Some(once.clone()), "{}", c.id);
        }
    }
}

#[test]
fn hostile_input_never_traps() {
    let mut odd: Vec<String> = [
        "",
        "http://",
        "https://[",
        "https://[::1",
        "https://a:b:c/",
        "https:////x",
        "https:\\\\x\\y",
        "https://@",
        "https://:443",
        "http://x:99999",
        "https://example.com:443:443",
        "\u{0}https://x.y",
        "HTTPS://\u{FFFD}.com",
    ]
    .iter()
    .map(|s| s.to_string())
    .collect();
    odd.push("https://a.b/".repeat(1000));
    for s in odd {
        trimmed(&s);
        compacted(&s);
        capitalized(&s);
        tracking_parameters(&s, &list());
        removing_tracking(&s, &list());
    }
}

#[test]
fn finds_tracking_parameters_in_order() {
    let link = "https://example.com/p?id=7&UTM_Source=a&fbclid=1&utm_x&keep=Yes&gclid=z";
    assert_eq!(
        tracking_parameters(link, &list()),
        vec!["UTM_Source", "fbclid", "utm_x", "gclid"]
    );
}

#[test]
fn removal_keeps_the_rest_verbatim() {
    let link = "https://example.com/Path?id=7&utm_source=a&Keep=Yes%20No&fbclid=1#Frag";
    assert_eq!(
        removing_tracking(link, &list()).as_deref(),
        Some("https://example.com/Path?id=7&Keep=Yes%20No#Frag")
    );
}

#[test]
fn empty_query_mark_goes() {
    assert_eq!(
        removing_tracking("https://example.com/a?utm_source=x&gclid=y", &list()).as_deref(),
        Some("https://example.com/a")
    );
    assert_eq!(
        removing_tracking("https://example.com/?fbclid=1#top", &list()).as_deref(),
        Some("https://example.com/#top")
    );
}

#[test]
fn nil_when_there_is_nothing_to_remove() {
    for s in [
        "https://example.com/a?x=1",
        "https://example.com/a",
        "https://example.com/a#utm_source=1",
        "https://example.com/a?",
        "mailto:a@b.c?utm_source=1",
        "https://example.com/a?utmx=1&xutm_source=2",
    ] {
        assert_eq!(removing_tracking(s, &list()), None, "{s}");
        assert!(tracking_parameters(s, &list()).is_empty(), "{s}");
    }
}

#[test]
fn userinfo_and_port_survive_removal() {
    assert_eq!(
        removing_tracking("http://u:p@Example.com:8080/a?gclid=1&b=2", &list()).as_deref(),
        Some("http://u:p@Example.com:8080/a?b=2")
    );
}

#[test]
fn empty_list_finds_nothing() {
    assert_eq!(
        removing_tracking("https://example.com/?utm_source=1", &[]),
        None
    );
}
