//! Per-item removal outcomes, keeper reasons, media filters and similar-shot groups.

use cleaner_core::*;
use std::collections::HashMap;
use std::time::{Duration, UNIX_EPOCH};

fn item(id: &str) -> CleanupItem {
    CleanupItem {
        revision: "v1".into(),
        ..new_cleanup_item(id.into(), id.into(), MediaKind::Photo)
    }
}

fn queued(ids: &[&str]) -> ReviewState {
    review_state_decide(
        &new_review_state(),
        ids.iter()
            .map(|id| (id.to_string(), ReviewDecision::Remove))
            .collect(),
        ids.iter()
            .map(|id| (id.to_string(), "v1".to_string()))
            .collect(),
    )
}

fn status(outcomes: &[RemovalOutcome], id: &str) -> RemovalStatus {
    outcomes.iter().find(|o| o.id == id).unwrap().status
}

#[test]
fn one_changed_item_is_named_and_the_rest_stay_ready() {
    let state = queued(&["a", "b", "c", "d"]);
    let mut kept = item("c");
    kept.favorite = true;
    let changed = CleanupItem {
        revision: "v2".into(),
        ..item("b")
    };
    let items = vec![item("a"), changed, kept];
    let ids: Vec<String> = ["a", "b", "c", "d", "e", "a"].map(String::from).to_vec();
    let mut unmarked = items.clone();
    unmarked.push(item("e"));
    let outcomes = removal_outcomes(ids, &unmarked, &state);
    assert_eq!(outcomes.len(), 5, "duplicates are reported once");
    assert_eq!(status(&outcomes, "a"), RemovalStatus::Ready);
    assert_eq!(status(&outcomes, "b"), RemovalStatus::Changed);
    assert_eq!(status(&outcomes, "c"), RemovalStatus::Favorite);
    assert_eq!(status(&outcomes, "d"), RemovalStatus::Missing);
    assert_eq!(status(&outcomes, "e"), RemovalStatus::NotMarked);
    assert_eq!(removal_ready_ids(&outcomes), vec!["a".to_string()]);
    // The batch guard still refuses the whole set, and accepts only the ready subset.
    assert!(validate_removal(vec!["a".into(), "b".into()], &unmarked, &state).is_err());
    validate_removal(removal_ready_ids(&outcomes), &unmarked, &state).unwrap();
}

#[test]
fn a_provider_listing_an_id_twice_is_never_ready() {
    let state = queued(&["a"]);
    let outcomes = removal_outcomes(vec!["a".into()], &[item("a"), item("a")], &state);
    assert_eq!(status(&outcomes, "a"), RemovalStatus::Missing);
}

#[test]
fn reconfirming_at_the_fresh_revision_makes_it_ready_again() {
    let state = queued(&["a"]);
    let fresh = CleanupItem {
        revision: "v2".into(),
        ..item("a")
    };
    let before = removal_outcomes(vec!["a".into()], std::slice::from_ref(&fresh), &state);
    assert_eq!(status(&before, "a"), RemovalStatus::Changed);
    let again = review_state_decide(
        &state,
        HashMap::from([("a".to_string(), ReviewDecision::Remove)]),
        HashMap::from([("a".to_string(), "v2".to_string())]),
    );
    let after = removal_outcomes(vec!["a".into()], &[fresh], &again);
    assert_eq!(status(&after, "a"), RemovalStatus::Ready);
}

#[test]
fn held_message_counts_without_blaming_a_generic_selection() {
    let ready = RemovalOutcome {
        id: "a".into(),
        status: RemovalStatus::Ready,
    };
    let changed = RemovalOutcome {
        id: "b".into(),
        status: RemovalStatus::Changed,
    };
    assert_eq!(removal_held_message(std::slice::from_ref(&ready)), None);
    assert_eq!(
        removal_held_message(&[ready.clone(), changed.clone()]).unwrap(),
        "1 item changed, became a favorite or is no longer available, so it was kept. Review them again in Pending removal."
    );
    let two = RemovalOutcome {
        id: "c".into(),
        status: RemovalStatus::Favorite,
    };
    assert_eq!(
        removal_held_message(&[changed, two]).unwrap(),
        "2 items changed, became favorites or are no longer available. Nothing was deleted. Review them again in Pending removal."
    );
}

