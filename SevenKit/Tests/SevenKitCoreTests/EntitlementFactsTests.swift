import Foundation
import Testing
import SevenKitCore

@Suite struct EntitlementFactsTests {
    let ids = SevenProductIDs(trial: "t", unlock: "u")
    let d0 = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func trialStartIsThePurchaseDate() {
        let f = entitlementFacts(from: [TransactionRecord(productID: "t", purchaseDate: d0)], products: ids)
        #expect(f == EntitlementFacts(trialStartedAt: d0))
    }

    @Test func revokedTransactionsDoNotCount() {
        let records = [
            TransactionRecord(productID: "t", purchaseDate: d0, revocationDate: d0.addingTimeInterval(5)),
            TransactionRecord(productID: "u", purchaseDate: d0, revocationDate: d0.addingTimeInterval(5)),
        ]
        #expect(entitlementFacts(from: records, products: ids) == EntitlementFacts())
    }

    @Test func earliestTrialPurchaseWins() {
        let records = [
            TransactionRecord(productID: "t", purchaseDate: d0.addingTimeInterval(50)),
            TransactionRecord(productID: "t", purchaseDate: d0),
        ]
        #expect(entitlementFacts(from: records, products: ids).trialStartedAt == d0)
    }

    @Test func unknownProductsAreIgnored() {
        let f = entitlementFacts(from: [TransactionRecord(productID: "other", purchaseDate: d0)], products: ids)
        #expect(f == EntitlementFacts())
    }

    @Test func pendingSetsTheFlagUnlessUnlocked() {
        #expect(entitlementFacts(from: [], products: ids, pendingProductIDs: ["u"]).purchasePending)
        let owned = [TransactionRecord(productID: "u", purchaseDate: d0)]
        let f = entitlementFacts(from: owned, products: ids, pendingProductIDs: ["u"])
        #expect(f.hasUnlock && !f.purchasePending)
        #expect(!entitlementFacts(from: [], products: ids, pendingProductIDs: ["x"]).purchasePending)
    }
}
