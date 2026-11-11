import Foundation

/// A small, versioned protocol. Journal, weight and private reflection never leave the phone.
public struct FastWatchRequest: Codable, Equatable, Sendable {
    public enum Action: String, Codable, Sendable { case refresh, start, end, water }
    public var version = 1
    public var id: UUID
    public var sentAt: Date
    public var action: Action
    public var revision: UUID?
    public var activeID: UUID?
    public init(id: UUID = UUID(), sentAt: Date = Date(), action: Action, revision: UUID? = nil, activeID: UUID? = nil) {
        self.id = id; self.sentAt = sentAt; self.action = action; self.revision = revision; self.activeID = activeID
    }
}
public struct FastWatchReceipt: Codable, Equatable, Sendable {
    public var request: FastWatchRequest
    public var savedAt: Date
}
public struct FastWatchSnapshot: Codable, Equatable, Sendable {
    public var revision: UUID
    public var generatedAt: Date
    public var activeID: UUID?
    public var start: Date?
    public var goal: Date?
    public var waterMilliliters: Double
    public var canCreateRecords: Bool
    public init(archive: FastArchive, canCreateRecords: Bool, now: Date = Date(), calendar: Calendar = .current) {
        revision = archive.companionRevision; generatedAt = now
        activeID = archive.active?.id; start = archive.active?.start; goal = archive.active?.goalDate
        waterMilliliters = archive.metrics.filter { $0.kind == .water && calendar.isDate($0.date, inSameDayAs: now) }.reduce(0) { $0 + $1.value }
        self.canCreateRecords = canCreateRecords
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
public extension FastArchive {
    /// Invoked inside ArchiveStore's atomic transaction. Receipts survive phone relaunch.
    /// A timed-out request may be retried verbatim, never replaced with a second water entry.
    mutating func applyWatch(_ request: FastWatchRequest, canCreateRecords: Bool, now: Date) throws {
        guard request.version == 1 else { throw FastWatchFailure.unsupported }
        guard request.sentAt.timeIntervalSince1970.isFinite,
              now.timeIntervalSince(request.sentAt) >= -30,
              now.timeIntervalSince(request.sentAt) <= 120 else { throw FastWatchFailure.expired }
        if let receipt = companionReceipts.first(where: { $0.request.id == request.id }) {
            var saved = receipt.request
            if abs(saved.sentAt.timeIntervalSince(request.sentAt)) <= 0.000_001 { saved.sentAt = request.sentAt }
            guard saved == request else { throw FastWatchFailure.malformed }
            return
        }
        guard request.action != .refresh else { return }
        guard request.revision == companionRevision, request.activeID == active?.id else { throw FastWatchFailure.stale }
        var next = self
        switch request.action {
        case .refresh: break
        case .start:
            guard canCreateRecords else { throw FastWatchFailure.locked }
            guard active == nil else { throw FastWatchFailure.stale }
            try next.save(FastRecord(id: request.id, start: now), now: now)
        case .end:
            guard var current = active, now > current.start else { throw FastWatchFailure.stale }
            current.end = now
            try next.save(current, now: now)
        case .water:
            guard canCreateRecords else { throw FastWatchFailure.locked }
            next.metrics.append(MetricEntry(id: request.id, date: now, kind: .water, value: 250))
        }
        next.companionReceipts.removeAll { now.timeIntervalSince($0.savedAt) > 600 }
        guard next.companionReceipts.count < 2_000 else { throw FastWatchFailure.unavailable }
        next.companionReceipts.append(FastWatchReceipt(request: request, savedAt: now))
        self = next
    }
}
