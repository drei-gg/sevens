//! Swift's `VaultError`, the same cases in the same order, with its user-facing descriptions, and
//! the cases format version 2 added after them.

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
        "This backup’s unlock secret already opens another vault on this device. Restore it with its recovery key instead, then choose a new unlock secret in Settings."
    )]
    SecretInUse,
}

/// The user-facing text for an error (Swift's `errorDescription`).
#[uniffi::export]
pub fn vault_error_description(error: VaultError) -> String {
    error.to_string()
}
