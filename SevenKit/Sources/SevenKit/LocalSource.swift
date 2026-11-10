#if DEBUG
import Foundation
import SevenKitCore

/// Debug builds only: facts kept in `UserDefaults`, driven by `SevenDebugPanel`. `Store` falls
/// back to it when the products don't load, which is every on-device run until App Store
/// Connect exists.
@MainActor
public final class LocalSource: EntitlementSource {
    public enum Preset: String, CaseIterable, Identifiable, Sendable {
        case notStarted, trialDay1, trialDay7, expired, unlocked, pending
        public var id: String { rawValue }
    }

    private enum Key {
        static let trialStart = "seven.local.trialStartedAt"
        static let unlock = "seven.local.hasUnlock"
        static let pending = "seven.local.purchasePending"
    }

    private let defaults: UserDefaults
    private let clock: @Sendable () -> Date
    private var continuation: AsyncStream<EntitlementFacts>.Continuation?

    public init(defaults: UserDefaults = .standard, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.defaults = defaults
        self.clock = clock
    }

    public var facts: EntitlementFacts {
        get {
            let start = defaults.object(forKey: Key.trialStart) as? Double
            return EntitlementFacts(
                trialStartedAt: start.map { Date(timeIntervalSince1970: $0) },
                hasUnlock: defaults.bool(forKey: Key.unlock),
                purchasePending: defaults.bool(forKey: Key.pending)
            )
        }
        set {
            if let start = newValue.trialStartedAt {
                defaults.set(start.timeIntervalSince1970, forKey: Key.trialStart)
            } else {
                defaults.removeObject(forKey: Key.trialStart)
            }
            defaults.set(newValue.hasUnlock, forKey: Key.unlock)
            defaults.set(newValue.purchasePending, forKey: Key.pending)
            continuation?.yield(newValue)
        }
    }

    /// Facts for a preset, relative to now.
    public func facts(for preset: Preset) -> EntitlementFacts {
        let now = clock()
        let day: TimeInterval = 86_400
        switch preset {
        case .notStarted: return EntitlementFacts()
        case .trialDay1: return EntitlementFacts(trialStartedAt: now)
        case .trialDay7: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-6 * day - 3_600))
        case .expired: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-8 * day))
        case .unlocked: return EntitlementFacts(trialStartedAt: now.addingTimeInterval(-2 * day), hasUnlock: true)
        case .pending: return EntitlementFacts(purchasePending: true)
        }
    }

    public func apply(_ preset: Preset) { facts = facts(for: preset) }

    public func loadPrices() async -> SevenPrices? { SevenPrices(trial: "Free", unlock: "Test price") }
    public func currentFacts() async -> EntitlementFacts { facts }

    public func factUpdates() -> AsyncStream<EntitlementFacts> {
        let (stream, continuation) = AsyncStream.makeStream(of: EntitlementFacts.self)
        self.continuation = continuation
        return stream
    }

    public func purchaseTrial() async throws -> PurchaseOutcome {
        var f = facts
        f.trialStartedAt = clock()
        facts = f
        return .purchased
    }

    public func purchaseUnlock() async throws -> PurchaseOutcome {
        var f = facts
        f.hasUnlock = true
        f.purchasePending = false
        facts = f
        return .purchased
    }

    public func restore() async throws { continuation?.yield(facts) }
}
#endif
