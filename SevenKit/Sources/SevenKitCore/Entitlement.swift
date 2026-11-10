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
    // Unlock wins over everything (pending, an expired trial). A running trial wins over a
    // pending purchase: waiting for Ask to Buy never takes away days already given. Pending
    // shows only when there's no running trial. A clock moved back before the start never
    // extends the trial (the end is always start + length) and never punishes it either.
    if facts.hasUnlock { return .unlocked }
    if let start = facts.trialStartedAt {
        let end = start.addingTimeInterval(trialLength)
        if now < end { return .trial(endsAt: end) }
        return facts.purchasePending ? .pending : .expired(endedAt: end)
    }
    return facts.purchasePending ? .pending : .notStarted
}
