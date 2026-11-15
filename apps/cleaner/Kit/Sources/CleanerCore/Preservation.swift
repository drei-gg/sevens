import Foundation

public struct ImageFacts: Codable, Sendable, Equatable {
    public var width: Int
    public var height: Int
    public var orientation: Int
    public var colorSpace: String
    public var hasAlpha: Bool
    public var bits: Int
    public var bytes: Int64
    public init(width: Int, height: Int, orientation: Int = 1, colorSpace: String = "sRGB", hasAlpha: Bool = false, bits: Int = 8, bytes: Int64) {
        self.width = width; self.height = height; self.orientation = orientation; self.colorSpace = colorSpace; self.hasAlpha = hasAlpha; self.bits = bits; self.bytes = bytes
    }
}
public struct CompressionAssessment: Codable, Sendable, Equatable {
    public var source: ImageFacts
    public var output: ImageFacts
    public var fullResolutionPSNR: Double
    public var worstTilePSNR: Double
    public var issues: [String]
    public var canOfferCopy: Bool { issues.isEmpty }
    /// A diagnostic score never authorizes removing a source.
    public var authorizesSourceDeletion: Bool { false }
    public var savings: Int64 { max(0, source.bytes - output.bytes) }
    public init(source: ImageFacts, output: ImageFacts, fullResolutionPSNR: Double, worstTilePSNR: Double, allowResize: Bool = false) {
        self.source = source; self.output = output; self.fullResolutionPSNR = fullResolutionPSNR; self.worstTilePSNR = worstTilePSNR
        var issues: [String] = []
        if !allowResize && (source.width != output.width || source.height != output.height) { issues.append("Dimensions changed.") }
        if source.orientation != output.orientation { issues.append("Orientation changed.") }
        if source.colorSpace != output.colorSpace { issues.append("Color profile changed.") }
        if source.hasAlpha != output.hasAlpha { issues.append("Transparency changed.") }
        if source.bits != output.bits { issues.append("Bit depth changed.") }
        if output.bytes >= source.bytes || source.bytes - output.bytes < 4096 { issues.append("No meaningful byte reduction.") }
        // Deliberately conservative engineering thresholds, NOT a perceptual-quality guarantee.
        if fullResolutionPSNR.isNaN || fullResolutionPSNR < 38 || worstTilePSNR.isNaN || worstTilePSNR < 30 { issues.append("Detail comparison did not pass the current profile.") }
        self.issues = issues
    }
}
public struct ContactField: Codable, Sendable, Equatable, Hashable {
    public var key: String
    public var label: String
    public var value: String
    public init(_ key: String, _ value: String, label: String = "") { self.key = key; self.value = value; self.label = label }
}
public struct ContactRecord: Identifiable, Codable, Sendable, Equatable {
    public var id: String
    public var name: String
    public var container: String
    public var fields: [ContactField]
    public var phoneKeys: Set<String>
    public var emailKeys: Set<String>
    public var inaccessibleFields: Set<String>
    public init(id: String, name: String, container: String, fields: [ContactField], phoneKeys: Set<String> = [], emailKeys: Set<String> = [], inaccessibleFields: Set<String> = ["notes"]) {
        self.id = id; self.name = name; self.container = container; self.fields = fields; self.phoneKeys = phoneKeys; self.emailKeys = emailKeys; self.inaccessibleFields = inaccessibleFields
    }
    public var incomplete: Bool { name.isEmpty || !fields.contains { ["phone", "email"].contains($0.key) && !$0.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } }
}
public struct ContactMatch: Identifiable, Sendable, Equatable {
    public var id: String { [left, right].sorted().joined(separator: "|") }
    public var left: String
    public var right: String
    public var reasons: [String]
    public init(left: String, right: String, reasons: [String]) { self.left = left; self.right = right; self.reasons = reasons }
}
public enum ContactMatching {
    public static func crowdedIdentifierCount(_ records: [ContactRecord]) -> Int {
        var counts: [String: Int] = [:]
        for record in records {
            for key in record.phoneKeys { counts["p:" + key, default: 0] += 1 }
            for key in record.emailKeys { counts["e:" + key, default: 0] += 1 }
        }
        return counts.values.filter { $0 > 100 }.count
    }
    /// Candidate pairs, never transitive identity groups. Shared household numbers are not proof of identity.
    public static func candidates(_ records: [ContactRecord]) -> [ContactMatch] {
        var buckets: [String: [String]] = [:]
        for record in records {
            for key in record.phoneKeys { buckets["p:" + key, default: []].append(record.id) }
            for key in record.emailKeys { buckets["e:" + key, default: []].append(record.id) }
        }
        var pairs: [String: ContactMatch] = [:]
        for (key, ids) in buckets {
            let ids = Array(Set(ids)).sorted()
            // Shared switchboards are still shown, but do not explode into millions of pair rows.
            guard ids.count <= 100 else { continue }
            for i in ids.indices { for j in ids.indices where j > i {
                let id = ids[i] + "|" + ids[j]
                var match = pairs[id] ?? ContactMatch(left: ids[i], right: ids[j], reasons: [])
                let reason = key.hasPrefix("p:") ? "Shared phone number (including extension)" : "Shared email address"
                if !match.reasons.contains(reason) { match.reasons.append(reason) }; pairs[id] = match
            } }
        }
        return pairs.values.sorted { $0.id < $1.id }
    }
}
public struct ContactMergePlan: Sendable, Equatable {
    public var fields: [ContactField]
    public var conflicts: [String]
    public var blockers: [String]
    public var canDeleteSources: Bool { blockers.isEmpty && conflicts.isEmpty }
    public init(_ records: [ContactRecord], backupVerified: Bool, revisionUnchanged: Bool) {
        fields = Array(Set(records.flatMap(\.fields))).sorted { ($0.key, $0.label, $0.value) < ($1.key, $1.label, $1.value) }
        let multivalue: Set<String> = ["phone", "email", "address", "url", "social", "instantMessage", "relation", "date"]
        conflicts = Dictionary(grouping: fields.filter { !multivalue.contains($0.key) && !$0.value.isEmpty }, by: \.key).filter { Set($0.value.map(\.value)).count > 1 }.keys.sorted()
        blockers = []
        if records.count < 2 { blockers.append("Choose at least two source records.") }
        if !records.allSatisfy({ $0.inaccessibleFields.isEmpty }) { blockers.append("Some source fields cannot be read. Keep the original records.") }
        if Set(records.map(\.container)).count != 1 { blockers.append("Records belong to different accounts.") }
        if !backupVerified { blockers.append("Backup has not passed a read-back check.") }
        if !revisionUnchanged { blockers.append("A source changed after review.") }
    }
}
