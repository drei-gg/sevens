import Foundation

/// Only timer metadata crosses into the widget container. Never journal text or measurements.
public struct TimerSnapshot: Codable, Equatable, Sendable {
    public var fastID: UUID?
    public var start: Date?
    public var goal: Date?
    public init(fastID: UUID? = nil, start: Date? = nil, goal: Date? = nil) {
        self.fastID = fastID; self.start = start; self.goal = goal
    }
    public init(archive: FastArchive) {
        self.init(fastID: archive.active?.id, start: archive.active?.start, goal: archive.active?.goalDate)
    }
    public static let filename = "timer.v1.json"
}
