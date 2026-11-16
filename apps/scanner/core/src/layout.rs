//! Page geometry from Swift `DocumentProcessing` (Processing.swift): the pixel sizes pages are
//! normalized and rasterized to, the PDF paper boxes, where a page image sits on the paper, the
//! render size and JPEG quality each export quality uses, and where a recognized line's invisible
//! text goes. Pixels, codecs and the PDF writer stay native; these numbers are the same on both.
//!
//! PDF coordinates are points with the origin at the bottom left. Recognition bounds are
//! normalized to the image (0...1), also with the origin at the bottom left, as Vision gives them.

use crate::export::PdfQuality;
use crate::settings::PaperSize;

/// The longest side of a normalized page image, in pixels.
pub const NORMALIZED_MAX_PIXELS: i64 = 2400;
/// An imported file (image or PDF) larger than this is refused before it is read.
pub const MAX_IMPORT_FILE_BYTES: i64 = 100_000_000;
/// Previews and recognition read pages at this size unless asked otherwise.
pub const RENDER_MAX_PIXELS: i64 = 2400;
/// JPEG quality for normalized, imported and rendered pages.
pub const PAGE_JPEG_QUALITY: f64 = 0.85;

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PixelSize {
    pub width: i64,
    pub height: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PdfRect {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

/// How one export quality renders each page.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PdfRender {
    pub max_pixels: i64,
    pub jpeg_quality: f64,
}

/// The size limits and qualities above, for the platforms.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PageProcessing {
    pub normalized_max_pixels: i64,
    pub render_max_pixels: i64,
    pub page_jpeg_quality: f64,
    pub max_import_file_bytes: i64,
}

#[uniffi::export]
pub fn page_processing() -> PageProcessing {
    PageProcessing {
        normalized_max_pixels: NORMALIZED_MAX_PIXELS,
        render_max_pixels: RENDER_MAX_PIXELS,
        page_jpeg_quality: PAGE_JPEG_QUALITY,
        max_import_file_bytes: MAX_IMPORT_FILE_BYTES,
    }
}

/// A captured or imported image scaled to fit 2400 pixels on its longest side, never enlarged.
/// Swift keeps the fractional size and lets the renderer round; this rounds to whole pixels,
/// at least one each way. `None` for an empty or non-finite size.
#[uniffi::export]
pub fn normalized_page_size(width: f64, height: f64) -> Option<PixelSize> {
    if !(width.is_finite() && height.is_finite()) || width <= 0.0 || height <= 0.0 {
        return None;
    }
    let scale = (NORMALIZED_MAX_PIXELS as f64 / width.max(height)).min(1.0);
    Some(PixelSize { width: ((width * scale).round() as i64).max(1), height: ((height * scale).round() as i64).max(1) })
}

/// The bitmap an imported PDF page is drawn into: 2400 pixels on its longest side, at most three
/// pixels per point, at least one pixel each way. `width`/`height` are the page's media box in
/// points after its rotation. `None` for an empty or non-finite box (the import is refused).
#[uniffi::export]
pub fn pdf_import_raster_size(width: f64, height: f64) -> Option<PixelSize> {
    if !(width.is_finite() && height.is_finite()) || width <= 0.0 || height <= 0.0 {
        return None;
    }
    let scale = (NORMALIZED_MAX_PIXELS as f64 / width.max(height)).min(3.0);
    Some(PixelSize { width: (width * scale).round().max(1.0) as i64, height: (height * scale).round().max(1.0) as i64 })
}

/// The paper in points: A4 595 × 842, US Letter 612 × 792.
#[uniffi::export]
pub fn paper_box(size: PaperSize) -> PdfRect {
    let (width, height) = match size {
        PaperSize::A4 => (595.0, 842.0),
        PaperSize::Letter => (612.0, 792.0),
    };
    PdfRect { x: 0.0, y: 0.0, width, height }
}

/// Standard: 2400 pixels at JPEG 0.85. Smaller file: 1200 pixels at 0.55.
#[uniffi::export]
pub fn pdf_render(quality: PdfQuality) -> PdfRender {
    match quality {
        PdfQuality::Standard => PdfRender { max_pixels: 2400, jpeg_quality: 0.85 },
        PdfQuality::Compact => PdfRender { max_pixels: 1200, jpeg_quality: 0.55 },
    }
}

