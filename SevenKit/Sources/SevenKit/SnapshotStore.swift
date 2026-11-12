import Foundation
import SevenKitCore

/// The app group's `UserDefaults` copy of the entitlement (SPEC §13). The app writes after
/// every state change; the Make extension reads.
public struct EntitlementSnapshotStore: Sendable {
    public static let key = "seven.entitlement.snapshot"
    private let suite: String

    /// Nil when the app has no group.
    public init?(appGroup: String?) {
        guard let appGroup, !appGroup.isEmpty else { return nil }
        suite = appGroup
    }

    public func write(_ snapshot: EntitlementSnapshot) {
        guard let data = try? snapshot.encoded(), let defaults = UserDefaults(suiteName: suite) else { return }
        defaults.set(data, forKey: Self.key)
    }

    public func read() -> EntitlementSnapshot? {
        guard let defaults = UserDefaults(suiteName: suite),
              let data = defaults.data(forKey: Self.key) else { return nil }
        return try? EntitlementSnapshot(data: data)
    }
}

/// What the Make extension calls instead of building a `Store`.
public enum ExtensionEntitlement {
    /// Snapshot first. When it says paid features are available, that is the answer. When there
    /// is no snapshot (no app group under free signing, or the app never ran) or it says "not
    /// available", ask StoreKit directly (C7). In Debug with no snapshot and no paid answer from
    /// StoreKit, the answer is `.unlocked`, so a free-signed build stays usable.
    @MainActor
    public static func currentState(
        config: SevenConfig,
        now: Date = Date(),
        snapshots: EntitlementSnapshotStore? = nil,
        source: (any EntitlementSource)? = nil
    ) async -> EntitlementState {
        if DevUnlock.isActive { return .unlocked }
        let store = snapshots ?? EntitlementSnapshotStore(appGroup: config.appGroup)
        let fromSnapshot = store?.read()?.resolvedState(now: now)
        if let fromSnapshot, fromSnapshot.paidFeaturesAvailable { return fromSnapshot }

        let live = source ?? StoreKitSource(config: config)
        let state = entitlementState(await live.currentFacts(), now: now, trialLength: config.trialLength)
        if state.paidFeaturesAvailable { return state }
        #if DEBUG
        if fromSnapshot == nil { return .unlocked }
        #endif
        return fromSnapshot ?? state
    }
}
