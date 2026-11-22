//! Bounded, explicit batch inputs. Never rewrite links or silently export a partial batch.

/// The most bytes of text a batch takes.
pub const BATCH_MAX_BYTES: usize = 262_144;
/// The most nonblank lines a batch takes.
pub const BATCH_MAX_LINES: usize = 100;
/// The longest line, in UTF-8 bytes.
pub const BATCH_MAX_LINE_BYTES: usize = 8_192;

/// Why a batch can't be exported, with what the sentence needs, so each platform words it in
/// its own strings.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum BatchIssue {
    /// More than `max_kilobytes` KB of text.
    TooLarge { max_kilobytes: i64 },
    /// Line `line` (1-based, counting blank lines) is longer than a code holds.
    LineTooLong { line: i64 },
    /// More than `max` nonblank lines.
    TooManyLines { max: i64 },
    /// No nonblank line.
    Empty,
}

impl BatchIssue {
    /// The English sentence, as `BatchReview.issue` has always carried it.
    pub fn message(&self) -> String {
        match self {
            Self::TooLarge { max_kilobytes } => {
                format!("The batch is too large. Use at most {max_kilobytes} KB of text.")
            }
            Self::LineTooLong { line } => format!("Line {line} is too long for a code."),
            Self::TooManyLines { max } => format!("Use at most {max} nonblank lines per batch."),
            Self::Empty => "Enter one code’s text or link per line.".into(),
        }
    }
}

#[derive(Debug, PartialEq, uniffi::Record)]
pub struct BatchReview {
    pub items: Vec<String>,
    /// English; kept for older callers. New code words `issue_kind` itself.
    pub issue: Option<String>,
    /// Set exactly when `issue` is.
    #[uniffi(default = None)]
    pub issue_kind: Option<BatchIssue>,
}

#[uniffi::export]
pub fn batch_review(text: String) -> BatchReview {
    let invalid = |kind: BatchIssue| BatchReview {
        items: vec![],
        issue: Some(kind.message()),
        issue_kind: Some(kind),
    };
    if text.len() > BATCH_MAX_BYTES {
        return invalid(BatchIssue::TooLarge {
            max_kilobytes: (BATCH_MAX_BYTES / 1024) as i64,
        });
    }
    let mut items = vec![];
    for (index, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        if line.len() > BATCH_MAX_LINE_BYTES {
            return invalid(BatchIssue::LineTooLong {
                line: index as i64 + 1,
            });
        }
        items.push(line.to_owned());
        if items.len() > BATCH_MAX_LINES {
            return invalid(BatchIssue::TooManyLines {
                max: BATCH_MAX_LINES as i64,
            });
        }
    }
    if items.is_empty() {
        return invalid(BatchIssue::Empty);
    }
    BatchReview {
        items,
        issue: None,
        issue_kind: None,
    }
}
