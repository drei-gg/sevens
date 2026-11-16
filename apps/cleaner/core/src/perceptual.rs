//! A perceptual image hash for the similar-shots pass where the platform has no feature prints.
//! iOS compares Vision feature prints (`similar_group`); Android has no system equivalent, so it
//! hashes a 32 × 32 thumbnail here (the classic DCT "pHash") and compares Hamming distances.
//! Same window, same review-only suggestions; the threshold is provisional, as Vision's is, and is
//! never used for automatic selection. Additive: nothing on iOS calls this.

use crate::library::{MatchGroup, MatchKind};
use crate::{CleanerError, blocked};

/// The side of the square thumbnail the hash reads.
pub const PERCEPTUAL_SIDE: u32 = 32;
/// Hamming distances (of 64 bits) at or below this are suggested as similar. Provisional.
pub const SIMILAR_HASH_DISTANCE: u32 = 10;
const LOW: usize = 8;

/// The 64-bit perceptual hash of a 32 × 32 RGBA thumbnail (row-major, 4 bytes per pixel, alpha
/// ignored): luma, a 2-D DCT-II, the top-left 8 × 8 coefficients compared with their median
/// (the DC term left out of the median). Similar pictures differ in few bits.
#[uniffi::export]
pub fn perceptual_hash(rgba: &[u8]) -> Result<u64, CleanerError> {
    let side = PERCEPTUAL_SIDE as usize;
    if rgba.len() != side * side * 4 {
        return Err(blocked("Could not read this photo for comparison."));
    }
    let luma: Vec<f64> = rgba
        .chunks_exact(4)
        .map(|p| 0.299 * p[0] as f64 + 0.587 * p[1] as f64 + 0.114 * p[2] as f64)
        .collect();
    let cos: Vec<f64> = (0..LOW * side)
        .map(|i| {
            let (u, x) = (i / side, i % side);
            (std::f64::consts::PI * (2 * x + 1) as f64 * u as f64 / (2 * side) as f64).cos()
        })
        .collect();
    // Rows first (only the 8 lowest frequencies are needed), then columns.
    let mut rows = vec![0.0; side * LOW];
    for y in 0..side {
        for u in 0..LOW {
            rows[y * LOW + u] = (0..side).map(|x| luma[y * side + x] * cos[u * side + x]).sum();
        }
    }
    let mut low = [0.0f64; LOW * LOW];
    for v in 0..LOW {
        for u in 0..LOW {
            low[v * LOW + u] = (0..side).map(|y| rows[y * LOW + u] * cos[v * side + y]).sum();
        }
    }
    let mut rest: Vec<f64> = low[1..].to_vec();
    rest.sort_by(|a, b| a.total_cmp(b));
    let median = (rest[rest.len() / 2 - 1] + rest[rest.len() / 2]) / 2.0;
    Ok(low.iter().enumerate().fold(0u64, |hash, (i, c)| if *c > median { hash | (1 << i) } else { hash }))
}

/// Bits that differ between two perceptual hashes, 0 to 64.
#[uniffi::export]
pub fn perceptual_distance(a: u64, b: u64) -> u32 {
    (a ^ b).count_ones()
}

/// [`crate::similar_group`] for perceptual hashes: the suggestion for a nearby pair, or `None`
/// above the threshold. Review only; never preselected.
#[uniffi::export]
pub fn similar_hash_group(earlier_id: String, later_id: String, distance: u32) -> Option<MatchGroup> {
    if distance > SIMILAR_HASH_DISTANCE {
        return None;
    }
    Some(MatchGroup {
        id: format!("similar:{earlier_id}:{later_id}"),
        kind: MatchKind::Similar,
        members: vec![earlier_id, later_id],
        reason: format!(
            "Nearby capture times and an image-hash distance of {distance} in 64. Inspect both; different moments may look alike."
        ),
    })
}
