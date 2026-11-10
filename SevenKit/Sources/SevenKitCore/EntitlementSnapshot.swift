import Foundation

/// What the app writes to the app group's `UserDefaults` after every state change so a share
/// extension can read it (PLAN §6.10): `{state, trialEndsAt, writtenAt}`.
public struct EntitlementSnapshot: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        case notStarted, trial, expired, unlocked, pending
    }

    public var state: Kind
    /// For `.trial` the end; for `.expired` the moment it ended; otherwise nil.
    public var trialEndsAt: Date?
    public var writtenAt: Date

    public init(state: Kind, trialEndsAt: Date? = nil, writtenAt: Date) {
        self.state = state
        self.trialEndsAt = trialEndsAt
        self.writtenAt = writtenAt
    }

    public init(_ entitlement: EntitlementState, writtenAt: Date) {
        self.writtenAt = writtenAt
        switch entitlement {
        case .notStarted: state = .notStarted; trialEndsAt = nil
        case .trial(let end): state = .trial; trialEndsAt = end
        case .expired(let end): state = .expired; trialEndsAt = end
        case .unlocked: state = .unlocked; trialEndsAt = nil
        case .pending: state = .pending; trialEndsAt = nil
        }
    }

    /// The state as of `now`: a trial whose end has passed since the snapshot was written reads
    /// as expired. A malformed snapshot (trial or expired without a date) returns nil, so the
    /// caller falls back to asking StoreKit.
    public func resolvedState(now: Date) -> EntitlementState? {
        switch state {
        case .notStarted: return .notStarted
        case .unlocked: return .unlocked
        case .pending: return .pending
        case .trial:
            guard let end = trialEndsAt else { return nil }
            return now < end ? .trial(endsAt: end) : .expired(endedAt: end)
        case .expired:
            guard let end = trialEndsAt else { return nil }
            return .expired(endedAt: end)
        }
    }

    // MARK: JSON (millisecond dates, so a round trip is exact)

    public func encoded() throws -> Data {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }

    public init(data: Data) throws {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        self = try decoder.decode(EntitlementSnapshot.self, from: data)
    }
}
