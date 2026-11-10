// StoreKitTest's own headers use deprecated API, and the build's warnings-as-errors flags turn
// that into a module build failure (Xcode 26.5 SDK). So this file only compiles on request:
//   scripts/vm sh "cd SevenKit && xcodebuild -scheme SevenKit-Package -destination '…' \
//        OTHER_SWIFT_FLAGS=-DSEVEN_STOREKIT_TESTS test"
// Observed (WP 1.9): compiled that way, SKTestSession(contentsOf:) loads QR.storekit but in an
// unhosted package test bundle Product.products(for:) returns nothing and the serialized suite
// stalls after the first test. Needs a host app to be useful; the fact-mapping rules these tests
// target are covered without StoreKit in EntitlementFactsTests (SevenKitCoreTests).
#if SEVEN_STOREKIT_TESTS
import Foundation
import StoreKit
import StoreKitTest
import Testing
@testable import SevenKit

/// Runs against `apps/qr/QR.storekit` with `SKTestSession` (PLAN §8, WP 1.9). One suite,
/// serialized: StoreKit test state is process-wide.
@MainActor
@Suite(.serialized) struct StoreKitSourceTests {
    static let storekitFile: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("apps/qr/QR.storekit")

    let session: SKTestSession
    let source: StoreKitSource

    init() throws {
        session = try SKTestSession(contentsOf: Self.storekitFile)
        session.resetToDefaultState()
        session.clearTransactions()
        session.disableDialogs = true
        source = StoreKitSource(config: testConfig())
    }

    @Test func productsLoadWithDisplayPricesFromStoreKit() async {
        let prices = await source.loadPrices()
        #expect(prices?.unlock?.contains("7") == true)
        #expect(prices?.trial != nil)
    }

    @Test func nothingOwnedMeansNoFacts() async {
        #expect(await source.currentFacts() == EntitlementFacts())
    }

    @Test func trialPurchaseStartsTheTrialAtThePurchaseDate() async throws {
        _ = await source.loadPrices()
        let before = Date()
        #expect(try await source.purchaseTrial() == .purchased)
        let facts = await source.currentFacts()
        let started = try #require(facts.trialStartedAt)
        #expect(abs(started.timeIntervalSince(before)) < 60)
        #expect(!facts.hasUnlock)
        #expect(entitlementState(facts, now: Date()).paidFeaturesAvailable)
    }

    @Test func unlockPurchaseUnlocks() async throws {
        _ = await source.loadPrices()
        #expect(try await source.purchaseUnlock() == .purchased)
        #expect(await source.currentFacts().hasUnlock)
    }

    @Test func askToBuyIsPendingUntilApproved() async throws {
        _ = await source.loadPrices()
        session.askToBuyEnabled = true
        #expect(try await source.purchaseUnlock() == .pending)
        let pending = await source.currentFacts()
        #expect(pending.purchasePending)
        #expect(entitlementState(pending, now: Date()) == .pending)
        let id = try #require(session.allTransactions().first?.identifier)
        try session.approveAskToBuyTransaction(identifier: id)
        for _ in 0..<50 where !(await source.currentFacts().hasUnlock) { try await Task.sleep(for: .milliseconds(100)) }
        #expect(await source.currentFacts().hasUnlock)
    }

    @Test func aRefundedUnlockNoLongerCounts() async throws {
        _ = await source.loadPrices()
        _ = try await source.purchaseUnlock()
        let id = try #require(session.allTransactions().first?.identifier)
        try session.refundTransaction(identifier: id)
        #expect(await source.currentFacts().hasUnlock == false)
    }

    @Test func restoreSucceedsWithoutErrors() async throws {
        _ = await source.loadPrices()
        _ = try await source.purchaseUnlock()
        try await source.restore()
        #expect(await source.currentFacts().hasUnlock)
    }

    @Test func storeUsesStoreKitPricesAndNeverFallsBackToLocal() async {
        let store = Store(config: testConfig(), source: source, trialStartStore: MemoryTrialStartStore(), defaults: makeTestDefaults())
        await store.start()
        #expect(store.unlockDisplayPrice?.contains("7") == true)
        #expect(!store.isUsingLocalSource)
        await store.startTrial()
        #expect(store.paidFeaturesAvailable)
    }
}
#endif
