import Foundation

public struct ReviewState: Codable, Sendable, Equatable {
    public var version = 1
    public var decisions: [String: ReviewDecision] = [:]
    public var revisions: [String: String] = [:]
    public var history: [HistoryEntry] = []
    public var processedSources: Set<String> = []
    public init() {}
}
public struct HistoryEntry: Codable, Identifiable, Sendable, Equatable {
    public var id = UUID()
    public var date = Date()
    public var message: String
    public init(_ message: String) { self.message = message }
}
/// Atomic durable user decisions. Scans never write over these. Corruption fails closed, not an empty reset.
public actor ReviewStore {
    private let url: URL
    public init(url: URL) { self.url = url }
    public func read() throws -> ReviewState {
        guard FileManager.default.fileExists(atPath: url.path) else { return ReviewState() }
        let state = try JSONDecoder().decode(ReviewState.self, from: Data(contentsOf: url))
        guard state.version == 1 else { throw CleanerError.blocked("This review database needs a newer app.") }; return state
    }
    public func decide(_ decisions: [String: ReviewDecision], revisions: [String: String]) throws -> ReviewState {
        var state = try read()
        for (id, value) in decisions { state.decisions[id] = value; state.revisions[id] = revisions[id] }
        try write(state); return state
    }
    public func record(_ message: String, processedSource: String? = nil) throws -> ReviewState {
        var state = try read(); state.history.insert(HistoryEntry(message), at: 0)
        if let processedSource { state.processedSources.insert(processedSource) }
        try write(state); return state
    }
    private func write(_ state: ReviewState) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(state).write(to: url, options: .atomic)
    }
}
public enum MutationGuard {
    public static func validateRemoval(ids: Set<String>, items: [CleanupItem], state: ReviewState) throws {
        guard !ids.isEmpty else { throw CleanerError.blocked("Nothing selected.") }
        let byID = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
        for id in ids {
            guard let item = byID[id], state.decisions[id] == .remove,
                  state.revisions[id] == item.revision, !item.favorite else {
                throw CleanerError.blocked("Selection changed, is protected, or needs review again.")
            }
        }
    }
}
