use qr_core::batch::batch_review;

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
