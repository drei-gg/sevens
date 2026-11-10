import Foundation
import Testing
import SevenKitCore

@Suite struct EntitlementSnapshotTests {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000.123)
    let all: [EntitlementState] = [
        .notStarted, .trial(endsAt: Date(timeIntervalSince1970: 1_800_604_800.5)),
        .expired(endedAt: Date(timeIntervalSince1970: 1_800_000_000)), .unlocked, .pending,
    ]

    @Test func codableRoundTripKeepsEveryState() throws {
        for s in all {
            let snap = EntitlementSnapshot(s, writtenAt: t0)
            let back = try EntitlementSnapshot(data: snap.encoded())
            #expect(back == snap)
            #expect(back.resolvedState(now: t0) == s)
        }
    }

    @Test func jsonHasTheThreeDocumentedKeys() throws {
        let snap = EntitlementSnapshot(.trial(endsAt: t0), writtenAt: t0)
        let obj = try JSONSerialization.jsonObject(with: snap.encoded()) as? [String: Any]
        #expect(Set(obj?.keys.map { $0 } ?? []) == ["state", "trialEndsAt", "writtenAt"])
        #expect(obj?["state"] as? String == "trial")
    }

    @Test func aTrialThatEndedSinceTheWriteReadsAsExpired() {
        let end = t0.addingTimeInterval(100)
        let snap = EntitlementSnapshot(.trial(endsAt: end), writtenAt: t0)
        #expect(snap.resolvedState(now: end.addingTimeInterval(-1)) == .trial(endsAt: end))
        #expect(snap.resolvedState(now: end) == .expired(endedAt: end))
    }

    @Test func malformedSnapshotResolvesToNil() {
        #expect(EntitlementSnapshot(state: .trial, trialEndsAt: nil, writtenAt: t0).resolvedState(now: t0) == nil)
        #expect(EntitlementSnapshot(state: .expired, trialEndsAt: nil, writtenAt: t0).resolvedState(now: t0) == nil)
    }

    @Test func garbageDataThrows() {
        #expect(throws: (any Error).self) { _ = try EntitlementSnapshot(data: Data("nope".utf8)) }
    }
}
