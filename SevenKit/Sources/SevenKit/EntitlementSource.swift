import Foundation
import SevenKitCore

/// Display prices straight from the source (StoreKit's `Product.displayPrice`). Never hard-coded.
public struct SevenPrices: Sendable, Equatable {
    public var trial: String?
    public var unlock: String?
    public init(trial: String? = nil, unlock: String? = nil) {
        self.trial = trial
        self.unlock = unlock
    }
}

public enum PurchaseOutcome: Sendable, Equatable {
    case purchased
    /// Ask to Buy or another deferred approval.
    case pending
    case cancelled
}

public enum SevenStoreError: Error, Sendable, Equatable {
    case productUnavailable
    case unverified
}

/// Where the facts behind `EntitlementState` come from.
@MainActor
public protocol EntitlementSource: AnyObject {
    /// Nil when the products did not load (no App Store Connect yet, offline, …).
    func loadPrices() async -> SevenPrices?
    func currentFacts() async -> EntitlementFacts
    /// Facts again, whenever they change outside a call (approval of a pending purchase, a
    /// refund, a purchase on another device). One consumer.
    func factUpdates() -> AsyncStream<EntitlementFacts>
    func purchaseTrial() async throws -> PurchaseOutcome
    func purchaseUnlock() async throws -> PurchaseOutcome
    func restore() async throws
}

/// A source with fixed, settable facts: SwiftUI previews and tests. No StoreKit involved.
@MainActor
public final class FixedSource: EntitlementSource {
    public var facts: EntitlementFacts { didSet { continuation?.yield(facts) } }
    public var prices: SevenPrices?
    public var trialOutcome: PurchaseOutcome = .purchased
    public var unlockOutcome: PurchaseOutcome = .purchased
    public var error: (any Error)?
    public private(set) var restoreCount = 0
    private var continuation: AsyncStream<EntitlementFacts>.Continuation?
    private let clock: @Sendable () -> Date

    public init(
        facts: EntitlementFacts = EntitlementFacts(),
        prices: SevenPrices? = SevenPrices(trial: "Free", unlock: "Test price"),
        clock: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.facts = facts
        self.prices = prices
        self.clock = clock
    }

    public func loadPrices() async -> SevenPrices? { prices }
    public func currentFacts() async -> EntitlementFacts { facts }

    public func factUpdates() -> AsyncStream<EntitlementFacts> {
        let (stream, continuation) = AsyncStream.makeStream(of: EntitlementFacts.self)
        self.continuation = continuation
        return stream
    }

    public func purchaseTrial() async throws -> PurchaseOutcome {
        if let error { throw error }
        switch trialOutcome {
        case .purchased: facts.trialStartedAt = clock()
        case .pending: facts.purchasePending = true
        case .cancelled: break
        }
        return trialOutcome
    }

    public func purchaseUnlock() async throws -> PurchaseOutcome {
        if let error { throw error }
        switch unlockOutcome {
        case .purchased: facts.hasUnlock = true; facts.purchasePending = false
        case .pending: facts.purchasePending = true
        case .cancelled: break
        }
        return unlockOutcome
    }

    public func restore() async throws {
        restoreCount += 1
        if let error { throw error }
    }
}
