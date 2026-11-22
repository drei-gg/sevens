//! The pure rules ported from CleanerPlatform and the review store. CleanerPlatform's own tests
//! exercise PhoneNumberKit, Contacts archives, ImageIO and CryptoKit, which stay native; these
//! cover the logic that moved, following the Swift code line by line.

use cleaner_core::*;
use std::collections::HashMap;
use std::time::{Duration, UNIX_EPOCH};

fn item(id: &str) -> CleanupItem {
    new_cleanup_item(id.into(), id.into(), MediaKind::Photo)
}
fn reason(result: Result<(), CleanerError>) -> String {
    match result {
        Err(CleanerError::Blocked { reason }) => reason,
        other => panic!("expected a block, got {other:?}"),
    }
}

// MARK: Review store

#[test]
fn newer_review_database_is_refused_not_reset() {
    let data = br#"{"version":2,"decisions":{},"revisions":{},"history":[],"processedSources":[]}"#;
    assert_eq!(
        review_state_decode(data),
        Err(CleanerError::Blocked {
            reason: "This review database needs a newer app.".into()
        })
    );
}

#[test]
fn review_file_needs_every_key_like_swift() {
    assert_eq!(
        review_state_decode(br#"{"version":1}"#),
        Err(CleanerError::Unreadable)
    );
}

#[test]
fn deciding_without_a_revision_clears_the_old_one() {
    let state = review_state_decide(
        &new_review_state(),
        HashMap::from([("a".to_string(), ReviewDecision::Remove)]),
        HashMap::from([("a".to_string(), "v1".to_string())]),
    );
    let state = review_state_decide(
        &state,
        HashMap::from([("a".to_string(), ReviewDecision::Keep)]),
        HashMap::new(),
    );
    assert_eq!(state.decisions["a"], ReviewDecision::Keep);
    assert!(!state.revisions.contains_key("a"));
}

#[test]
fn history_is_newest_first_and_processed_sources_are_a_set() {
    let mut state = new_review_state();
    for (message, source) in [
        ("one", Some("b")),
        ("two", Some("a")),
        ("three", Some("b")),
        ("four", None),
    ] {
        state = review_state_record(
            &state,
            new_history_entry(message.into()),
            source.map(String::from),
        );
    }
    let messages: Vec<&str> = state.history.iter().map(|h| h.message.as_str()).collect();
    assert_eq!(messages, ["four", "three", "two", "one"]);
    assert_eq!(state.processed_sources, ["a", "b"]);
}

#[test]
fn removal_guard_blocks_empty_unknown_favorite_and_duplicate() {
    let state = review_state_decide(
        &new_review_state(),
        HashMap::from([
            ("a".to_string(), ReviewDecision::Remove),
            ("b".to_string(), ReviewDecision::Remove),
        ]),
        HashMap::from([
            ("a".to_string(), String::new()),
            ("b".to_string(), String::new()),
        ]),
    );
    assert_eq!(
        reason(validate_removal(vec![], &[item("a")], &state)),
        "Nothing selected."
    );
    let stale = "Selection changed, is protected, or needs review again.";
    assert_eq!(
        reason(validate_removal(vec!["z".into()], &[item("a")], &state)),
        stale
    );
    assert_eq!(
        reason(validate_removal(
            vec!["a".into()],
            &[CleanupItem {
                favorite: true,
                ..item("a")
            }],
            &state
        )),
        stale
    );
    assert_eq!(
        reason(validate_removal(
            vec!["a".into()],
            &[item("a"), item("a")],
            &state
        )),
        stale
    );
    validate_removal(
        vec!["a".into(), "b".into()],
        &[item("a"), item("b")],
        &state,
    )
    .unwrap();
}

// MARK: Matching details

#[test]
fn keeper_prefers_kept_then_pixels_then_earliest_then_id() {
    let at = |s| Some(UNIX_EPOCH + Duration::from_secs(s));
    let items = vec![
        CleanupItem {
            width: 10,
            height: 10,
            date: at(5),
            ..item("small")
        },
        CleanupItem {
            width: 20,
            height: 20,
            date: at(9),
            ..item("late")
        },
        CleanupItem {
            width: 20,
            height: 20,
            date: at(1),
            ..item("early")
        },
        CleanupItem {
            width: 20,
            height: 20,
            ..item("undated")
        },
    ];
    assert_eq!(
        matching_keeper(&items, &HashMap::new()).as_deref(),
        Some("early")
    );
    let kept = HashMap::from([("small".to_string(), ReviewDecision::Keep)]);
    assert_eq!(matching_keeper(&items, &kept).as_deref(), Some("small"));
    let removed = HashMap::from([("early".to_string(), ReviewDecision::Remove)]);
    assert_eq!(matching_keeper(&items, &removed).as_deref(), Some("late"));
}

#[test]
fn exact_groups_are_per_kind_and_sorted() {
    let items = vec![
        CleanupItem {
            fingerprint: Some("f".into()),
            ..item("b")
        },
        CleanupItem {
            fingerprint: Some("f".into()),
            ..item("a")
        },
        CleanupItem {
            fingerprint: Some("f".into()),
            kind: MediaKind::Video,
            ..item("v")
        },
        item("none"),
    ];
    let groups = matching_exact(&items);
    assert_eq!(groups.len(), 1);
    assert_eq!(groups[0].id, "photo:f");
    assert_eq!(groups[0].members, ["a", "b"]);
}

#[test]
fn canonically_equal_contact_values_are_one_value() {
    let a = new_contact_record(
        "A".into(),
        "A".into(),
        "local".into(),
        vec![new_contact_field(
            "givenName".into(),
            "Zo\u{eb}".into(),
            String::new(),
        )],
        vec![],
        vec![],
    );
    let b = new_contact_record(
        "B".into(),
        "B".into(),
        "local".into(),
        vec![new_contact_field(
            "givenName".into(),
            "Zoe\u{308}".into(),
            String::new(),
        )],
        vec![],
        vec![],
    );
    let plan = new_contact_merge_plan(&[a, b], true, true);
    assert_eq!(plan.fields.len(), 1);
    assert!(plan.conflicts.is_empty());
}

#[test]
fn switchboard_numbers_make_no_pairs_but_are_counted() {
    let records: Vec<ContactRecord> = (0..101)
        .map(|i| {
            new_contact_record(
                format!("{i:03}"),
                "x".into(),
                "local".into(),
                vec![],
                vec!["+1".into()],
                vec![],
            )
        })
        .collect();
    assert!(contact_candidates(&records).is_empty());
    assert_eq!(contact_crowded_identifier_count(&records), 1);
}

// MARK: Platform rules

#[test]
fn email_keys_lowercase_only_the_domain() {
    assert_eq!(
        email_key("  Jean.Doe@Example.COM \n".into()).as_deref(),
        Some("Jean.Doe@example.com")
    );
    assert_eq!(email_key("a@b@c".into()), None);
    assert_eq!(email_key("@example.com".into()), None);
    assert_eq!(email_key("jean@".into()), None);
}

#[test]
fn display_name_falls_back_to_organization() {
    assert_eq!(
        contact_display_name("Jean".into(), String::new(), "Test".into(), "Co".into()),
        "Jean Test"
    );
    assert_eq!(
        contact_display_name(String::new(), String::new(), String::new(), "Co".into()),
        "Co"
    );
}

#[test]
fn photo_tags_and_fingerprints() {
    let traits = PhotoTraits {
        screenshot: true,
        burst: true,
        hdr: true,
        ..Default::default()
    };
    assert_eq!(photo_tags(traits), ["Bursts", "HDR", "Screenshots"]);
    let parts = vec![
        photo_resource_part(2, "b".into(), "22".into()),
        photo_resource_part(1, "a".into(), "11".into()),
    ];
    assert_eq!(photo_fingerprint(parts), "1:a:11|2:b:22");
}

#[test]
fn largest_first_keeps_unknown_last_and_ties_in_order() {
    let items = vec![
        item("unknown"),
        CleanupItem {
            bytes: Some(5),
            ..item("five")
        },
        CleanupItem {
            bytes: Some(9),
            ..item("nine")
        },
        CleanupItem {
            bytes: Some(5),
            ..item("five2")
        },
    ];
    let ids: Vec<String> = sort_largest_first(items)
        .into_iter()
        .map(|i| i.id)
        .collect();
    assert_eq!(ids, ["nine", "five", "five2", "unknown"]);
}

#[test]
fn album_request_needs_every_item_and_a_title() {
    validate_album_request(2, 2, "Trip".into()).unwrap();
    assert_eq!(
        reason(validate_album_request(2, 1, "Trip".into())),
        "Choose accessible media and an album name."
    );
    assert!(validate_album_request(2, 2, " \n".into()).is_err());
}

#[test]
fn similarity_window_keeps_sixty_seconds_and_twenty_photos() {
    let at = |s: u64| UNIX_EPOCH + Duration::from_secs(1_700_000_000 + s);
    let window = SimilarityWindow::new();
    assert!(window.prune(at(0)).is_empty());
    window.admit("a".into(), at(0));
    assert_eq!(window.prune(at(60)), ["a"]);
    assert!(window.prune(at(61)).is_empty());
    for i in 0..25 {
        window.prune(at(100));
        window.admit(format!("p{i}"), at(100));
    }
    let recent = window.prune(at(100));
    assert_eq!(recent.len(), 20);
    assert_eq!(recent[0], "p5");
}

#[test]
fn similar_pairs_below_threshold_with_swift_reason() {
    assert!(similar_group("a".into(), "b".into(), 0.2).is_none());
    let group = similar_group("a".into(), "b".into(), 0.1234).unwrap();
    assert_eq!(group.id, "similar:a:b");
    assert_eq!(group.kind, MatchKind::Similar);
    assert_eq!(
        group.reason,
        "Nearby capture times and Vision distance 0.123. Inspect both; different moments may look alike."
    );
}

#[test]
fn similarity_visits_photos_oldest_first() {
    let at = |s| Some(UNIX_EPOCH + Duration::from_secs(s));
    let items = vec![
        CleanupItem {
            date: at(9),
            ..item("late")
        },
        item("undated"),
        CleanupItem {
            kind: MediaKind::Video,
            ..item("video")
        },
        CleanupItem {
            date: at(1),
            ..item("early")
        },
    ];
    let ids: Vec<String> = similarity_order(items).into_iter().map(|i| i.id).collect();
    assert_eq!(ids, ["undated", "early", "late"]);
}

#[test]
fn psnr_weights_samples_and_reports_the_worst_tile() {
    let clean = tile_error(
        &[10, 20, 30, 255, 40, 50, 60, 0],
        &[10, 20, 30, 0, 40, 50, 60, 255],
    )
    .unwrap();
    assert_eq!(
        clean,
        TileError {
            mean_squared_error: 0.0,
            samples: 6
        }
    );
    let damaged = tile_error(&[0, 0, 0, 0], &[255, 255, 255, 0]).unwrap();
    assert_eq!(damaged.mean_squared_error, 65025.0);
    let scores = psnr_scores(&[clean, damaged]);
    assert_eq!(scores.worst_tile, 0.0);
    assert!(
        (scores.full_resolution - 10.0 * (65025.0f64 / (65025.0 * 3.0 / 9.0)).log10()).abs()
            < 1e-12
    );
    assert_eq!(psnr_scores(&[clean]).full_resolution, 120.0);
    let empty = psnr_scores(&[]);
    assert!(empty.full_resolution.is_nan());
    assert_eq!(empty.worst_tile, f64::INFINITY);
    assert!(tile_error(&[1, 2, 3], &[1, 2, 3]).is_err());
}

#[test]
fn compression_profile_gates_in_swift_order() {
    check_compression_source(0.96, 1, "public.jpeg".into(), 4000, 4000).unwrap();
    assert!(check_compression_source(0.96, 1, "public.png".into(), 4001, 4000).is_err());
    assert!(check_compression_source(0.89, 1, "public.jpeg".into(), 10, 10).is_err());
    assert!(check_compression_source(0.96, 2, "public.jpeg".into(), 10, 10).is_err());
    assert!(check_compression_source(0.96, 1, "public.heic".into(), 10, 10).is_err());
    assert!(check_compression_source(0.96, 1, "public.jpeg".into(), 0, 10).is_err());
    check_compression_pixels(8, true, false).unwrap();
    assert!(check_compression_pixels(16, true, false).is_err());
    assert!(check_compression_pixels(8, false, false).is_err());
    assert!(check_compression_pixels(8, true, true).is_err());
}

#[test]
fn only_passing_candidates_are_saved() {
    let facts = new_image_facts(100, 100, 100_000);
    let pass = new_compression_assessment(
        facts.clone(),
        ImageFacts {
            bytes: 10_000,
            ..facts.clone()
        },
        45.0,
        36.0,
        false,
    );
    check_copy_offer(&pass).unwrap();
    assert_eq!(assessment_savings(&pass), 90_000);
    let fail = new_compression_assessment(facts.clone(), facts, 45.0, 36.0, false);
    assert_eq!(
        reason(check_copy_offer(&fail)),
        "This candidate did not pass the current profile."
    );
    assert_eq!(assessment_savings(&fail), 0);
}

#[test]
fn contact_export_read_back_must_match() {
    let record = new_contact_record(
        "A".into(),
        "A".into(),
        "local".into(),
        vec![],
        vec!["+2".into(), "+1".into()],
        vec![],
    );
    let package = new_contact_package(
        vec![record],
        HashMap::from([("A".to_string(), vec![1, 2, 3])]),
    );
    let bytes = contact_package_encode(&package).unwrap();
    contact_package_verify(&package, &bytes).unwrap();
    let other = new_contact_package(
        package.records.clone(),
        HashMap::from([("A".to_string(), vec![1, 2])]),
    );
    assert_eq!(
        reason(contact_package_verify(&other, &bytes)),
        "Archive read-back did not match."
    );
    assert_eq!(
        contact_package_verify(&package, b"{}"),
        Err(CleanerError::Unreadable)
    );
}

// MARK: Perceptual hash (Android's similar-shots pass; additive, iOS uses Vision)

fn thumbnail(f: impl Fn(usize, usize) -> u8) -> Vec<u8> {
    let mut rgba = Vec::with_capacity(32 * 32 * 4);
    for y in 0..32 {
        for x in 0..32 {
            let v = f(x, y);
            rgba.extend_from_slice(&[v, v, v, 255]);
        }
    }
    rgba
}

#[test]
fn perceptual_hash_needs_a_32_pixel_square() {
    assert!(perceptual_hash(&[0u8; 16]).is_err());
    assert!(perceptual_hash(&thumbnail(|_, _| 0)).is_ok());
}

#[test]
fn perceptual_hash_matches_itself_and_tolerates_small_changes() {
    let scene =
        |x: usize, y: usize| ((x * 7 + y * 3) % 256) as u8 / 2 + if x > 16 { 100 } else { 0 };
    let a = perceptual_hash(&thumbnail(scene)).unwrap();
    let brighter = perceptual_hash(&thumbnail(|x, y| scene(x, y).saturating_add(12))).unwrap();
    let noisy = perceptual_hash(&thumbnail(|x, y| scene(x, y) ^ ((x * y) % 3) as u8)).unwrap();
    assert_eq!(perceptual_distance(a, a), 0);
    assert!(perceptual_distance(a, brighter) <= SIMILAR_HASH_DISTANCE);
    assert!(perceptual_distance(a, noisy) <= SIMILAR_HASH_DISTANCE);
}

#[test]
fn perceptual_hash_separates_different_pictures() {
    let left = perceptual_hash(&thumbnail(|x, _| if x < 16 { 230 } else { 20 })).unwrap();
    let top = perceptual_hash(&thumbnail(|_, y| if y < 16 { 230 } else { 20 })).unwrap();
    let inverted = perceptual_hash(&thumbnail(|x, _| if x < 16 { 20 } else { 230 })).unwrap();
    assert!(perceptual_distance(left, top) > SIMILAR_HASH_DISTANCE);
    assert!(perceptual_distance(left, inverted) > SIMILAR_HASH_DISTANCE);
}

#[test]
fn similar_hash_group_is_a_review_only_suggestion_at_the_threshold() {
    let group = similar_hash_group("a".into(), "b".into(), SIMILAR_HASH_DISTANCE).unwrap();
    assert_eq!(group.id, "similar:a:b");
    assert_eq!(group.kind, MatchKind::Similar);
    assert_eq!(group.members, vec!["a".to_string(), "b".to_string()]);
    assert!(group.reason.contains("distance of 10 in 64"));
    assert!(similar_hash_group("a".into(), "b".into(), SIMILAR_HASH_DISTANCE + 1).is_none());
    // Similar groups never get default selections.
    let items = vec![item("a"), item("b")];
    assert!(matching_proposed_removal(&[group], &items, &HashMap::new()).is_empty());
}
