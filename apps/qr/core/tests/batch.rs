use qr_core::batch::{BatchIssue, batch_review};

#[test]
fn batch_preserves_exact_payloads_duplicates_and_order() {
    let result =
        batch_review("\r\n https://example.com/?utm_source=label \r\n\t\nनमस्ते\nनमस्ते\n".into());
    assert_eq!(result.issue, None);
    assert_eq!(
        result.items,
        vec![" https://example.com/?utm_source=label ", "नमस्ते", "नमस्ते"]
    );
}

#[test]
fn invalid_batches_never_return_a_partial_export() {
    for input in [
        " \n\r\n".into(),
        "x\n".repeat(101),
        format!("good\n{}", "a".repeat(8193)),
        "a".repeat(262145),
    ] {
        let result = batch_review(input);
        assert!(result.issue.is_some());
        assert!(result.items.is_empty());
    }
    assert_eq!(batch_review("x\n".repeat(100)).items.len(), 100);
}

#[test]
fn issues_carry_a_kind_and_its_numbers() {
    let kind = |input: String| batch_review(input).issue_kind;
    assert_eq!(kind(" \n\r\n".into()), Some(BatchIssue::Empty));
    assert_eq!(
        kind("x\n".repeat(101)),
        Some(BatchIssue::TooManyLines { max: 100 })
    );
    assert_eq!(
        kind(format!("good\n\n{}", "a".repeat(8193))),
        Some(BatchIssue::LineTooLong { line: 3 })
    );
    assert_eq!(
        kind("a".repeat(262145)),
        Some(BatchIssue::TooLarge { max_kilobytes: 256 })
    );
    assert_eq!(kind("ok".into()), None);
}

#[test]
fn the_english_message_stays_for_older_callers() {
    let r = batch_review("\n".into());
    assert_eq!(
        r.issue.as_deref(),
        Some("Enter one code’s text or link per line.")
    );
    assert!(!r.issue.unwrap().contains('\''));
    assert_eq!(
        batch_review(format!("{}", "a".repeat(8193)))
            .issue
            .as_deref(),
        Some("Line 1 is too long for a code.")
    );
}
