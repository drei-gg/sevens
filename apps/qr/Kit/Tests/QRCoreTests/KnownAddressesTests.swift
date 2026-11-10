import Foundation
import Testing
@testable import QRCore

private func link(_ text: String) -> Analysis {
    Analyzer(data: BundledData.shared).analyze(ScannedCode(text: text))
}

private func known(_ domains: String...) -> KnownAddresses {
    var k = KnownAddresses()
    for (i, d) in domains.enumerated() { k.add(d, at: Date(timeIntervalSince1970: Double(i))) }
    return k
}

@Suite struct KnownAddressesTests {
    @Test func addContainsRemoveAreCaseAndDotInsensitive() {
        var k = KnownAddresses()
        k.add("Example.COM.", at: Date(timeIntervalSince1970: 1))
        #expect(k.contains("example.com"))
        #expect(k.entries.map(\.domain) == ["example.com"])
        k.add("other.org", at: Date(timeIntervalSince1970: 2))
        k.add("example.com", at: Date(timeIntervalSince1970: 3))      // moves to the front, no duplicate
        #expect(k.entries.map(\.domain) == ["example.com", "other.org"])
        k.remove("EXAMPLE.com")
        #expect(!k.contains("example.com") && k.contains("other.org"))
        k.add("  ", at: Date())
        #expect(k.entries.count == 1)
        k.removeAll()
        #expect(k.isEmpty && !k.contains(""))
    }

    @Test func unicodeDomainsCompareInUnicodeForm() {
        let k = known("bücher.example")
        #expect(k.contains("BÜCHER.example"))
    }

    @Test func sizeIsBounded() {
        var k = KnownAddresses()
        for i in 0..<(KnownAddresses.maxEntries + 50) { k.add("d\(i).example", at: Date(timeIntervalSince1970: Double(i))) }
        #expect(k.entries.count == KnownAddresses.maxEntries)
        #expect(k.contains("d\(KnownAddresses.maxEntries + 49).example"))
        #expect(!k.contains("d0.example"))
    }

    @Test func offerForAListOnlyResult() {
        let a = link("https://bit.ly/abc")
        #expect(a.signals.contains { $0.id == .shortener })
        #expect(KnownAddresses().offer(for: a) == "bit.ly")
        #expect(known("bit.ly").offer(for: a) == nil)
        #expect(known("bit.ly").offer(for: Analyzer(data: BundledData.shared, known: known("bit.ly")).analyze(ScannedCode(text: "https://bit.ly/abc"))) == nil)
    }

    @Test func noOfferWithAStrongNonListSignalOrCleanOrIPOrOtherPayload() {
        #expect(KnownAddresses().offer(for: link("https://paypal.com@bit.ly/x")) == nil)
        #expect(KnownAddresses().offer(for: link("javascript:alert(1)")) == nil)
        #expect(KnownAddresses().offer(for: link("https://www.google.com/")) == nil)
        #expect(KnownAddresses().offer(for: link("http://192.168.1.1/")) == nil)
        #expect(KnownAddresses().offer(for: link("hello there")) == nil)
        #expect(KnownAddresses().offer(for: link("WIFI:T:nopass;S:Cafe;;")) == nil)
    }

    @Test func noOfferWhenAnotherWarningIsPresent() {
        // A list-based signal next to a rule-based warning (plain http): not offered.
        let a = link("http://bit.ly/abc")
        #expect(a.signals.contains { $0.id == .plainHTTP })
        #expect(KnownAddresses().offer(for: a) == nil)
    }

    @Test func applyDropsListSignalsAndKeepsTheRest() {
        let payload = link("http://bit.ly/abc").payload
        let signals = [Signal(.shortener), Signal(.plainHTTP), Signal(.unknownEnding)]
        let out = known("bit.ly").apply(to: signals, payload: payload)
        #expect(out.map(\.id) == [.plainHTTP, .unknownEnding, .knownByYou])
        #expect(KnownAddresses().apply(to: signals, payload: payload) == signals)
        #expect(known("other.org").apply(to: signals, payload: payload) == signals)
    }

    @Test func applyLeavesOtherPayloadsAlone() {
        let text = Payload.text(TextInfo(text: "bit.ly"))
        let signals = [Signal(.shortener)]
        #expect(known("bit.ly").apply(to: signals, payload: text) == signals)
    }

    @Test func fileRoundTripAndHostileInput() throws {
        let k = known("a.example", "b.example")
        let data = try KnownAddressesFile.encode(k)
        #expect(KnownAddressesFile.decode(data) == .addresses(k))
        #expect(KnownAddressesFile.decode(Data(#"{"schema":2,"entries":1}"#.utf8)) == .unknownSchema(2))
        for text in ["", "nope", "[]", "{}", #"{"schema":1}"#, #"{"schema":1,"entries":{}}"#] {
            #expect(KnownAddressesFile.decode(Data(text.utf8)) == .unreadable, "\(text)")
        }
        let lossy = Data(#"{"schema":1,"entries":[{"domain":"ok.example","added":"2024-01-01T00:00:00Z"},{"domain":5}]}"#.utf8)
        guard case .addresses(let got) = KnownAddressesFile.decode(lossy) else { Issue.record("no addresses"); return }
        #expect(got.entries.map(\.domain) == ["ok.example"])
    }

    @Test func historyExportCarriesKnownAddressesAndStaysReadable() throws {
        let k = known("a.example")
        let item = HistoryItem(kind: .scanned, payload: "https://x.example", symbology: .qr, typeID: "link",
                               level: .normal, signalIDs: [], date: Date(timeIntervalSince1970: 1_700_000_000))
        let data = try HistoryExport.json([item], known: k)
        #expect(HistoryFile.decode(data) == .items([item]))             // old readers still read it
        #expect(HistoryExport.knownAddresses(in: data) == k)
        #expect(try HistoryExport.json([item]) == HistoryFile.encode([item]))   // without known: unchanged
        #expect(HistoryExport.knownAddresses(in: try HistoryFile.encode([item])).isEmpty)
        #expect(HistoryExport.knownAddresses(in: Data("junk".utf8)).isEmpty)
    }
}
