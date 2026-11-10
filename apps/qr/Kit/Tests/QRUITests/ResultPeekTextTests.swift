import Testing
import QRCore
@testable import QRUI

@Suite struct ResultPeekTextTests {
    @Test func ageWording() {
        #expect(PeekViewText.age(days: nil) == "Age unknown")
        #expect(PeekViewText.age(days: 0) == "Registered today")
        #expect(PeekViewText.age(days: 1) == "Registered 1 day ago")
        #expect(PeekViewText.age(days: 12) == "Registered 12 days ago")
    }

    @Test func connectionWording() {
        let https = PeekCapture(hops: [PeekHop(url: "https://a.example", status: 200)], stoppedBecause: .finished, tlsVersion: "TLS 1.3")
        #expect(PeekViewText.connection(https) == "Encrypted (TLS 1.3)")
        let http = PeekCapture(hops: [PeekHop(url: "http://a.example", status: nil)], stoppedBecause: .httpTarget)
        #expect(PeekViewText.connection(http) == "Not fetched: plain http")
    }

    @Test func asksListsKinds() {
        let signals = [Signal(.asksForSecret, ["kind": "password"]), Signal(.secretFormBrandMismatch, ["kind": "card"])]
        #expect(PeekViewText.asks(signals) == "a password, card details")
        #expect(PeekViewText.asks([]) == nil)
    }
}
