import Foundation

public enum MediaKind: String, Codable, Sendable { case photo, video, file }
public enum ReviewDecision: String, Codable, Sendable, CaseIterable { case undecided, keep, later, remove }
public struct CleanupItem: Identifiable, Codable, Sendable, Equatable {
    public let id: String
    public var name: String
    public var kind: MediaKind
    public var revision: String
    public var bytes: Int64?
    public var date: Date?
    public var width: Int
    public var height: Int
    public var duration: Double
    public var favorite: Bool
    public var tags: Set<String>
    public var fingerprint: String?
    public init(id: String, name: String, kind: MediaKind, revision: String = "", bytes: Int64? = nil, date: Date? = nil, width: Int = 0, height: Int = 0, duration: Double = 0, favorite: Bool = false, tags: Set<String> = [], fingerprint: String? = nil) {
        self.id = id; self.name = name; self.kind = kind; self.revision = revision; self.bytes = bytes; self.date = date
        self.width = width; self.height = height; self.duration = duration; self.favorite = favorite; self.tags = tags; self.fingerprint = fingerprint
    }
}
public struct MatchGroup: Identifiable, Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable { case identicalResources, similar }
    public var id: String
    public var kind: Kind
    public var members: [String]
    public var reason: String
    public init(id: String, kind: Kind, members: [String], reason: String) { self.id = id; self.kind = kind; self.members = members; self.reason = reason }
}
public enum Matching {
    public static func exact(_ items: [CleanupItem]) -> [MatchGroup] {
        let eligible = items.filter { $0.fingerprint != nil }
        return Dictionary(grouping: eligible, by: { "\($0.kind.rawValue):\($0.fingerprint!)" }).compactMap { key, group in
            guard group.count > 1 else { return nil }
            return MatchGroup(id: key, kind: .identicalResources, members: group.map(\.id).sorted(), reason: "All readable resource bytes match. Dates, favorites and album membership can still differ.")
        }.sorted { $0.id < $1.id }
    }
    public static func keeper(in items: [CleanupItem], decisions: [String: ReviewDecision]) -> String? {
        items.filter { decisions[$0.id] != .remove }.sorted {
            let a = decisions[$0.id] == .keep || $0.favorite
            let b = decisions[$1.id] == .keep || $1.favorite
            if a != b { return a }
            if $0.width * $0.height != $1.width * $1.height { return $0.width * $0.height > $1.width * $1.height }
            if $0.date != $1.date { return ($0.date ?? .distantFuture) < ($1.date ?? .distantFuture) }
            return $0.id < $1.id
        }.first?.id
    }
    /// Only byte-equal groups get default selections. Never override a deliberate review decision.
    public static func proposedRemoval(groups: [MatchGroup], items: [CleanupItem], decisions: [String: ReviewDecision]) -> Set<String> {
        let byID = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
        var result = Set<String>()
        for group in groups where group.kind == .identicalResources {
            let members = group.members.compactMap { byID[$0] }
            guard members.count > 1, let keeper = keeper(in: members, decisions: decisions) else { continue }
            for item in members where item.id != keeper && !item.favorite && (decisions[item.id] ?? .undecided) == .undecided { result.insert(item.id) }
        }
        return result
    }
}
public struct StorageSummary: Sendable, Equatable {
    public let knownBytes: Int64
    public let unknownCount: Int
    public init(_ items: [CleanupItem]) { knownBytes = items.compactMap(\.bytes).reduce(0,+); unknownCount = items.filter { $0.bytes == nil }.count }
}
public enum CleanerError: Error, LocalizedError, Sendable, Equatable {
    case blocked(String)
    public var errorDescription: String? { switch self { case .blocked(let reason): reason } }
}
