//! What can go wrong, as typed cases the apps word for themselves. Swift's `VaultError` (the same
//! cases in the same order, then the cases format version 2 added), the recognition failures, and
//! [`VaultIssue`], the reasons a plan can't go ahead, with their numbers as fields.
//!
//! Each app words every case through its own string catalog (iOS `Localizable.xcstrings`, Android
//! `strings.xml`), so translations never pass through the core. The English `Display` text here is
//! the fallback and log text only. `*_cases()` list every case for the apps' tests that each one
//! has a localized sentence.

use crate::recognition::RecognitionFailure;

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum VaultError {
    #[error("Use 8–32 digits. Avoid dates and repeated sequences.")]
    WeakPin,
    #[error("Connect at least 8 different dots on the 5×5 grid.")]
    WeakPattern,
    #[error("That didn’t open a vault. Check the PIN, pattern, passphrase or recovery key and try again.")]
    InvalidCredential,
    #[error("Use a passphrase of at least 12 characters.")]
    WeakPassword,
    #[error("The data could not be verified. The existing vault has not been replaced.")]
    Damaged,
    #[error("This format or version is not supported.")]
    Unsupported,
    #[error("Unlock the vault to continue.")]
    Locked,
    #[error(
        "A vault with this identity or unlock secret already exists. Choose another secret; restore never replaces an existing vault."
    )]
    DuplicateVault,
    #[error("Enter a name without path separators, up to 200 characters.")]
    InvalidName,
    #[error("A file is missing. Existing data has been preserved.")]
    MissingFile,
    #[error("There is not enough available storage to finish this operation.")]
    NoSpace,
    /// Added for format version 2 (after Swift's cases, so their order is unchanged): a version 1
    /// backup, which only the iPhone app can read, offered to a version 2 reader.
    #[error(
        "This backup is in an older format made by Vault on iPhone, so it can’t be restored here. Update Vault on the iPhone and back up again."
    )]
    OlderBackup,
    /// Restoring with an unlock secret that already opens another vault here: publishing it would
    /// leave one secret opening two vaults. The recovery key restores it; then choose a new secret.
    #[error(
        "This backup’s unlock secret already opens another vault on this device. Restore it with its recovery key instead; you’ll then choose a new unlock secret."
    )]
    SecretInUse,
}

/// Why a recognition or search plan can't go ahead. The apps show it in place of running the plan.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum VaultIssue {
    /// The PDF has no pages to read.
    NoReadablePages,
    /// The chosen range isn't within the document's pages.
    PageRange { page_count: u32 },
    /// More pages than one recognition reads.
    TooManyPages { maximum: u32 },
    /// The review queue was opened with nothing selected.
    NothingSelected,
    /// More files than one review queue takes.
    TooManyFiles { maximum: u32 },
    /// A selected item is gone or in the trash.
    SelectionChanged,
    /// The selection includes something other than images and PDFs.
    UnsupportedSelection,
    /// A note-text search longer than the limit, in characters.
    QueryTooLong { maximum: u32 },
}

impl std::fmt::Display for VaultIssue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NoReadablePages => write!(f, "This PDF has no readable pages."),
            Self::PageRange { page_count } => write!(f, "Choose a page range from 1 to {page_count}."),
            Self::TooManyPages { maximum } => write!(f, "Choose up to {maximum} pages at a time."),
            Self::NothingSelected => write!(f, "Select images or PDFs to recognize."),
            Self::TooManyFiles { maximum } => write!(f, "Select up to {maximum} files per review queue."),
            Self::SelectionChanged => write!(f, "The selection changed. Select the files again."),
            Self::UnsupportedSelection => {
                write!(f, "Select only images and PDFs. Notes and other files cannot be recognized.")
            }
            Self::QueryTooLong { maximum } => write!(f, "Use up to {maximum} characters to search note text."),
        }
    }
}

/// English text for an error, for logs and as the fallback where an app has no localized string.
#[uniffi::export]
pub fn vault_error_description(error: VaultError) -> String {
    error.to_string()
}

