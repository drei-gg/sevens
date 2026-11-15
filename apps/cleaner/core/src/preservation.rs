//! Preservation.swift: compression assessment that can never authorize deletion, contact records,
//! candidate pairs that never become transitive identity groups, and merge plans that block on
//! anything unreadable, cross-account or stale.

use crate::codec::set;
use crate::text::{canonical_set, nfc, swift_cmp, swift_eq, trimmed};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashMap};

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct ImageFacts {
    pub width: i32,
    pub height: i32,
    pub orientation: i32,
    pub color_space: String,
    pub has_alpha: bool,
    pub bits: i32,
    pub bytes: i64,
}

/// `ImageFacts(width:height:bytes:)` with Swift's defaults: orientation 1, sRGB, opaque, 8-bit.
#[uniffi::export]
pub fn new_image_facts(width: i32, height: i32, bytes: i64) -> ImageFacts {
    ImageFacts { width, height, orientation: 1, color_space: "sRGB".into(), has_alpha: false, bits: 8, bytes }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct CompressionAssessment {
    pub source: ImageFacts,
    pub output: ImageFacts,
    #[serde(rename = "fullResolutionPSNR")]
    pub full_resolution_psnr: f64,
    #[serde(rename = "worstTilePSNR")]
    pub worst_tile_psnr: f64,
    /// The initializer's findings; the platform appends metadata findings after it.
    pub issues: Vec<String>,
}

/// `CompressionAssessment(source:output:fullResolutionPSNR:worstTilePSNR:allowResize:)`.
/// The thresholds are deliberately conservative engineering limits, not a perceptual guarantee.
#[uniffi::export(default(allow_resize = false))]
pub fn new_compression_assessment(
    source: ImageFacts,
    output: ImageFacts,
    full_resolution_psnr: f64,
    worst_tile_psnr: f64,
    allow_resize: bool,
) -> CompressionAssessment {
    let mut issues = Vec::new();
    if !allow_resize && (source.width != output.width || source.height != output.height) {
        issues.push("Dimensions changed.".to_string());
    }
    if source.orientation != output.orientation {
        issues.push("Orientation changed.".to_string());
    }
    if source.color_space != output.color_space {
        issues.push("Color profile changed.".to_string());
    }
    if source.has_alpha != output.has_alpha {
        issues.push("Transparency changed.".to_string());
    }
    if source.bits != output.bits {
        issues.push("Bit depth changed.".to_string());
    }
    if output.bytes >= source.bytes || source.bytes - output.bytes < 4096 {
        issues.push("No meaningful byte reduction.".to_string());
    }
    if full_resolution_psnr.is_nan() || full_resolution_psnr < 38.0 || worst_tile_psnr.is_nan() || worst_tile_psnr < 30.0 {
        issues.push("Detail comparison did not pass the current profile.".to_string());
    }
    CompressionAssessment { source, output, full_resolution_psnr, worst_tile_psnr, issues }
}

/// `canOfferCopy`: a separate copy may be offered only when nothing was found.
#[uniffi::export]
pub fn assessment_can_offer_copy(assessment: &CompressionAssessment) -> bool {
    assessment.issues.is_empty()
}

/// `authorizesSourceDeletion`: a diagnostic score never authorizes removing a source.
#[uniffi::export]
pub fn assessment_authorizes_source_deletion(_assessment: &CompressionAssessment) -> bool {
    false
}

/// `savings`: bytes saved, never negative.
#[uniffi::export]
pub fn assessment_savings(assessment: &CompressionAssessment) -> i64 {
    assessment.source.bytes.saturating_sub(assessment.output.bytes).max(0)
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, uniffi::Record)]
pub struct ContactField {
    pub key: String,
    pub label: String,
    pub value: String,
}

/// `ContactField(key, value, label:)`, label defaulting to empty.
#[uniffi::export(default(label = ""))]
pub fn new_contact_field(key: String, value: String, label: String) -> ContactField {
    ContactField { key, label, value }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
#[serde(rename_all = "camelCase")]
pub struct ContactRecord {
    pub id: String,
    pub name: String,
    pub container: String,
    pub fields: Vec<ContactField>,
    /// Swift `Set<String>`s, kept sorted and deduplicated.
    #[serde(with = "set")]
    pub phone_keys: Vec<String>,
    #[serde(with = "set")]
    pub email_keys: Vec<String>,
    #[serde(with = "set")]
    pub inaccessible_fields: Vec<String>,
}

/// `ContactRecord(id:name:container:fields:phoneKeys:emailKeys:)`: notes are inaccessible by default
/// because reading them needs an additional Apple entitlement.
#[uniffi::export]
pub fn new_contact_record(
    id: String,
    name: String,
    container: String,
    fields: Vec<ContactField>,
    phone_keys: Vec<String>,
    email_keys: Vec<String>,
) -> ContactRecord {
    ContactRecord {
        id,
        name,
        container,
        fields,
        phone_keys: canonical_set(phone_keys),
        email_keys: canonical_set(email_keys),
        inaccessible_fields: vec!["notes".into()],
    }
}

/// `incomplete`: no name, or no non-blank phone or email.
#[uniffi::export]
pub fn contact_is_incomplete(record: &ContactRecord) -> bool {
    record.name.is_empty()
        || !record.fields.iter().any(|f| (f.key == "phone" || f.key == "email") && !trimmed(&f.value).is_empty())
}

/// A candidate pair, never a transitive identity group.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContactMatch {
    /// `[left, right].sorted().joined(separator: "|")`.
    pub id: String,
    pub left: String,
    pub right: String,
    pub reasons: Vec<String>,
}

