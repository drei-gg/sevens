uniffi::setup_scaffolding!();
mod commands;
mod export;
mod health;
mod watch;
pub mod insights;
pub mod model;
pub mod observations;
pub mod schedule;
pub mod time;
use age::secrecy::SecretString;
use model::*;
use std::io::{Read, Write};
use zeroize::Zeroizing;
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum Error {
    #[error("{reason}")]
    Invalid { reason: String },
}
impl Error {
    pub fn invalid(s: &str) -> Self {
        Self::Invalid { reason: s.into() }
    }
}
pub type Result<T> = std::result::Result<T, Error>;
pub fn ensure(ok: bool, s: &str) -> Result<()> {
    if ok { Ok(()) } else { Err(Error::invalid(s)) }
}
fn json<T: serde::Serialize>(v: &T) -> Result<String> {
    serde_json::to_string(v).map_err(|_| Error::invalid("Could not encode records."))
}
#[uniffi::export]
pub fn bloom_empty() -> String {
    serde_json::to_string(&Archive::default()).expect("default archive encodes")
}
#[uniffi::export]
pub fn bloom_validate(archive: String) -> Result<String> {
    json(&decode(&archive)?)
}
#[uniffi::export]
pub fn bloom_apply(
    archive: String,
    command: String,
    now: i64,
    zone: String,
    paid: bool,
) -> Result<String> {
    ensure(command.len() <= 512_000, "Action is too large.")?;
    let mut a = decode(&archive)?;
    let c = serde_json::from_str(&command).map_err(|_| Error::invalid("Unreadable action."))?;
    commands::apply(&mut a, c, now, &zone, paid)?;
    json(&a)
}
#[uniffi::export]
pub fn bloom_dashboard(archive: String, now: i64, zone: String) -> Result<String> {
    json(&insights::dashboard(&decode(&archive)?, now, &zone)?)
}
/// Android's rolling receiver must not inherit iOS's finite pending-notification queue cap.
#[uniffi::export]
pub fn bloom_due_reminders(archive: String, after: i64, now: i64, zone: String) -> Result<String> {
    ensure(
        after < now && now - after <= 86401,
        "Invalid reminder delivery window.",
    )?;
    let d = insights::full_dashboard(&decode(&archive)?, after, &zone)?;
    json(
        &d.reminders
            .into_iter()
            .filter(|r| r.at <= now)
            .collect::<Vec<_>>(),
    )
}
#[uniffi::export]
pub fn bloom_merge(archive: String, incoming: String) -> Result<String> {
    json(&commands::merge(decode(&archive)?, decode(&incoming)?)?)
}
#[uniffi::export]
pub fn bloom_health_export(archive: String) -> Result<String> {
    let archive = decode(&archive)?;
    ensure(
        archive.revision < i64::MAX as u64,
        "This journal revision cannot be shared with Health.",
    )?;
    json(&health::export(&archive))
}
#[uniffi::export]
pub fn bloom_health_review(archive: String, samples: String) -> Result<String> {
    ensure(samples.len() <= MAX_BYTES, "Health samples are too large.")?;
    let samples = serde_json::from_str(&samples)
        .map_err(|_| Error::invalid("Invalid Health flow samples."))?;
    json(&health::review(&decode(&archive)?, samples)?)
}
#[uniffi::export]
pub fn bloom_health_import(archive: String, reviewed: String) -> Result<String> {
    let current = decode(&archive)?;
    let reviewed = decode(&reviewed)?;
    // Only the reviewed flow values cross this boundary, even if a caller supplies an archive
    // with additional fields. Recheck occupied dates under the repository's transaction lock.
    let samples = reviewed
        .days
        .into_iter()
        .map(|d| health::FlowSample {
            day: d.day,
            flow: d.flow,
            period_start: d.period_start,
        })
        .collect();
    let incoming = health::review(&current, samples)?;
    if incoming.days.is_empty() {
        return json(&current);
    }
    json(&commands::merge(current, incoming)?)
}
#[uniffi::export]
pub fn bloom_csv(archive: String, include_contacts: bool, include_notes: bool) -> Result<String> {
    Ok(export::csv(
        &decode(&archive)?,
        include_contacts,
        include_notes,
    ))
}
#[uniffi::export]
pub fn bloom_report(
    archive: String,
    from: String,
    to: String,
    include_contacts: bool,
    include_notes: bool,
) -> Result<String> {
    export::report(
        &decode(&archive)?,
        &from,
        &to,
        include_contacts,
        include_notes,
    )
}
#[uniffi::export]
pub fn bloom_seal(archive: String, password: String) -> Result<Vec<u8>> {
    let plaintext = Zeroizing::new(bloom_validate(archive)?);
    let password = Zeroizing::new(password);
    ensure(
        password.chars().count() >= 12 && password.len() <= 1024,
        "Use a backup passphrase of at least 12 characters.",
    )?;
    let mut recipient = age::scrypt::Recipient::new(SecretString::from(password.to_string()));
    recipient.set_work_factor(16);
    let encryptor =
        age::Encryptor::with_recipients(std::iter::once(&recipient as &dyn age::Recipient))
            .map_err(|_| Error::invalid("Could not create encrypted backup."))?;
    let mut out = vec![];
    let mut writer = encryptor
        .wrap_output(&mut out)
        .map_err(|_| Error::invalid("Could not encrypt backup."))?;
    writer
        .write_all(plaintext.as_bytes())
        .map_err(|_| Error::invalid("Could not encrypt backup."))?;
    writer
        .finish()
        .map_err(|_| Error::invalid("Could not finish backup."))?;
    Ok(out)
}
#[uniffi::export]
pub fn bloom_open(data: Vec<u8>, password: String) -> Result<String> {
    ensure(
        data.len() <= MAX_BYTES + 1_000_000 && password.len() <= 1024,
        "Backup is too large.",
    )?;
    let password = Zeroizing::new(password);
    let decryptor = age::Decryptor::new_buffered(&data[..])
        .map_err(|_| Error::invalid("This is not an encrypted age backup."))?;
    ensure(
        decryptor.is_scrypt(),
        "This backup uses an unsupported key type.",
    )?;
    let mut identity = age::scrypt::Identity::new(SecretString::from(password.to_string()));
    identity.set_max_work_factor(18);
    let reader = decryptor
        .decrypt(std::iter::once(&identity as &dyn age::Identity))
        .map_err(|_| {
            Error::invalid("Could not unlock this backup. Check the passphrase and file.")
        })?;
    let mut plaintext = Zeroizing::new(Vec::new());
    reader
        .take((MAX_BYTES + 1) as u64)
        .read_to_end(&mut plaintext)
        .map_err(|_| Error::invalid("Backup authentication failed."))?;
    ensure(
        plaintext.len() <= MAX_BYTES,
        "Decrypted backup is too large.",
    )?;
    bloom_validate(
        String::from_utf8(plaintext.to_vec())
            .map_err(|_| Error::invalid("Backup contents are unreadable."))?,
    )
}

#[uniffi::export]
pub fn bloom_watch_snapshot(archive: String, now: i64, zone: String, paid: bool) -> Result<String> {
    json(&watch::snapshot(&decode(&archive)?, now, &zone, paid)?)
}
#[uniffi::export]
pub fn bloom_watch_apply(archive: String, request: String, now: i64, zone: String, paid: bool) -> Result<String> {
    let mut archive = decode(&archive)?;
    watch::apply(&mut archive, &request, now, &zone, paid)?;
    json(&archive)
}
