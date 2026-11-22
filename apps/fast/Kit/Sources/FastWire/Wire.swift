import Foundation

// The data that crosses to the Watch and the widgets: plain Codable types with no Fast logic, so
// watchOS and the widget extension never link the Rust core. The phone builds these from the
// archive in FastCore (see FastCore's Façade.swift) and applies Watch requests through the core.
// A small, versioned protocol. Journal, weight and private reflection never leave the phone.

public struct FastWatchRequest: Codable, Equatable, Sendable {
    public enum Action: String, Codable, Sendable { case refresh, start, end, water }
    public var version = 1
    public var id: UUID
    public var sentAt: Date
    public var action: Action
    public var revision: UUID?
    public var activeID: UUID?
    public var presetID: UUID?
    public init(id: UUID = UUID(), sentAt: Date = Date(), action: Action, revision: UUID? = nil, activeID: UUID? = nil, presetID: UUID? = nil) {
        self.id = id; self.sentAt = sentAt; self.action = action; self.revision = revision; self.activeID = activeID
        self.presetID = presetID; self.version = presetID == nil ? 1 : 2
    }
}

public struct FastWatchPreset: Codable, Equatable, Sendable, Identifiable {
    public var id: UUID
    public var goalMinutes: Int32?
    public var eatingMinutes: Int32
    public init(id: UUID, goalMinutes: Int32?, eatingMinutes: Int32) {
        self.id = id; self.goalMinutes = goalMinutes; self.eatingMinutes = eatingMinutes
    }
}

public struct FastWatchSnapshot: Codable, Equatable, Sendable {
    public var revision: UUID
    public var generatedAt: Date
    public var activeID: UUID?
    public var start: Date?
    public var goal: Date?
    public var waterMilliliters: Double
    public var canCreateRecords: Bool
    public var presets: [FastWatchPreset]?
    public init(revision: UUID, generatedAt: Date, activeID: UUID?, start: Date?, goal: Date?,
                waterMilliliters: Double, canCreateRecords: Bool, presets: [FastWatchPreset]? = nil) {
        self.revision = revision; self.generatedAt = generatedAt; self.activeID = activeID
        self.start = start; self.goal = goal; self.waterMilliliters = waterMilliliters
        self.canCreateRecords = canCreateRecords; self.presets = presets
    }
}

public struct FastWatchReply: Codable, Sendable {
    public var version = 1
    public var requestID: UUID?
    public var snapshot: FastWatchSnapshot?
    public var failure: FastWatchFailure?
    public init(requestID: UUID? = nil, snapshot: FastWatchSnapshot? = nil, failure: FastWatchFailure? = nil) {
        self.requestID = requestID; self.snapshot = snapshot; self.failure = failure
    }
}

public enum FastWatchFailure: String, Error, Codable, Sendable {
    case unsupported, malformed, expired, stale, locked, unavailable, saveFailed
}

public enum FastWatchCodec {
    public static let maximumBytes = 32_768
    public static func encode<T: Encodable>(_ value: T) throws -> Data {
        let data = try JSONEncoder().encode(value)
        guard data.count <= maximumBytes else { throw FastWatchFailure.malformed }
        return data
    }
    public static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        guard data.count <= maximumBytes else { throw FastWatchFailure.malformed }
        return try JSONDecoder().decode(type, from: data)
    }
}

/// Only timer metadata crosses into the widget container. Never journal text or measurements.
public struct TimerSnapshot: Codable, Equatable, Sendable {
    public var fastID: UUID?
    public var start: Date?
    public var goal: Date?
    public init(fastID: UUID? = nil, start: Date? = nil, goal: Date? = nil) {
        self.fastID = fastID; self.start = start; self.goal = goal
    }
    public static let filename = "timer.v1.json"
}