#[test]
fn keeper_suggestion_names_the_deciding_rule() {
    let at = |s| Some(UNIX_EPOCH + Duration::from_secs(s));
    let big = CleanupItem {
        width: 40,
        height: 30,
        date: at(9),
        ..item("big")
    };
    let early = CleanupItem {
        width: 20,
        height: 20,
        date: at(1),
        ..item("early")
    };
    let late = CleanupItem {
        width: 20,
        height: 20,
        date: at(5),
        ..item("late")
    };
    let none = HashMap::new();
    let pick = |items: &[CleanupItem], d: &HashMap<String, ReviewDecision>| {
        keeper_suggestion(items, d).unwrap()
    };
    let s = pick(&[early.clone(), big.clone()], &none);
    assert_eq!(s.id, "big");
    assert_eq!(s.reason, "Suggested keeper: the most pixels.");
    let s = pick(&[late.clone(), early.clone()], &none);
    assert_eq!(s.id, "early");
    assert_eq!(s.reason, "Suggested keeper: the earliest capture date.");
    let fav = CleanupItem {
        favorite: true,
        ..late.clone()
    };
    assert_eq!(
        pick(&[big.clone(), fav], &none).reason,
        "Suggested keeper: it’s a favorite."
    );
    let kept = HashMap::from([("late".to_string(), ReviewDecision::Keep)]);
    assert_eq!(
        pick(&[big.clone(), late.clone()], &kept).reason,
        "Suggested keeper: you chose to keep it."
    );
    let removed = HashMap::from([("big".to_string(), ReviewDecision::Remove)]);
    let s = pick(&[big.clone(), late.clone()], &removed);
    assert_eq!(s.id, "late");
    assert_eq!(
        s.reason,
        "Suggested keeper: the others are queued for removal."
    );
    let twin = CleanupItem {
        id: "twin".into(),
        ..early.clone()
    };
    assert_eq!(
        pick(&[twin, early.clone()], &none).reason,
        "Suggested keeper: the same size and date as another, chosen by name."
    );
    let all_removed = HashMap::from([
        ("big".to_string(), ReviewDecision::Remove),
        ("late".to_string(), ReviewDecision::Remove),
    ]);
    assert!(keeper_suggestion(&[big, late], &all_removed).is_none());
}

#[test]
fn large_lists_largest_first_from_its_minimum() {
    let sized = |id: &str, bytes: Option<i64>| CleanupItem { bytes, ..item(id) };
    let items = vec![
        sized("small", Some(4_000_000)),
        sized("tie-a", Some(12_000_000)),
        sized("unknown", None),
        sized("huge", Some(80_000_000)),
        sized("tie-b", Some(12_000_000)),
        sized("edge", Some(10_000_000)),
    ];
    let ids = |list: Vec<CleanupItem>| list.into_iter().map(|i| i.id).collect::<Vec<_>>();
    assert_eq!(
        ids(large_items(items.clone(), 10_000_000)),
        ["huge", "tie-a", "tie-b", "edge"]
    );
    assert_eq!(
        ids(large_items(items.clone(), 2_000_000)),
        ["huge", "tie-a", "tie-b", "edge", "small"]
    );
    assert_eq!(ids(large_items(items, 100_000_000)), Vec::<String>::new());
}

