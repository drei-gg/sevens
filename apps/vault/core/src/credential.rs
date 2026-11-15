//! Unlock credentials: passphrase, PIN and 5×5 pattern rules, their domain-separated key
//! material, and pattern canonicalization (Swift `VaultCredentialKind` and `PatternPath`).

use crate::error::VaultError;
use crate::text::characters;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum VaultCredentialKind {
    Passphrase,
    Pin,
    Pattern,
}

impl VaultCredentialKind {
    pub const ALL: [VaultCredentialKind; 3] = [Self::Passphrase, Self::Pin, Self::Pattern];

    pub fn title(self) -> &'static str {
        match self {
            Self::Passphrase => "Passphrase",
            Self::Pin => "PIN",
            Self::Pattern => "Pattern",
        }
    }

    pub fn validate(self, value: &str) -> Result<(), VaultError> {
        match self {
            Self::Passphrase => {
                if characters(value) < 12 || value.len() > 4096 {
                    return Err(VaultError::WeakPassword);
                }
            }
            Self::Pin => {
                if !(8..=32).contains(&characters(value)) || !value.bytes().all(|b| b.is_ascii_digit()) {
                    return Err(VaultError::WeakPin);
                }
            }
            Self::Pattern => match pattern_decode(value.to_string()) {
                Some(path) if path.len() >= 8 => {}
                _ => return Err(VaultError::WeakPattern),
            },
        }
        Ok(())
    }

    /// Domain separation keeps a typed passphrase from impersonating a PIN or pattern.
    pub fn material(self, value: &str) -> String {
        match self {
            Self::Passphrase => value.to_string(),
            Self::Pin => format!("Vault/PIN/v1/{value}"),
            Self::Pattern => format!("Vault/Pattern/5/v1/{value}"),
        }
    }
}

#[uniffi::export]
pub fn credential_kind_title(kind: VaultCredentialKind) -> String {
    kind.title().to_string()
}

#[uniffi::export]
pub fn credential_kind_validate(kind: VaultCredentialKind, value: String) -> Result<(), VaultError> {
    kind.validate(&value)
}

/// The string fed to key derivation for `value` typed as `kind`.
#[uniffi::export]
pub fn credential_kind_material(kind: VaultCredentialKind, value: String) -> String {
    kind.material(&value)
}

/// The pattern grid's side (`PatternPath.side`).
pub const PATTERN_SIDE: i32 = 5;

#[uniffi::export]
pub fn pattern_encode(path: Vec<i32>) -> String {
    path.iter().map(|d| d.to_string()).collect::<Vec<_>>().join(",")
}

/// A stored pattern back to its dots, or `None` unless it is canonical: decimal dots 0–24 with no
/// repeats, no padding, and every crossed collinear dot already included.
#[uniffi::export]
pub fn pattern_decode(value: String) -> Option<Vec<i32>> {
    let parts: Vec<&str> = value.split(',').collect();
    // Swift's `Int(_:)`: an optional sign and ASCII digits, 64-bit.
    let numbers: Vec<i64> = parts.iter().filter_map(|p| p.parse::<i64>().ok()).collect();
    if numbers.is_empty() || numbers.len() != parts.len() || numbers.len() > 25 {
        return None;
    }
    if numbers.iter().collect::<HashSet<_>>().len() != numbers.len() || !numbers.iter().all(|d| (0..25).contains(d)) {
        return None;
    }
    let path: Vec<i32> = numbers.iter().map(|&d| d as i32).collect();
    if pattern_encode(path.clone()) != value {
        return None;
    }
    let mut canonical = vec![];
    for &dot in &path {
        pattern_push(dot, &mut canonical);
    }
    (canonical == path).then_some(path)
}

/// Fill crossed collinear dots so sampling frequency cannot change the credential.
pub fn pattern_push(dot: i32, path: &mut Vec<i32>) {
    if !(0..25).contains(&dot) || path.contains(&dot) {
        return;
    }
    if let Some(&last) = path.last() {
        let side = PATTERN_SIDE;
        let (dx, dy) = (dot % side - last % side, dot / side - last / side);
        let (mut a, mut b) = (dx.abs(), dy.abs());
        while b != 0 {
            (a, b) = (b, a % b);
        }
        if a > 1 {
            for step in 1..a {
                let middle = (last / side + dy / a * step) * side + last % side + dx / a * step;
                if !path.contains(&middle) {
                    path.push(middle);
                }
            }
        }
    }
    path.push(dot);
}

/// `PatternPath.append(_:to:)`: `path` with `dot` (and any dots it crosses) appended.
#[uniffi::export]
pub fn pattern_append(dot: i32, path: Vec<i32>) -> Vec<i32> {
    let mut path = path;
    pattern_push(dot, &mut path);
    path
}
