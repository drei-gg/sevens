import Foundation
import Testing
@testable import SevenKit

@MainActor
@Suite struct StoreExtensionTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    func group() -> (String, EntitlementSnapshotStore?) {
        let name = "seven.tests.group.\(UUID().uuidString)"
        return (name, EntitlementSnapshotStore(appGroup: name))
    }

    @Test func aPaidSnapshotIsEnoughAndStoreKitIsNotAsked() async {
        let (name, snaps) = group()
        snaps?.write(EntitlementSnapshot(.unlocked, writtenAt: now))
        let source = FixedSource(facts: EntitlementFacts())
        let state = await ExtensionEntitlement.currentState(config: testConfig(group: name), now: now, snapshots: snaps, source: source)
        #expect(state == .unlocked)
    }

    @Test func aTrialSnapshotPastItsEndIsNotPaid() async {
        let (name, snaps) = group()
        let end = now.addingTimeInterval(-10)
        snaps?.write(EntitlementSnapshot(.trial(endsAt: end), writtenAt: now.addingTimeInterval(-86_400)))
        let source = FixedSource(facts: EntitlementFacts())
        let state = await ExtensionEntitlement.currentState(config: testConfig(group: name), now: now, snapshots: snaps, source: source)
        #expect(state == .expired(endedAt: end))
    }

    @Test func aStaleLockedSnapshotYieldsToStoreKit() async {
        let (name, snaps) = group()
        snaps?.write(EntitlementSnapshot(.notStarted, writtenAt: now))
        let source = FixedSource(facts: EntitlementFacts(hasUnlock: true))
        let state = await ExtensionEntitlement.currentState(config: testConfig(group: name), now: now, snapshots: snaps, source: source)
        #expect(state == .unlocked)
    }

    @Test func noSnapshotAsksStoreKitDirectly() async {
        let source = FixedSource(facts: EntitlementFacts(trialStartedAt: now))
        let state = await ExtensionEntitlement.currentState(config: testConfig(), now: now, snapshots: nil, source: source)
        #expect(state == .trial(endsAt: now.addingTimeInterval(7 * 86_400)))
    }

    @Test func noGroupAndNothingOwnedIsUnlockedInDebugOnly() async {
        let source = FixedSource(facts: EntitlementFacts())
        let state = await ExtensionEntitlement.currentState(config: testConfig(), now: now, snapshots: nil, source: source)
        #if DEBUG
        #expect(state == .unlocked)
        #else
        #expect(state == .notStarted)
        #endif
    }
}
