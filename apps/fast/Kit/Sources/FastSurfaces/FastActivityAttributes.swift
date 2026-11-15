import ActivityKit
import Foundation

public struct FastActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var start: Date
        public var goal: Date?
        public init(start: Date, goal: Date?) { self.start = start; self.goal = goal }
    }
    public var fastID: UUID
    public init(fastID: UUID) { self.fastID = fastID }
}