fn match_id(left: &str, right: &str) -> String {
    let mut ids = [left, right];
    ids.sort_by(|a, b| swift_cmp(a, b));
    ids.join("|")
}

/// `ContactMatch(left:right:reasons:)`, deriving its id.
#[uniffi::export]
pub fn new_contact_match(left: String, right: String, reasons: Vec<String>) -> ContactMatch {
    ContactMatch { id: match_id(&left, &right), left, right, reasons }
}

fn identifier_buckets(records: &[ContactRecord]) -> BTreeMap<String, Vec<String>> {
    let mut buckets: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for record in records {
        for key in canonical_set(record.phone_keys.clone()) {
            buckets.entry(format!("p:{}", nfc(&key))).or_default().push(record.id.clone());
        }
        for key in canonical_set(record.email_keys.clone()) {
            buckets.entry(format!("e:{}", nfc(&key))).or_default().push(record.id.clone());
        }
    }
    buckets
}

/// `ContactMatching.crowdedIdentifierCount`: identifiers shared by more than 100 records.
#[uniffi::export]
pub fn contact_crowded_identifier_count(records: &[ContactRecord]) -> i32 {
    identifier_buckets(records).values().filter(|ids| ids.len() > 100).count() as i32
}

/// `ContactMatching.candidates`: pairs sharing a phone key or email key. Identifiers shared by
/// more than 100 records (switchboards) produce no pairs. Shared numbers are not identity.
#[uniffi::export]
pub fn contact_candidates(records: &[ContactRecord]) -> Vec<ContactMatch> {
    let mut pairs: HashMap<String, ContactMatch> = HashMap::new();
    // Swift walks a dictionary in hash order, so a pair with both kinds lists them in either order;
    // here buckets go in key order: email reasons before phone reasons.
    for (key, ids) in identifier_buckets(records) {
        let ids = canonical_set(ids);
        if ids.len() > 100 {
            continue;
        }
        let reason = if key.starts_with("p:") { "Shared phone number (including extension)" } else { "Shared email address" };
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                let id = format!("{}|{}", ids[i], ids[j]);
                let entry = pairs.entry(id).or_insert_with(|| new_contact_match(ids[i].clone(), ids[j].clone(), vec![]));
                if !entry.reasons.iter().any(|r| r == reason) {
                    entry.reasons.push(reason.to_string());
                }
            }
        }
    }
    let mut result: Vec<ContactMatch> = pairs.into_values().collect();
    result.sort_by(|a, b| swift_cmp(&a.id, &b.id));
    result
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct ContactMergePlan {
    pub fields: Vec<ContactField>,
    pub conflicts: Vec<String>,
    pub blockers: Vec<String>,
}

const MULTIVALUE: [&str; 8] = ["phone", "email", "address", "url", "social", "instantMessage", "relation", "date"];

fn field_order(a: &ContactField, b: &ContactField) -> std::cmp::Ordering {
    swift_cmp(&a.key, &b.key).then_with(|| swift_cmp(&a.label, &b.label)).then_with(|| swift_cmp(&a.value, &b.value))
}

/// `ContactMergePlan(_:backupVerified:revisionUnchanged:)`.
#[uniffi::export]
pub fn new_contact_merge_plan(records: &[ContactRecord], backup_verified: bool, revision_unchanged: bool) -> ContactMergePlan {
    let mut fields: Vec<ContactField> = records.iter().flat_map(|r| r.fields.iter().cloned()).collect();
    fields.sort_by(field_order);
    fields.dedup_by(|a, b| field_order(a, b).is_eq());

    let mut by_key: Vec<(String, Vec<String>)> = Vec::new();
    for field in fields.iter().filter(|f| !MULTIVALUE.contains(&f.key.as_str()) && !f.value.is_empty()) {
        match by_key.iter_mut().find(|(key, _)| swift_eq(key, &field.key)) {
            Some((_, values)) => values.push(field.value.clone()),
            None => by_key.push((field.key.clone(), vec![field.value.clone()])),
        }
    }
    let mut conflicts: Vec<String> =
        by_key.into_iter().filter(|(_, values)| canonical_set(values.clone()).len() > 1).map(|(key, _)| key).collect();
    conflicts.sort_by(|a, b| swift_cmp(a, b));

    let mut blockers = Vec::new();
    if records.len() < 2 {
        blockers.push("Choose at least two source records.".to_string());
    }
    if !records.iter().all(|r| r.inaccessible_fields.is_empty()) {
        blockers.push("Some source fields cannot be read. Keep the original records.".to_string());
    }
    if canonical_set(records.iter().map(|r| r.container.clone()).collect()).len() != 1 {
        blockers.push("Records belong to different accounts.".to_string());
    }
    if !backup_verified {
        blockers.push("Backup has not passed a read-back check.".to_string());
    }
    if !revision_unchanged {
        blockers.push("A source changed after review.".to_string());
    }
    ContactMergePlan { fields, conflicts, blockers }
}

/// `canDeleteSources`: no blockers and no conflicts.
#[uniffi::export]
pub fn merge_plan_can_delete_sources(plan: &ContactMergePlan) -> bool {
    plan.blockers.is_empty() && plan.conflicts.is_empty()
}
