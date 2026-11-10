import Foundation
import Testing
import SevenKitCore

@Suite struct EntitlementStateTests {
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    let week: TimeInterval = 7 * 86_400

    func state(_ facts: EntitlementFacts, at offset: TimeInterval) -> EntitlementState {
        entitlementState(facts, now: start.addingTimeInterval(offset))
    }

    @Test func nothingStartedIsNotStarted() {
        #expect(entitlementState(EntitlementFacts(), now: start) == .notStarted)
    }

    @Test func trialRunsForSevenDays() {
        let f = EntitlementFacts(trialStartedAt: start)
        let end = start.addingTimeInterval(week)
        #expect(state(f, at: 0) == .trial(endsAt: end))
        #expect(state(f, at: 3 * 86_400) == .trial(endsAt: end))
    }

    @Test func oneSecondBeforeSevenDaysIsStillTrial() {
        let f = EntitlementFacts(trialStartedAt: start)
        #expect(state(f, at: week - 1) == .trial(endsAt: start.addingTimeInterval(week)))
    }

    @Test func exactlySevenDaysIsExpired() {
        let f = EntitlementFacts(trialStartedAt: start)
        #expect(state(f, at: week) == .expired(endedAt: start.addingTimeInterval(week)))
    }

    @Test func oneSecondAfterSevenDaysIsExpired() {
        let f = EntitlementFacts(trialStartedAt: start)
        #expect(state(f, at: week + 1) == .expired(endedAt: start.addingTimeInterval(week)))
    }

    @Test func clockMovedBackNeverExtendsOrPunishes() {
        let f = EntitlementFacts(trialStartedAt: start)
        let s = state(f, at: -30 * 86_400)
        #expect(s == .trial(endsAt: start.addingTimeInterval(week)))
        #expect(s.paidFeaturesAvailable)
    }

    @Test func unlockDuringTrialWins() {
        let f = EntitlementFacts(trialStartedAt: start, hasUnlock: true)
        #expect(state(f, at: 86_400) == .unlocked)
    }

    @Test func unlockAfterExpiryWins() {
        let f = EntitlementFacts(trialStartedAt: start, hasUnlock: true)
        #expect(state(f, at: 30 * 86_400) == .unlocked)
    }

    @Test func unlockWinsOverPending() {
        let f = EntitlementFacts(trialStartedAt: start, hasUnlock: true, purchasePending: true)
        #expect(state(f, at: 0) == .unlocked)
    }

    @Test func pendingHidesNotStartedAndTrial() {
        #expect(entitlementState(EntitlementFacts(purchasePending: true), now: start) == .pending)
        let f = EntitlementFacts(trialStartedAt: start, purchasePending: true)
        #expect(state(f, at: 86_400) == .pending)
        #expect(!EntitlementState.pending.paidFeaturesAvailable)
    }

    @Test func customTrialLength() {
        let f = EntitlementFacts(trialStartedAt: start)
        let now = start.addingTimeInterval(61)
        #expect(entitlementState(f, now: now, trialLength: 60) == .expired(endedAt: start.addingTimeInterval(60)))
    }
}
