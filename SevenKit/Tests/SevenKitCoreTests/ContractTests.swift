import Foundation
import Testing
import SevenKitCore

@Test func paidFeaturesFollowTheState() {
    #expect(EntitlementState.unlocked.paidFeaturesAvailable)
    #expect(EntitlementState.trial(endsAt: Date()).paidFeaturesAvailable)
    #expect(!EntitlementState.notStarted.paidFeaturesAvailable)
    #expect(!EntitlementState.expired(endedAt: Date()).paidFeaturesAvailable)
    #expect(!EntitlementState.pending.paidFeaturesAvailable)
}