#[test]
fn media_filters_never_treat_unknown_as_zero() {
    let photo = |bytes: Option<i64>, w, h| CleanupItem {
        bytes,
        width: w,
        height: h,
        ..item("p")
    };
    assert!(!media_is_large(&photo(None, 0, 0)));
    assert!(!media_is_large(&photo(Some(9_999_999), 0, 0)));
    assert!(media_is_large(&photo(Some(10_000_000), 0, 0)));
    let video = CleanupItem {
        kind: MediaKind::Video,
        bytes: Some(50_000_000),
        ..item("v")
    };
    assert!(!media_is_large(&video), "videos need 100 MB");

    // An adjustable minimum: still never counts an unknown size, and a known empty file is not
    // large even at the smallest minimum.
    assert!(media_is_large_from(&video, 50_000_000));
    assert!(!media_is_large_from(&photo(None, 0, 0), 0));
    assert!(!media_is_large_from(&photo(Some(0), 0, 0), 0));
    assert!(media_is_large_from(&photo(Some(1), 0, 0), -5));
    assert_eq!(large_minimum_default(MediaKind::Photo), 10_000_000);
    assert_eq!(large_minimum_default(MediaKind::Video), 100_000_000);
    for kind in [MediaKind::Photo, MediaKind::Video] {
        let choices = large_minimum_choices(kind);
        assert!(choices.contains(&large_minimum_default(kind)));
        assert!(choices.windows(2).all(|w| w[0] < w[1]));
    }

    assert!(media_resolution_matches(
        2160,
        3840,
        MediaResolutionFilter::AtLeast2160
    ));
    assert!(media_resolution_matches(
        1920,
        1080,
        MediaResolutionFilter::AtLeast1080
    ));
    assert!(!media_resolution_matches(
        1920,
        1079,
        MediaResolutionFilter::AtLeast1080
    ));
    assert!(media_resolution_matches(
        640,
        480,
        MediaResolutionFilter::Below720
    ));
    assert!(!media_resolution_matches(
        0,
        0,
        MediaResolutionFilter::Below720
    ));
    assert!(media_resolution_matches(
        0,
        480,
        MediaResolutionFilter::Unknown
    ));

    assert!(media_duration_matches(
        59.9,
        MediaDurationFilter::Under1Minute
    ));
    assert!(!media_duration_matches(
        0.0,
        MediaDurationFilter::Under1Minute
    ));
    assert!(!media_duration_matches(
        f64::NAN,
        MediaDurationFilter::Under1Minute
    ));
    assert!(media_duration_matches(0.0, MediaDurationFilter::Unknown));
    assert!(media_duration_matches(
        300.0,
        MediaDurationFilter::AtLeast5Minutes
    ));

    let filters = MediaFilters {
        size: FileSizeFilter::Unknown,
        ..media_filters_any()
    };
    assert!(media_filters_match(&photo(None, 10, 10), filters));
    assert!(!media_filters_match(&photo(Some(0), 10, 10), filters));
    let long = MediaFilters {
        duration: MediaDurationFilter::AtLeast30Minutes,
        ..media_filters_any()
    };
    assert!(
        media_filters_match(&photo(Some(1), 10, 10), long),
        "duration applies to videos only"
    );
    assert!(!media_filters_match(&video, long));
}

fn edge(a: &str, b: &str, d: f32) -> SimilarEdge {
    SimilarEdge {
        earlier: a.into(),
        later: b.into(),
        distance: d,
    }
}

#[test]
fn a_burst_becomes_one_group_not_overlapping_pairs() {
    let order: Vec<String> = ["a", "b", "c", "d", "x"].map(String::from).to_vec();
    let edges = vec![
        edge("a", "b", 0.1),
        edge("b", "c", 0.05),
        edge("a", "c", 0.15),
        edge("c", "d", 0.19),
        edge("d", "x", 0.2),
    ];
    let groups = similar_clusters(order, edges, SimilarityMeasure::Vision);
    assert_eq!(groups.len(), 1);
    assert_eq!(groups[0].members, vec!["a", "b", "c", "d"]);
    assert_eq!(groups[0].id, "similar:a");
    assert_eq!(groups[0].kind, MatchKind::Similar);
    assert_eq!(
        groups[0].reason,
        "Nearby capture times. Each photo is within Vision distance 0.190 of another here. Inspect each; different moments may look alike."
    );
}

