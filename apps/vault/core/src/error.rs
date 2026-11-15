//! Swift's `VaultError`, the same cases in the same order, with its user-facing descriptions.

#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum VaultError {
    #[error("Use 8–32 digits. Avoid dates and repeated sequences.")]
    WeakPin,
    #[error("Connect at least 8 different dots on the 5×5 grid.")]
    WeakPattern,
    #[error("That credential did not open a vault. Check the passphrase or recovery key.")]
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
}

/// The user-facing text for an error (Swift's `errorDescription`).
#[uniffi::export]
pub fn vault_error_description(error: VaultError) -> String {
    error.to_string()
}
