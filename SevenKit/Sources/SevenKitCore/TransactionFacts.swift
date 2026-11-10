import Foundation

/// The two StoreKit product identifiers of a Seven app.
public struct SevenProductIDs: Sendable, Equatable {
    public var trial: String
    public var unlock: String
    public init(trial: String, unlock: String) {
        self.trial = trial
        self.unlock = unlock
    }
}

/// A StoreKit transaction reduced to what the entitlement rules need (a seam, so the rules are
/// testable without StoreKit).
public struct TransactionRecord: Sendable, Equatable {
    public var productID: String
    public var purchaseDate: Date
    public var revocationDate: Date?

    public init(productID: String, purchaseDate: Date, revocationDate: Date? = nil) {
        self.productID = productID
        self.purchaseDate = purchaseDate
        self.revocationDate = revocationDate
    }
}

/// PLAN §6.10: trial start = the trial transaction's `purchaseDate`; a transaction with a
/// `revocationDate` doesn't count; family-shared transactions arrive here like any other and
/// count. A pending purchase (Ask to Buy) sets `purchasePending` unless the unlock is owned.
public func entitlementFacts(
    from records: [TransactionRecord],
    products: SevenProductIDs,
    pendingProductIDs: Set<String> = []
) -> EntitlementFacts {
    let live = records.filter { $0.revocationDate == nil }
    let trialStart = live.filter { $0.productID == products.trial }.map(\.purchaseDate).min()
    let hasUnlock = live.contains { $0.productID == products.unlock }
    let pending = pendingProductIDs.contains(products.trial) || pendingProductIDs.contains(products.unlock)
    return EntitlementFacts(trialStartedAt: trialStart, hasUnlock: hasUnlock, purchasePending: pending && !hasUnlock)
}