/// The page image on the paper: as large as fits inside an 18-point margin on every side, keeping
/// its aspect, centred. Images smaller than that are enlarged, as Swift does.
#[uniffi::export]
pub fn pdf_image_frame(size: PaperSize, image_width: i64, image_height: i64) -> Option<PdfRect> {
    if image_width <= 0 || image_height <= 0 {
        return None;
    }
    let paper = paper_box(size);
    let (w, h) = (image_width as f64, image_height as f64);
    let scale = ((paper.width - 36.0) / w).min((paper.height - 36.0) / h);
    let (width, height) = (w * scale, h * scale);
    Some(PdfRect { x: (paper.width - width) / 2.0, y: (paper.height - height) / 2.0, width, height })
}

/// Where a recognized line's invisible text goes on the paper: its normalized bounds (origin at
/// the bottom left, as Vision reports them) mapped into the image's frame.
#[uniffi::export]
pub fn pdf_text_rect(frame: PdfRect, bounds: PdfRect) -> PdfRect {
    PdfRect {
        x: frame.x + bounds.x * frame.width,
        y: frame.y + bounds.y * frame.height,
        width: bounds.width * frame.width,
        height: bounds.height * frame.height,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalized_size_fits_2400_and_never_enlarges() {
        assert_eq!(normalized_page_size(4032.0, 3024.0), Some(PixelSize { width: 2400, height: 1800 }));
        assert_eq!(normalized_page_size(600.0, 800.0), Some(PixelSize { width: 600, height: 800 }));
        assert_eq!(normalized_page_size(0.0, 800.0), None);
        assert_eq!(normalized_page_size(f64::NAN, 800.0), None);
    }

    #[test]
    fn pdf_import_raster_caps_at_three_pixels_per_point() {
        // A4 at 2400 / 842 ≈ 2.85 px per point.
        assert_eq!(pdf_import_raster_size(595.0, 842.0), Some(PixelSize { width: 1696, height: 2400 }));
        // A business card is capped at 3x.
        assert_eq!(pdf_import_raster_size(252.0, 144.0), Some(PixelSize { width: 756, height: 432 }));
        assert_eq!(pdf_import_raster_size(-1.0, 10.0), None);
    }

    #[test]
    fn image_frame_centres_inside_an_18_point_margin() {
        let frame = pdf_image_frame(PaperSize::A4, 1800, 2400).unwrap();
        // Width-limited: 559 / 1800, centred vertically.
        assert!((frame.width - 559.0).abs() < 1e-9);
        assert!((frame.x - 18.0).abs() < 1e-9);
        assert!((frame.y - (842.0 - frame.height) / 2.0).abs() < 1e-9);
        let tall = pdf_image_frame(PaperSize::A4, 1000, 2400).unwrap();
        assert!((tall.height - 806.0).abs() < 1e-9);
        assert!((tall.y - 18.0).abs() < 1e-9);
        let letter = pdf_image_frame(PaperSize::Letter, 2400, 1800).unwrap();
        assert!((letter.width - 576.0).abs() < 1e-9);
        assert!((letter.x - 18.0).abs() < 1e-9);
        assert_eq!(pdf_image_frame(PaperSize::A4, 0, 10), None);
    }

    #[test]
    fn text_rect_maps_normalized_bounds_into_the_frame() {
        let frame = PdfRect { x: 10.0, y: 20.0, width: 100.0, height: 200.0 };
        let rect = pdf_text_rect(frame, PdfRect { x: 0.5, y: 0.25, width: 0.5, height: 0.1 });
        assert_eq!(rect, PdfRect { x: 60.0, y: 70.0, width: 50.0, height: 20.0 });
    }

    #[test]
    fn qualities_and_paper() {
        assert_eq!(pdf_render(PdfQuality::Compact), PdfRender { max_pixels: 1200, jpeg_quality: 0.55 });
        assert_eq!(paper_box(PaperSize::Letter).width, 612.0);
        assert_eq!(page_processing().max_import_file_bytes, 100_000_000);
    }
}
