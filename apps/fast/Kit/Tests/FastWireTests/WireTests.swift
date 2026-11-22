import Foundation
import Testing
import FastWire

@Test func oversizedWatchMessagesAreMalformed() {
    #expect(throws: FastWatchFailure.malformed) {
        try FastWatchCodec.decode(FastWatchRequest.self, from: Data(repeating: 0, count: 32_769))
    }
}

@Test func watchRequestsRoundTrip() throws {
    let request = FastWatchRequest(sentAt: Date(timeIntervalSince1970: 1_800_000_000), action: .water, revision: UUID())
    #expect(try FastWatchCodec.decode(FastWatchRequest.self, from: FastWatchCodec.encode(request)) == request)
}

@Test func complicationSnapshotOnlyContainsTimerMetadataAndRejectsFutureVersions() throws {
    let source = FastWatchSnapshot(revision: UUID(), generatedAt: Date(), activeID: UUID(),
                                  start: Date(), goal: nil, waterMilliliters: 1_250, canCreateRecords: true)
    let timer = WatchTimerSnapshot(source)
    let data = try FastWatchCodec.encode(timer)
    #expect(try WatchTimerSnapshot.decode(data) == timer)
    var object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(Set(object.keys) == ["version", "generatedAt", "start"])
    object["version"] = 2
    let future = try JSONSerialization.data(withJSONObject: object)
    #expect(throws: FastWatchFailure.unsupported) { try WatchTimerSnapshot.decode(future) }
    #expect(throws: FastWatchFailure.malformed) {
        try WatchTimerSnapshot.decode(Data(repeating: 0, count: FastWatchCodec.maximumBytes + 1))
    }
    let idle = WatchTimerSnapshot(FastWatchSnapshot(revision: UUID(), generatedAt: Date(), activeID: nil,
        start: nil, goal: nil, waterMilliliters: 0, canCreateRecords: false))
    #expect(try WatchTimerSnapshot.decode(FastWatchCodec.encode(idle)).start == nil)
}
