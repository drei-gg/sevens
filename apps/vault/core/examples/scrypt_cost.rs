//! How long one envelope takes on this machine: `cargo run --release --example scrypt_cost`.
use std::time::Instant;
use vault_core::*;

fn main() {
    let identity = vault_identity_generate();
    let started = Instant::now();
    let header = header_v2_new(Uuid::new_v4(), &identity, VaultCredentialKind::Passphrase, "several private testing words".into(), generate_recovery_key()).unwrap();
    println!("seal two envelopes: {:?}", started.elapsed());
    let started = Instant::now();
    header_v2_open(&header, "several private testing words".into(), VaultCredentialKind::Passphrase, false).unwrap();
    println!("open one envelope: {:?}", started.elapsed());
}