/// English text for an issue, for logs and as the fallback where an app has no localized string.
#[uniffi::export]
pub fn vault_issue_description(issue: VaultIssue) -> String {
    issue.to_string()
}

/// Every [`VaultError`], in declaration order.
#[uniffi::export]
pub fn vault_error_cases() -> Vec<VaultError> {
    use VaultError::*;
    vec![
        WeakPin,
        WeakPattern,
        InvalidCredential,
        WeakPassword,
        Damaged,
        Unsupported,
        Locked,
        DuplicateVault,
        InvalidName,
        MissingFile,
        NoSpace,
        OlderBackup,
        SecretInUse,
    ]
}

/// Every [`RecognitionFailure`].
#[uniffi::export]
pub fn recognition_failure_cases() -> Vec<RecognitionFailure> {
    vec![RecognitionFailure::TooMuchText, RecognitionFailure::InvalidPage]
}

/// One of each [`VaultIssue`], with the numbers the core actually uses.
#[uniffi::export]
pub fn vault_issue_cases() -> Vec<VaultIssue> {
    use VaultIssue::*;
    vec![
        NoReadablePages,
        PageRange { page_count: 21 },
        TooManyPages { maximum: crate::recognition::MAXIMUM_PAGES },
        NothingSelected,
        TooManyFiles { maximum: crate::recognition::MAXIMUM_ITEMS },
        SelectionChanged,
        UnsupportedSelection,
        QueryTooLong { maximum: crate::search::MAXIMUM_QUERY_CHARACTERS },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    // Each list must name every case: a new case fails to compile in the match until it gets a
    // slot, and fails the test until the list includes it.
    #[test]
    fn case_lists_are_complete() {
        let mut seen = [false; 13];
        for case in vault_error_cases() {
            seen[match case {
                VaultError::WeakPin => 0,
                VaultError::WeakPattern => 1,
                VaultError::InvalidCredential => 2,
                VaultError::WeakPassword => 3,
                VaultError::Damaged => 4,
                VaultError::Unsupported => 5,
                VaultError::Locked => 6,
                VaultError::DuplicateVault => 7,
                VaultError::InvalidName => 8,
                VaultError::MissingFile => 9,
                VaultError::NoSpace => 10,
                VaultError::OlderBackup => 11,
                VaultError::SecretInUse => 12,
            }] = true;
        }
        assert!(seen.iter().all(|s| *s));

        let mut seen = [false; 2];
        for case in recognition_failure_cases() {
            seen[match case {
                RecognitionFailure::TooMuchText => 0,
                RecognitionFailure::InvalidPage => 1,
            }] = true;
        }
        assert!(seen.iter().all(|s| *s));

        let mut seen = [false; 8];
        for case in vault_issue_cases() {
            seen[match case {
                VaultIssue::NoReadablePages => 0,
                VaultIssue::PageRange { .. } => 1,
                VaultIssue::TooManyPages { .. } => 2,
                VaultIssue::NothingSelected => 3,
                VaultIssue::TooManyFiles { .. } => 4,
                VaultIssue::SelectionChanged => 5,
                VaultIssue::UnsupportedSelection => 6,
                VaultIssue::QueryTooLong { .. } => 7,
            }] = true;
        }
        assert!(seen.iter().all(|s| *s));
    }

    #[test]
    fn fallback_text_carries_the_numbers() {
        assert_eq!(VaultIssue::PageRange { page_count: 7 }.to_string(), "Choose a page range from 1 to 7.");
        assert_eq!(vault_issue_description(VaultIssue::TooManyPages { maximum: 20 }), "Choose up to 20 pages at a time.");
        assert_eq!(VaultIssue::TooManyFiles { maximum: 10 }.to_string(), "Select up to 10 files per review queue.");
        assert_eq!(VaultIssue::QueryTooLong { maximum: 200 }.to_string(), "Use up to 200 characters to search note text.");
    }
}
