//! Bounded native video-copy profile. Platforms inspect and encode; these rules are shared.
use crate::{blocked, CleanerError};

#[uniffi::export]
pub fn check_video_copy_source(
    item: &crate::CleanupItem,
    state: &crate::ReviewState,
) -> Result<(), CleanerError> {
    if item.kind != crate::MediaKind::Video {
        return Err(blocked("Choose a video from the accessible library."));
    }
    if state
        .processed_sources
        .contains(&crate::compression_source_key(item))
        || state
            .processed_sources
            .contains(&format!("output:{}", item.id))
    {
        return Err(blocked("This source revision or saved copy was already processed. The original remains available."));
    }
    Ok(())
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct VideoFacts {
    /// Upright display dimensions, after the container's orientation transform.
    pub width: u32,
    pub height: u32,
    pub duration_ms: u64,
    pub frame_rate: f64,
    pub bytes: u64,
    pub video_codec: String,
    pub bit_depth: u32,
    pub hdr: bool,
    pub auxiliary_tracks: bool,
    pub audio_codec: Option<String>,
    pub audio_channels: u32,
    pub audio_sample_rate: u32,
    pub audio_duration_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct VideoCopyPlan {
    pub start_ms: u64,
    pub end_ms: u64,
    pub width: u32,
    pub height: u32,
    pub bitrate: u32,
    pub required_free_bytes: u64,
}

#[uniffi::export]
pub fn video_copy_plan(
    source: &VideoFacts,
    start_ms: u64,
    end_ms: u64,
) -> Result<VideoCopyPlan, CleanerError> {
    if source.video_codec != "h264" || source.bit_depth != 8 || source.hdr {
        return Err(blocked("This profile accepts 8-bit SDR H.264 video only. HDR, HEVC and other codecs are not converted. The original stays."));
    }
    if source.auxiliary_tracks {
        return Err(blocked("Extra tracks, slow-motion and auxiliary media need a different preservation profile. The original stays."));
    }
    if source.width < 2
        || source.height < 2
        || source.width.max(source.height) > 1920
        || source.width.min(source.height) > 1080
        || !source.frame_rate.is_finite()
        || !(1.0..=30.01).contains(&source.frame_rate)
        || source.duration_ms > 600_000
        || source.bytes == 0
        || source.bytes > 512 * 1024 * 1024
    {
        return Err(blocked(
            "Choose a video up to 1080p, 30 frames per second, 10 minutes and 512 MB.",
        ));
    }
    match source.audio_codec.as_deref() {
        None if source.audio_channels == 0 => {}
        Some("aac") if (1..=2).contains(&source.audio_channels)
            && (8_000..=48_000).contains(&source.audio_sample_rate)
            && source.audio_duration_ms.abs_diff(source.duration_ms) <= 100 => {}
        _ => return Err(blocked("This profile keeps one mono or stereo AAC audio track aligned with the video, or a silent video. Other audio needs a different profile.")),
    }
    if start_ms >= end_ms || end_ms > source.duration_ms || end_ms - start_ms < 500 {
        return Err(blocked(
            "Choose a range inside the video lasting at least half a second.",
        ));
    }
    let scale = (1280.0 / source.width.max(source.height) as f64)
        .min(720.0 / source.width.min(source.height) as f64)
        .min(1.0);
    let even = |n: u32| (((n as f64 * scale).floor() as u32) / 2 * 2).max(2);
    let (width, height) = (even(source.width), even(source.height));
    let bitrate = ((width as u64 * height as u64 * 3_000_000) / (1280 * 720))
        .clamp(400_000, 3_000_000) as u32;
    Ok(VideoCopyPlan {
        start_ms,
        end_ms,
        width,
        height,
        bitrate,
        // Retained original, candidate and verification copy plus encoder/container headroom.
        required_free_bytes: source
            .bytes
            .saturating_mul(3)
            .saturating_add(32 * 1024 * 1024),
    })
}

#[uniffi::export]
pub fn video_copy_issues(
    source: &VideoFacts,
    output: &VideoFacts,
    plan: &VideoCopyPlan,
) -> Vec<String> {
    let mut issues = Vec::new();
    if video_copy_plan(source, plan.start_ms, plan.end_ms).as_ref() != Ok(plan) {
        issues.push("The source or selected range changed. Prepare a new preview.".into());
        return issues;
    }
    if output.width != plan.width || output.height != plan.height {
        issues.push("The upright output dimensions do not match the preview profile.".into());
    }
    if output.video_codec != "h264"
        || output.bit_depth != 8
        || output.hdr
        || output.auxiliary_tracks
    {
        issues.push("The output codec or color profile did not pass.".into());
    }
    if !output.frame_rate.is_finite() || (output.frame_rate - source.frame_rate).abs() > 0.1 {
        issues.push("The output frame rate changed.".into());
    }
    if output.duration_ms.abs_diff(plan.end_ms - plan.start_ms) > 100 {
        issues.push(
            "The output duration differs from the selected range by more than 0.1 second.".into(),
        );
    }
    if output.audio_codec != source.audio_codec
        || output.audio_channels != source.audio_channels
        || output.audio_sample_rate != source.audio_sample_rate
        || (source.audio_codec.is_some()
            && output.audio_duration_ms.abs_diff(output.duration_ms) > 100)
    {
        issues.push("The audio format or timing was not preserved.".into());
    }
    if output.bytes == 0 {
        issues.push("The output is empty.".into());
    }
    issues
}
