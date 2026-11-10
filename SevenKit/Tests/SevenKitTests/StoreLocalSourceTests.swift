#if DEBUG
import Foundation
import Testing
@testable import SevenKit

@MainActor
@Suite struct StoreLocalSourceTests {
    let clock = StoreTestClock()

    func state(_ preset: LocalSource.Preset) -> EntitlementState {
        let local = LocalSource(defaults: makeTestDefaults(), clock: clock.function)
        local.apply(preset)
        return entitlementState(local.facts, now: clock.now)
    }

    @Test func presetsMapToTheirStates() {
        #expect(state(.notStarted) == .notStarted)
        guard case .trial = state(.trialDay1) else { Issue.record("day 1"); return }
        guard case .trial = state(.trialDay7) else { Issue.record("day 7"); return }
        guard case .expired = state(.expired) else { Issue.record("expired"); return }
        #expect(state(.unlocked) == .unlocked)
        #expect(state(.pending) == .pending)
    }

    @Test func dayOnePresetIsDayOneAndDaySevenIsDaySeven() {
        let local = LocalSource(defaults: makeTestDefaults(), clock: clock.function)
        local.apply(.trialDay1)
        #expect(TrialMath.dayNumber(startedAt: local.facts.trialStartedAt!, now: clock.now) == 1)
        local.apply(.trialDay7)
        #expect(TrialMath.dayNumber(startedAt: local.facts.trialStartedAt!, now: clock.now) == 7)
    }

    @Test func factsPersistInUserDefaults() {
        let defaults = makeTestDefaults()
        LocalSource(defaults: defaults, clock: clock.function).apply(.unlocked)
        #expect(LocalSource(defaults: defaults, clock: clock.function).facts.hasUnlock)
    }

    @Test func purchasesWorkLocally() async throws {
        let local = LocalSource(defaults: makeTestDefaults(), clock: clock.function)
        #expect(try await local.purchaseTrial() == .purchased)
        #expect(local.facts.trialStartedAt == clock.now)
        #expect(try await local.purchaseUnlock() == .purchased)
        #expect(local.facts.hasUnlock)
    }

    @Test func storeFallsBackToLocalWhenProductsDoNotLoadAndPanelDrivesIt() async {
        let source = FixedSource(prices: nil)
        let store = Store(config: testConfig(), source: source, trialStartStore: MemoryTrialStartStore(),
                          defaults: makeTestDefaults(), clock: clock.function)
        await store.start()
        #expect(store.isUsingLocalSource)
        #expect(store.canPurchase)   // Debug + LocalSource: the buy button works without a StoreKit price
        store.applyDebugPreset(.expired)
        guard case .expired = store.state else { Issue.record("expected expired"); return }
        store.applyDebugPreset(.unlocked)
        #expect(store.state == .unlocked)
    }
}
#endif
