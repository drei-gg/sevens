//! Render data (QRCore `Render/`): the module grid, quiet zones, colour contrast, the SVG writer and
//! label-sheet layout. Data out only; drawing stays native.

use crate::contract::Symbology;

/// A grid of modules, row-major, `true` = dark.
#[derive(Debug, Clone, PartialEq, Eq, Hash, uniffi::Record)]
pub struct BitMatrix {
    pub width: i64,
    pub height: i64,
    pub bits: Vec<bool>,
}

impl BitMatrix {
    /// `bits.len()` must be `width * height`; otherwise the matrix is empty.
    pub fn new(width: i64, height: i64, bits: Vec<bool>) -> Self {
        if width > 0 && height > 0 && bits.len() as i64 == width * height {
            BitMatrix {
                width,
                height,
                bits,
            }
        } else {
            BitMatrix {
                width: 0,
                height: 0,
                bits: vec![],
            }
        }
    }

    /// All light.
    pub fn light(width: i64, height: i64) -> Self {
        Self::new(
            width,
            height,
            vec![false; (width.max(0) * height.max(0)) as usize],
        )
    }

    /// Out of range reads as light.
    pub fn get(&self, x: i64, y: i64) -> bool {
        x >= 0
            && y >= 0
            && x < self.width
            && y < self.height
            && self.bits[(y * self.width + x) as usize]
    }
}

/// A matrix, or an empty one when `bits` doesn't match the size.
#[uniffi::export]
pub fn bit_matrix_new(width: i64, height: i64, bits: Vec<bool>) -> BitMatrix {
    BitMatrix::new(width, height, bits)
}

/// sRGB, components 0…1.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct RgbColor {
    pub red: f64,
    pub green: f64,
    pub blue: f64,
}

impl RgbColor {
    pub const BLACK: RgbColor = RgbColor {
        red: 0.0,
        green: 0.0,
        blue: 0.0,
    };
    pub const WHITE: RgbColor = RgbColor {
        red: 1.0,
        green: 1.0,
        blue: 1.0,
    };

    /// "#rrggbb", lowercase. Components clamped to 0…1 and rounded (NaN reads as 0).
    pub fn hex(&self) -> String {
        let byte = |v: f64| {
            if v.is_nan() {
                0
            } else {
                (v.clamp(0.0, 1.0) * 255.0).round() as u8
            }
        };
        format!(
            "#{:02x}{:02x}{:02x}",
            byte(self.red),
            byte(self.green),
            byte(self.blue)
        )
    }
}

#[uniffi::export]
pub fn rgb_hex(color: RgbColor) -> String {
    color.hex()
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Enum)]
pub enum ContrastWarning {
    /// WCAG ratio under 4.0.
    LowContrast { ratio: f64 },
    /// Foreground lighter than background.
    Inverted,
}

/// WCAG 2.x relative luminance.
pub fn luminance(c: RgbColor) -> f64 {
    let lin = |v: f64| {
        let x = if v.is_nan() { 0.0 } else { v.clamp(0.0, 1.0) };
        if x <= 0.04045 {
            x / 12.92
        } else {
            ((x + 0.055) / 1.055).powf(2.4)
        }
    };
    0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}

/// WCAG 2 contrast ratio, 1…21.
#[uniffi::export]
pub fn contrast_ratio(a: RgbColor, b: RgbColor) -> f64 {
    let (la, lb) = (luminance(a), luminance(b));
    (la.max(lb) + 0.05) / (la.min(lb) + 0.05)
}

