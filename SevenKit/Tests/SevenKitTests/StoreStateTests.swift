import Foundation
import Testing
@testable import SevenKit

@MainActor
@Suite struct StoreStateTests {
    let clock = StoreTestClock()
    let day: TimeInterval = 86_400

    func makeStore(
        source: FixedSource? = nil,
        policy: TrialPolicy = .onPurchase,
        startStore: MemoryTrialStartStore = MemoryTrialStartStore(),
        group: String? = nil
    ) -> (Store, FixedSource) {
        let source = source ?? FixedSource(clock: clock.function)
        let store = Store(config: testConfig(group: group), trialPolicy: policy, source: source,
                          trialStartStore: startStore, defaults: makeTestDefaults(), clock: clock.function)
        return (store, source)
    }

    @Test func startLoadsPricesAndStaysNotStarted() async {
        let (store, _) = makeStore()
        await store.start()
        #expect(store.hasStarted)
        #expect(store.state == .notStarted)
        #expect(store.unlockDisplayPrice == "Test price")
        #expect(!store.paidFeaturesAvailable)
        #expect(store.canPurchase)
    }

    @Test func startingTheTrialOpensPaidFeaturesForSevenDays() async {
        let (store, _) = makeStore()
        await store.start()
        await store.startTrial()
        #expect(store.state == .trial(endsAt: clock.now.addingTimeInterval(7 * day)))
        #expect(store.paidFeaturesAvailable)
        #expect(store.trialDay == 1)
        clock.advance(7 * day - 1)
        await store.refresh()
        #expect(store.paidFeaturesAvailable)
        clock.advance(1)
        await store.refresh()
        #expect(store.state == .expired(endedAt: store.trialStartedAt!.addingTimeInterval(7 * day)))
        #expect(store.shouldPresentTrialEnded)
        store.markTrialEndedShown()
        #expect(!store.shouldPresentTrialEnded)
    }

    @Test func unlockDuringTheTrialWins() async {
        let (store, _) = makeStore()
        await store.start()
        await store.startTrial()
        await store.unlock()
        #expect(store.state == .unlocked)
        #expect(store.trialDay == nil)
        #expect(!store.canPurchase)
    }

    @Test func askToBuyShowsPendingUntilApproved() async {
        let (store, source) = makeStore()
        source.unlockOutcome = .pending
        await store.start()
        await store.unlock()
        #expect(store.state == .pending)
        #expect(!store.paidFeaturesAvailable)
        source.facts = EntitlementFacts(hasUnlock: true)   // Apple approves; the update listener delivers
        await store.refresh()
        #expect(store.state == .unlocked)
    }

    @Test func cancelledPurchaseChangesNothingAndShowsNoError() async {
        let (store, source) = makeStore()
        source.trialOutcome = .cancelled
        await store.start()
        await store.startTrial()
        #expect(store.state == .notStarted)
        #expect(store.lastError == nil)
    }

    @Test func failedPurchaseSetsAPlainError() async {
        let (store, source) = makeStore()
        source.error = SevenStoreError.productUnavailable
        await store.start()
        await store.unlock()
        #expect(store.lastError != nil)
        #expect(store.state == .notStarted)
    }

    @Test func updatesFromTheSourceReachTheStore() async {
        let (store, source) = makeStore()
        await store.start()
        source.facts = EntitlementFacts(hasUnlock: true)
        for _ in 0..<50 where store.state != .unlocked { try? await Task.sleep(for: .milliseconds(20)) }
        #expect(store.state == .unlocked)
    }

    @Test func restoreAsksTheSourceAndRefreshes() async {
        let (store, source) = makeStore()
        await store.start()
        source.facts = EntitlementFacts(hasUnlock: true)
        await store.restore()
        #expect(source.restoreCount == 1)
        #expect(store.state == .unlocked)
    }

    @Test func noPricesMeansNoBuyButton() async {
        let source = FixedSource(prices: nil)
        let (store, _) = makeStore(source: source)
        await store.start()
        #if DEBUG
        // Debug builds fall back to the local source, which has a (test) price.
        #expect(store.isUsingLocalSource)
        #else
        #expect(store.unlockDisplayPrice == nil)
        #expect(!store.canPurchase)
        #endif
    }

    @Test func snapshotIsWrittenToTheAppGroupAfterEachChange() async {
        let group = "seven.tests.group.\(UUID().uuidString)"
        let (store, _) = makeStore(group: group)
        let reader = EntitlementSnapshotStore(appGroup: group)
        await store.start()
        #expect(reader?.read()?.state == .notStarted)
        await store.startTrial()
        #expect(reader?.read()?.state == .trial)
        #expect(reader?.read()?.trialEndsAt == clock.now.addingTimeInterval(7 * day))
        await store.unlock()
        #expect(reader?.read()?.state == .unlocked)
        UserDefaults().removePersistentDomain(forName: group)
    }

    // MARK: TrialPolicy.onFirstLaunch (C12)

    @Test func firstLaunchPolicyStartsTheTrialByItself() async {
        let keep = MemoryTrialStartStore()
        let (store, _) = makeStore(policy: .onFirstLaunch, startStore: keep)
        await store.start()
        #expect(store.state == .trial(endsAt: clock.now.addingTimeInterval(7 * day)))
        #expect(keep.read() == clock.now)
        await store.startTrial()   // nothing to buy
        #expect(store.state == .trial(endsAt: clock.now.addingTimeInterval(7 * day)))
    }

    @Test func firstLaunchDateIsWrittenOnceAndSurvivesRelaunch() async {
        let keep = MemoryTrialStartStore()
        let (first, _) = makeStore(policy: .onFirstLaunch, startStore: keep)
        await first.start()
        let launched = clock.now
        clock.advance(8 * day)
        let (second, _) = makeStore(policy: .onFirstLaunch, startStore: keep)
        await second.start()
        #expect(keep.read() == launched)
        #expect(second.state == .expired(endedAt: launched.addingTimeInterval(7 * day)))
    }

    @Test func firstLaunchUnlockStillWins() async {
        let (store, _) = makeStore(policy: .onFirstLaunch)
        await store.start()
        await store.unlock()
        #expect(store.state == .unlocked)
    }

    @Test func onPurchaseNeverTouchesTheKeychainStore() async {
        let keep = MemoryTrialStartStore()
        let (store, _) = makeStore(startStore: keep)
        await store.start()
        #expect(keep.read() == nil)
        #expect(store.state == .notStarted)
    }
}