#[test]
fn a_pair_keeps_the_pair_wording_and_groups_follow_visit_order() {
    let order: Vec<String> = ["p", "q", "r", "s"].map(String::from).to_vec();
    let groups = similar_clusters(
        order,
        vec![
            edge("r", "s", 3.0),
            edge("p", "q", 10.0),
            edge("q", "r", 11.0),
        ],
        SimilarityMeasure::ImageHash,
    );
    assert_eq!(groups.len(), 2);
    assert_eq!(groups[0].members, vec!["p", "q"]);
    assert_eq!(
        groups[0].reason,
        "Nearby capture times and an image-hash distance of 10 in 64. Inspect both; different moments may look alike."
    );
    assert_eq!(groups[1].members, vec!["r", "s"]);
}

#[test]
fn groups_stop_growing_at_the_limit() {
    let n = SIMILAR_GROUP_LIMIT * 2 + 3;
    let order: Vec<String> = (0..n).map(|i| format!("p{i:03}")).collect();
    let edges: Vec<SimilarEdge> = (1..n)
        .map(|i| edge(&order[i - 1], &order[i], 0.01))
        .collect();
    let groups = similar_clusters(order.clone(), edges, SimilarityMeasure::Vision);
    assert!(
        groups
            .iter()
            .all(|g| g.members.len() <= SIMILAR_GROUP_LIMIT)
    );
    let total: usize = groups.iter().map(|g| g.members.len()).sum();
    assert_eq!(total, n, "every linked photo still appears in a group");
    let mut seen = std::collections::HashSet::new();
    assert!(
        groups
            .iter()
            .flat_map(|g| &g.members)
            .all(|m| seen.insert(m.clone())),
        "groups never overlap"
    );
}

#[test]
fn the_collector_keeps_only_qualifying_pairs() {
    let clusters = SimilarClusters::new(SimilarityMeasure::Vision);
    for id in ["a", "b", "c"] {
        clusters.visit(id.into());
    }
    assert!(clusters.link("a".into(), "b".into(), 0.1));
    assert!(!clusters.link("b".into(), "c".into(), 0.25));
    let groups = clusters.groups();
    assert_eq!(groups.len(), 1);
    assert_eq!(groups[0].members, vec!["a", "b"]);
}

#[test]
fn still_compression_needs_room_for_candidate_and_readback() {
    let need = still_copy_required_free_bytes(4000, 3000);
    assert_eq!(need, 4000 * 3000 * 3 * 2 + 32 * 1024 * 1024);
    assert!(check_still_copy_space(4000, 3000, need).is_ok());
    assert!(matches!(
        check_still_copy_space(4000, 3000, need - 1),
        Err(CleanerError::Blocked { .. })
    ));
    assert!(
        check_still_copy_space(1, 1, 0).is_err(),
        "unknown free space refuses"
    );
}

#[test]
fn android_albums_are_single_safe_folders_under_pictures() {
    assert_eq!(
        album_relative_path("  Trip 2026 ".into()).unwrap(),
        "Pictures/Trip 2026/"
    );
    assert_eq!(album_folder_name("Café ’26".into()).unwrap(), "Café ’26");
    for bad in [
        "",
        "   ",
        ".",
        "..",
        ".hidden",
        "a/b",
        "a\\b",
        "a:b",
        "line\nbreak",
        "q?",
    ] {
        assert!(
            album_folder_name(bad.into()).is_err(),
            "{bad:?} should be refused"
        );
    }
    assert!(album_folder_name("x".repeat(101)).is_err());
    assert!(album_folder_name("x".repeat(100)).is_ok());
}