/// Warnings in display order (low contrast, then inverted); empty when fine. Warns, never blocks.
#[uniffi::export]
pub fn contrast_warnings(foreground: RgbColor, background: RgbColor) -> Vec<ContrastWarning> {
    let mut out = Vec::new();
    let r = contrast_ratio(foreground, background);
    if r < 4.0 {
        out.push(ContrastWarning::LowContrast { ratio: r });
    }
    if luminance(foreground) > luminance(background) {
        out.push(ContrastWarning::Inverted);
    }
    out
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct QuietZoneModules {
    pub horizontal: i64,
    pub vertical: i64,
}

/// Quiet zone in modules: QR 4 all round (Micro QR 2), Aztec 1, PDF417 and MicroPDF417 2, Data
/// Matrix 1, 1D codes 10 left and right (0 top and bottom). Anything else 4.
#[uniffi::export]
pub fn quiet_zone_modules(symbology: Symbology) -> QuietZoneModules {
    use Symbology::*;
    let (horizontal, vertical) = match symbology {
        Qr => (4, 4),
        MicroQr => (2, 2),
        Aztec => (1, 1),
        Pdf417 | MicroPdf417 => (2, 2),
        DataMatrix => (1, 1),
        Code128 | Code39 | Code93 | Ean13 | Ean8 | Upce | Itf14 | Codabar | Gs1DataBar => (10, 0),
        Other => (4, 4),
    };
    QuietZoneModules {
        horizontal,
        vertical,
    }
}

/// Pads with light modules. Negative values count as 0. An empty matrix stays empty.
#[uniffi::export]
pub fn quiet_zone_add(matrix: BitMatrix, horizontal: i64, vertical: i64) -> BitMatrix {
    if matrix.width <= 0 || matrix.height <= 0 {
        return matrix;
    }
    let (h, v) = (horizontal.max(0), vertical.max(0));
    let (w, ht) = (matrix.width + 2 * h, matrix.height + 2 * v);
    let mut bits = vec![false; (w * ht) as usize];
    for y in 0..matrix.height {
        for x in 0..matrix.width {
            if matrix.get(x, y) {
                bits[((y + v) * w + x + h) as usize] = true;
            }
        }
    }
    BitMatrix::new(w, ht, bits)
}

/// One `<path>` of horizontal runs, crisp edges, colours as hex. `module_size` under 1 counts as 1;
/// `background` None leaves it transparent. Deterministic; ends with a newline.
#[uniffi::export]
pub fn svg(
    matrix: BitMatrix,
    module_size: i64,
    foreground: RgbColor,
    background: Option<RgbColor>,
) -> String {
    let s = module_size.max(1);
    let (w, h) = (matrix.width * s, matrix.height * s);
    let mut out = format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {w} {h}\" width=\"{w}\" height=\"{h}\" shape-rendering=\"crispEdges\">\n"
    );
    if let Some(bg) = background {
        out += &format!(
            "<rect width=\"100%\" height=\"100%\" fill=\"{}\"/>\n",
            bg.hex()
        );
    }
    let mut d = String::new();
    for y in 0..matrix.height {
        let mut x = 0;
        while x < matrix.width {
            if !matrix.get(x, y) {
                x += 1;
                continue;
            }
            let mut end = x + 1;
            while end < matrix.width && matrix.get(end, y) {
                end += 1;
            }
            let len = (end - x) * s;
            d += &format!("M{} {}h{len}v{s}h-{len}z", x * s, y * s);
            x = end;
        }
    }
    if !d.is_empty() {
        out += &format!("<path fill=\"{}\" d=\"{d}\"/>\n", foreground.hex());
    }
    out + "</svg>\n"
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum PaperSize {
    A4,
    Letter,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PageSize {
    /// Points (1/72 in).
    pub width: f64,
    pub height: f64,
}

#[uniffi::export]
pub fn paper_size_points(paper: PaperSize) -> PageSize {
    match paper {
        PaperSize::A4 => PageSize {
            width: 595.28,
            height: 841.89,
        },
        PaperSize::Letter => PageSize {
            width: 612.0,
            height: 792.0,
        },
    }
}

/// Regions CLDR lists as US Letter.
const LETTER_REGIONS: &[&str] = &[
    "BZ", "CA", "CL", "CO", "CR", "GT", "MX", "NI", "PA", "PH", "PR", "SV", "US", "VE",
];

/// US Letter for regions that use it, A4 otherwise and for None or unknown codes.
#[uniffi::export]
pub fn paper_size_for_region(region_code: Option<String>) -> PaperSize {
    match region_code {
        Some(c) if LETTER_REGIONS.contains(&crate::text::upper(&c).as_str()) => PaperSize::Letter,
        _ => PaperSize::A4,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum SheetGrid {
    Grid2x3,
    Grid3x4,
    Grid4x6,
}

impl SheetGrid {
    pub fn columns(self) -> i64 {
        match self {
            Self::Grid2x3 => 2,
            Self::Grid3x4 => 3,
            Self::Grid4x6 => 4,
        }
    }
    pub fn rows(self) -> i64 {
        match self {
            Self::Grid2x3 => 3,
            Self::Grid3x4 => 4,
            Self::Grid4x6 => 6,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct SheetCell {
    /// Points from the top-left of the page.
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// Page margin on every side, points.
pub const SHEET_MARGIN: f64 = 36.0;
/// Gap between neighbouring cells, points.
pub const SHEET_GUTTER: f64 = 12.0;

/// Cells left to right, top to bottom, inside 36 pt margins with a 12 pt gutter.
#[uniffi::export]
pub fn sheet_cells(grid: SheetGrid, paper: PaperSize) -> Vec<SheetCell> {
    let page = paper_size_points(paper);
    let (cols, rows) = (grid.columns(), grid.rows());
    let cw = (page.width - 2.0 * SHEET_MARGIN - (cols - 1) as f64 * SHEET_GUTTER) / cols as f64;
    let ch = (page.height - 2.0 * SHEET_MARGIN - (rows - 1) as f64 * SHEET_GUTTER) / rows as f64;
    let mut out = Vec::with_capacity((cols * rows) as usize);
    for r in 0..rows {
        for c in 0..cols {
            out.push(SheetCell {
                x: SHEET_MARGIN + c as f64 * (cw + SHEET_GUTTER),
                y: SHEET_MARGIN + r as f64 * (ch + SHEET_GUTTER),
                width: cw,
                height: ch,
            });
        }
    }
    out
}

#[uniffi::export]
pub fn sheet_grid_columns(grid: SheetGrid) -> i64 {
    grid.columns()
}

#[uniffi::export]
pub fn sheet_grid_rows(grid: SheetGrid) -> i64 {
    grid.rows()
}

/// The label sheet's page margin (`SHEET_MARGIN`), points; a single-code page uses it too.
#[uniffi::export]
pub fn sheet_margin() -> f64 {
    SHEET_MARGIN
}

/// The gap between label cells (`SHEET_GUTTER`), points.
#[uniffi::export]
pub fn sheet_gutter() -> f64 {
    SHEET_GUTTER
}
