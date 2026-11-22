//! Portable constant-width ink. Native authoring owns input; this module owns limits and geometry.
use crate::{AnnotationRect, PageCrop, PageSettings, ScanError, page_crop_rotated};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct InkPoint {
    pub x: f64,
    pub y: f64,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "camelCase")]
pub enum InkColor {
    Black,
    Red,
    Blue,
}
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PageInk {
    pub points: Vec<InkPoint>,
    /// Fraction of the upright original's shorter edge, independent of crop and rotation.
    pub width: f64,
    pub color: InkColor,
    /// The visible original-space page at creation. Off-canvas input must never appear after uncrop.
    pub clip: PageCrop,
}
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct InkDrawing {
    pub points: Vec<InkPoint>,
    pub width: f64,
    pub clip: AnnotationRect,
}
fn full() -> PageCrop {
    PageCrop {
        left: 0.0,
        top: 0.0,
        right: 1.0,
        bottom: 1.0,
    }
}
fn rotated(p: InkPoint, turns: i64) -> InkPoint {
    match turns.rem_euclid(4) {
        1 => InkPoint {
            x: 1.0 - p.y,
            y: p.x,
        },
        2 => InkPoint {
            x: 1.0 - p.x,
            y: 1.0 - p.y,
        },
        3 => InkPoint {
            x: p.y,
            y: 1.0 - p.x,
        },
        _ => p,
    }
}
fn valid_points(points: &[InkPoint]) -> Result<(), ScanError> {
    if points.is_empty() || points.len() > 8192 {
        return Err(ScanError::TooLarge);
    }
    if points.iter().any(|p| {
        !p.x.is_finite()
            || !p.y.is_finite()
            || !(-16.0..=17.0).contains(&p.x)
            || !(-16.0..=17.0).contains(&p.y)
    }) {
        return Err(ScanError::InvalidDocument);
    }
    Ok(())
}
#[uniffi::export]
pub fn ink_validate(ink: &[PageInk]) -> Result<(), ScanError> {
    if ink.len() > 100 || ink.iter().map(|s| s.points.len()).sum::<usize>() > 32768 {
        return Err(ScanError::TooLarge);
    }
    for s in ink {
        valid_points(&s.points)?;
        s.clip.validate()?;
        if !s.width.is_finite() || !(0.000001..=0.1).contains(&s.width) {
            return Err(ScanError::InvalidDocument);
        }
    }
    Ok(())
}
fn metrics(settings: PageSettings, width: f64, height: f64) -> Result<(PageCrop, f64), ScanError> {
    settings.validate()?;
    if !width.is_finite()
        || !height.is_finite()
        || width <= 0.0
        || height <= 0.0
        || width > 100000.0
        || height > 100000.0
    {
        return Err(ScanError::InvalidDocument);
    }
    let crop = page_crop_rotated(
        settings.crop.unwrap_or(full()),
        settings.quarter_turns as u32,
    )?;
    Ok((
        crop,
        (width / (crop.right - crop.left)).min(height / (crop.bottom - crop.top)),
    ))
}
/// Input points are fractions of the displayed page. Outside points remain outside, then clip.
#[uniffi::export]
pub fn ink_from_display(
    points: Vec<InkPoint>,
    width: f64,
    display_width: f64,
    display_height: f64,
    settings: PageSettings,
    color: InkColor,
) -> Result<PageInk, ScanError> {
    valid_points(&points)?;
    let (crop, short) = metrics(settings, display_width, display_height)?;
    let ink = PageInk {
        points: points
            .into_iter()
            .map(|p| {
                rotated(
                    InkPoint {
                        x: crop.left + p.x * (crop.right - crop.left),
                        y: crop.top + p.y * (crop.bottom - crop.top),
                    },
                    4 - settings.quarter_turns,
                )
            })
            .collect(),
        width: width / short,
        color,
        clip: settings.crop.unwrap_or(full()),
    };
    ink_validate(std::slice::from_ref(&ink))?;
    Ok(ink)
}
/// Pixel geometry used unchanged by editor, thumbnails, and PDF raster rendering.
#[uniffi::export]
pub fn ink_display(
    ink: PageInk,
    settings: PageSettings,
    display_width: f64,
    display_height: f64,
) -> Result<InkDrawing, ScanError> {
    ink_validate(std::slice::from_ref(&ink))?;
    let (crop, short) = metrics(settings, display_width, display_height)?;
    let clip = crate::annotation_display_bounds(ink.clip, settings)?;
    Ok(InkDrawing {
        points: ink
            .points
            .into_iter()
            .map(|p| {
                let p = rotated(p, settings.quarter_turns);
                InkPoint {
                    x: (p.x - crop.left) / (crop.right - crop.left) * display_width,
                    y: (p.y - crop.top) / (crop.bottom - crop.top) * display_height,
                }
            })
            .collect(),
        width: ink.width * short,
        clip: AnnotationRect {
            x: clip.x * display_width,
            y: clip.y * display_height,
            width: clip.width * display_width,
            height: clip.height * display_height,
        },
    })
}
