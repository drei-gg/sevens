import Foundation

// Contract (PLAN §5.4), frozen at qr-contract-1. Bodies: WP 1.9.

public enum EntitlementState: Sendable, Equatable {
    case notStarted
    case trial(endsAt: Date)
    case expired(endedAt: Date)
    case unlocked
    case pending

    /// Trial or unlocked.
    public var paidFeaturesAvailable: Bool {
        switch self {
        case .trial, .unlocked: return true
        case .notStarted, .expired, .pending: return false
        }
    }
}

public struct EntitlementFacts: Sendable, Equatable {
    public var trialStartedAt: Date?
    public var hasUnlock: Bool
    public var purchasePending: Bool

    public init(trialStartedAt: Date? = nil, hasUnlock: Bool = false, purchasePending: Bool = false) {
        self.trialStartedAt = trialStartedAt
        self.hasUnlock = hasUnlock
        self.purchasePending = purchasePending
    }
}

public func entitlementState(_ facts: EntitlementFacts, now: Date, trialLength: TimeInterval = 7 * 86_400) -> EntitlementState {
    fatalError("WP 1.9")
}
