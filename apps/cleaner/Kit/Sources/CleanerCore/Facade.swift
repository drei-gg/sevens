import Foundation

// Cleaner's logic is the Rust crate apps/cleaner/core; its Swift bindings (Generated/CleanerCoreFFI.swift)
// compile into this module. This file keeps the Swift spellings the app and CleanerPlatform were
// written against (Matching, ContactMatching, MutationGuard, `CleanupItem(id:name:kind:)`,
// `CleanerError.blocked`, `fullResolutionPSNR`) and only converts: Int to the core's Int32, Swift
// `Set`s to the core's sorted lists, error sentences, Identifiable conformances. No rule or
// calculation lives here.

// MARK: errors

public extension CleanerError {
    /// `CleanerError.blocked(reason)`: the reason is the user-facing message.
    static func blocked(_ reason: String) -> CleanerError { .Blocked(reason: reason) }
}

public extension Error {
    /// The sentence to show for an error. The core's errors describe themselves for debugging,
    /// so their user-facing sentence comes from here; everything else is its localized description.
    var cleanerMessage: String {
        switch self as? CleanerError {
        case .Blocked(let reason): reason
        case .Unreadable: "This file cannot be read."
        case nil: localizedDescription
        }
    }
}

// MARK: items and groups

extension CleanupItem: Identifiable {}
extension MatchGroup: Identifiable {}
extension HistoryEntry: Identifiable {}
extension ContactRecord: Identifiable {}
extension ContactMatch: Identifiable {}

extension ReviewDecision: CaseIterable {
    public static var allCases: [ReviewDecision] { reviewDecisionAllCases() }
}

public extension ReviewDecision {
    /// The file format's name: "undecided", "keep", "later", "remove".
    var rawValue: String { String(describing: self) }
}

public extension CleanupItem {
    init(id: String, name: String, kind: MediaKind, revision: String = "", bytes: Int64? = nil, date: Date? = nil,
         width: Int = 0, height: Int = 0, duration: Double = 0, favorite: Bool = false, tags: Set<String> = [],
         fingerprint: String? = nil) {
        self.init(id: id, name: name, kind: kind, revision: revision, bytes: bytes, date: date,
                  width: Int32(clamping: width), height: Int32(clamping: height), duration: duration,
                  favorite: favorite, tags: tags.sorted(), fingerprint: fingerprint)
    }
}

public extension MatchGroup {
    typealias Kind = MatchKind
}

public enum Matching {
    public static func exact(_ items: [CleanupItem]) -> [MatchGroup] { matchingExact(items: items) }
    public static func keeper(in items: [CleanupItem], decisions: [String: ReviewDecision]) -> String? {
        matchingKeeper(items: items, decisions: decisions)
    }
    /// Only byte-equal groups get default selections. Never overrides a deliberate review decision.
    public static func proposedRemoval(groups: [MatchGroup], items: [CleanupItem], decisions: [String: ReviewDecision]) -> Set<String> {
        Set(matchingProposedRemoval(groups: groups, items: items, decisions: decisions))
    }
}

public extension StorageSummary {
    init(_ items: [CleanupItem]) { self = storageSummary(items: items) }
}

// MARK: review state

public extension ReviewState {
    init() { self = newReviewState() }
}

public extension HistoryEntry {
    init(_ message: String) { self = newHistoryEntry(message: message) }
}

public enum MutationGuard {
    public static func validateRemoval(ids: Set<String>, items: [CleanupItem], state: ReviewState) throws {
        try CleanerCore.validateRemoval(ids: ids.sorted(), items: items, state: state)
    }
}

// MARK: compression

public extension ImageFacts {
    init(width: Int, height: Int, orientation: Int = 1, colorSpace: String = "sRGB", hasAlpha: Bool = false, bits: Int = 8, bytes: Int64) {
        self.init(width: Int32(clamping: width), height: Int32(clamping: height), orientation: Int32(clamping: orientation),
                  colorSpace: colorSpace, hasAlpha: hasAlpha, bits: Int32(clamping: bits), bytes: bytes)
    }
}

public extension CompressionAssessment {
    init(source: ImageFacts, output: ImageFacts, fullResolutionPSNR: Double, worstTilePSNR: Double, allowResize: Bool = false) {
        self = newCompressionAssessment(source: source, output: output, fullResolutionPsnr: fullResolutionPSNR,
                                        worstTilePsnr: worstTilePSNR, allowResize: allowResize)
    }
    var fullResolutionPSNR: Double { get { fullResolutionPsnr } set { fullResolutionPsnr = newValue } }
    var worstTilePSNR: Double { get { worstTilePsnr } set { worstTilePsnr = newValue } }
    var canOfferCopy: Bool { assessmentCanOfferCopy(assessment: self) }
    /// A diagnostic score never authorizes removing a source.
    var authorizesSourceDeletion: Bool { assessmentAuthorizesSourceDeletion(assessment: self) }
    var savings: Int64 { assessmentSavings(assessment: self) }
}

// MARK: contacts

public extension ContactField {
    init(_ key: String, _ value: String, label: String = "") { self = newContactField(key: key, value: value, label: label) }
}

public extension ContactRecord {
    /// Notes are inaccessible by default: reading them needs an additional Apple entitlement.
    init(id: String, name: String, container: String, fields: [ContactField], phoneKeys: Set<String> = [], emailKeys: Set<String> = []) {
        self = newContactRecord(id: id, name: name, container: container, fields: fields,
                                phoneKeys: Array(phoneKeys), emailKeys: Array(emailKeys))
    }
    var incomplete: Bool { contactIsIncomplete(record: self) }
}

public extension ContactMatch {
    init(left: String, right: String, reasons: [String]) { self = newContactMatch(left: left, right: right, reasons: reasons) }
}

public enum ContactMatching {
    public static func crowdedIdentifierCount(_ records: [ContactRecord]) -> Int { Int(contactCrowdedIdentifierCount(records: records)) }
    /// Candidate pairs, never transitive identity groups. Shared household numbers are not proof of identity.
    public static func candidates(_ records: [ContactRecord]) -> [ContactMatch] { contactCandidates(records: records) }
}

public extension ContactMergePlan {
    init(_ records: [ContactRecord], backupVerified: Bool, revisionUnchanged: Bool) {
        self = newContactMergePlan(records: records, backupVerified: backupVerified, revisionUnchanged: revisionUnchanged)
    }
    var canDeleteSources: Bool { mergePlanCanDeleteSources(plan: self) }
}
