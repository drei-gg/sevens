use cleaner_core::*;

fn source() -> VideoFacts {
    VideoFacts {
        width: 1920,
        height: 1080,
        duration_ms: 10_000,
        frame_rate: 30.0,
        bytes: 8_000_000,
        video_codec: "h264".into(),
        bit_depth: 8,
        hdr: false,
        auxiliary_tracks: false,
        audio_codec: Some("aac".into()),
        audio_channels: 2,
        audio_sample_rate: 48_000,
        audio_duration_ms: 10_000,
    }
}

#[test]
fn upright_profiles_never_enlarge_and_keep_even_dimensions() {
    for (w, h, expected) in [
        (1920, 1080, (1280, 720)),
        (1080, 1920, (720, 1280)),
        (640, 480, (640, 480)),
        (1920, 800, (1280, 532)),
    ] {
        let mut s = source();
        s.width = w;
        s.height = h;
        let p = video_copy_plan(&s, 1250, 8750).unwrap();
        assert_eq!((p.width, p.height), expected);
        assert_eq!(p.end_ms - p.start_ms, 7500);
        assert!(p.required_free_bytes > s.bytes * 3);
    }
}

#[test]
fn unsupported_color_tracks_audio_and_unbounded_inputs_are_refused() {
    let s = source();
    let mut cases = vec![];
    let mut x = s.clone();
    x.hdr = true;
    cases.push(x);
    let mut x = s.clone();
    x.bit_depth = 10;
    cases.push(x);
    let mut x = s.clone();
    x.video_codec = "hevc".into();
    cases.push(x);
    let mut x = s.clone();
    x.auxiliary_tracks = true;
    cases.push(x);
    let mut x = s.clone();
    x.audio_channels = 6;
    cases.push(x);
    let mut x = s.clone();
    x.audio_duration_ms = 9800;
    cases.push(x);
    let mut x = s.clone();
    x.frame_rate = f64::NAN;
    cases.push(x);
    let mut x = s.clone();
    x.frame_rate = 60.0;
    cases.push(x);
    let mut x = s.clone();
    x.bytes = 513 * 1024 * 1024;
    cases.push(x);
    let mut x = s.clone();
    x.duration_ms = 600001;
    cases.push(x);
    for x in cases {
        assert!(video_copy_plan(&x, 0, 5000).is_err());
    }
    for (a, b) in [(0, 499), (5000, 5000), (8000, 7000), (0, 10001)] {
        assert!(video_copy_plan(&s, a, b).is_err());
    }
}

#[test]
fn output_checks_actual_geometry_audio_duration_and_color() {
    let s = source();
    let p = video_copy_plan(&s, 1250, 8750).unwrap();
    let mut o = s.clone();
    o.width = p.width;
    o.height = p.height;
    o.duration_ms = 7500;
    o.audio_duration_ms = 7500;
    o.bytes = 1_000_000;
    assert!(video_copy_issues(&s, &o, &p).is_empty());
    let mut bad = o.clone();
    bad.audio_codec = None;
    assert!(!video_copy_issues(&s, &bad, &p).is_empty());
    let mut bad = o.clone();
    bad.duration_ms = 7700;
    assert!(!video_copy_issues(&s, &bad, &p).is_empty());
    let mut bad = o.clone();
    bad.width = 720;
    assert!(!video_copy_issues(&s, &bad, &p).is_empty());
    let mut bad = o.clone();
    bad.hdr = true;
    assert!(!video_copy_issues(&s, &bad, &p).is_empty());
    let mut stale = p.clone();
    stale.width = 640;
    assert!(!video_copy_issues(&s, &o, &stale).is_empty());
}

#[test]
fn silent_video_stays_silent_and_larger_outputs_are_honest_not_deletion_authority() {
    let mut s = source();
    s.audio_codec = None;
    s.audio_channels = 0;
    s.audio_sample_rate = 0;
    s.audio_duration_ms = 0;
    let p = video_copy_plan(&s, 0, 10000).unwrap();
    let mut o = s.clone();
    o.width = p.width;
    o.height = p.height;
    o.bytes = s.bytes + 100;
    assert!(video_copy_issues(&s, &o, &p).is_empty());
    o.audio_codec = Some("aac".into());
    assert!(!video_copy_issues(&s, &o, &p).is_empty());
}

#[test]
fn source_guards_reject_repeated_lossy_processing_and_photo_inputs() {
    let mut item = new_cleanup_item("video".into(), "clip.mp4".into(), MediaKind::Video);
    item.revision = "revision-1".into();
    let mut state = new_review_state();
    assert!(check_video_copy_source(&item, &state).is_ok());
    state.processed_sources.push(compression_source_key(&item));
    assert!(check_video_copy_source(&item, &state).is_err());
    item.revision = "revision-2".into();
    assert!(check_video_copy_source(&item, &state).is_ok());
    state.processed_sources.push("output:video".into());
    assert!(check_video_copy_source(&item, &state).is_err());
    item.kind = MediaKind::Photo;
    assert!(check_video_copy_source(&item, &new_review_state()).is_err());
}
