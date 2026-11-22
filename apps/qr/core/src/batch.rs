//! Bounded, explicit batch inputs. Never rewrite links or silently export a partial batch.

#[derive(Debug, PartialEq, uniffi::Record)]
pub struct BatchReview {
    pub items: Vec<String>,
    pub issue: Option<String>,
}

#[uniffi::export]
pub fn batch_review(text: String) -> BatchReview {
    let invalid = |message: String| BatchReview {
        items: vec![],
        issue: Some(message),
    };
    if text.len() > 262_144 {
        return invalid("The batch is too large. Use at most 256 KB of text.".into());
    }
    let mut items = vec![];
    for (index, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        if line.len() > 8_192 {
            return invalid(format!("Line {} is too long for a code.", index + 1));
        }
        items.push(line.to_owned());
        if items.len() > 100 {
            return invalid("Use at most 100 nonblank lines per batch.".into());
        }
    }
    if items.is_empty() {
        return invalid("Enter one code's text or link per line.".into());
    }
    BatchReview { items, issue: None }
}
