import Foundation

/// Atomic durable user decisions. Scans never write over these. Corruption fails closed, not an
/// empty reset. The state changes, the file format and its version gate are the core's; the file
/// is this store's.
public actor ReviewStore {
    private let url: URL
    public init(url: URL) { self.url = url }
    public func read() throws -> ReviewState {
        guard FileManager.default.fileExists(atPath: url.path) else { return ReviewState() }
        return try reviewStateDecode(data: Data(contentsOf: url))
    }
    public func decide(_ decisions: [String: ReviewDecision], revisions: [String: String]) throws -> ReviewState {
        let state = reviewStateDecide(state: try read(), decisions: decisions, revisions: revisions)
        try write(state); return state
    }
    public func record(_ message: String, processedSource: String? = nil) throws -> ReviewState {
        let state = reviewStateRecord(state: try read(), entry: HistoryEntry(message), processedSource: processedSource)
        try write(state); return state
    }
    public func recordCopy(source: CleanupItem, outputID: String) throws -> ReviewState {
        let state = try reviewStateRecordCopy(state: read(), source: source, outputId: outputID,
            entry: HistoryEntry("Saved a compressed copy of \(source.name). Original retained."))
        try write(state); return state
    }
    private func write(_ state: ReviewState) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try reviewStateEncode(state: state).write(to: url, options: .atomic)
    }
}
