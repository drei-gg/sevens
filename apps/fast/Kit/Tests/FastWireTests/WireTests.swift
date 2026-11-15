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
