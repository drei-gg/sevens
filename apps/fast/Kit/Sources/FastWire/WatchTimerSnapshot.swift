import Foundation

/// The watch face gets timer metadata only, not the Watch app's water or entitlement state.
/// Missing data remains distinct from an explicitly synced idle timer.
public struct WatchTimerSnapshot: Codable, Equatable, Sendable {
    public let version: Int
    public let generatedAt: Date
    public let start: Date?
    public let goal: Date?
    public static let filename = "watch-timer.v1.json"
    public static let widgetKind = "FastWatchTimer"

    public init(_ snapshot: FastWatchSnapshot) {
        version = 1
        generatedAt = snapshot.generatedAt
        start = snapshot.start
        goal = snapshot.goal
    }

    public static func decode(_ data: Data) throws -> Self {
        let value = try FastWatchCodec.decode(Self.self, from: data)
        guard value.version == 1 else { throw FastWatchFailure.unsupported }
        return value
    }
}
