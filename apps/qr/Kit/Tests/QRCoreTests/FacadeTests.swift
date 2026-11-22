import Foundation
import Testing
@testable import QRCore

// The rules are the Rust core's and are tested there (apps/qr/core/tests, every QRCore test under
// its Swift name). These tests prove the bindings link and the façade converts: every fixture
// through the Swift API, one Peek pair, and History and known-address files round-tripped.

private struct FixtureCase: Decodable {
    struct Expect: Decodable {
        var type: String
        var level: Int
        var signals: [String]
        var info: [String]?
        var fields: [String: String]?
    }
    var id: String
    var input: String?
    var inputBase64: String?
    var known: [String]?
    var expect: Expect

    var code: ScannedCode {
        if let input { return ScannedCode(text: input) }
        let bytes = inputBase64.flatMap { Data(base64Encoded: $0) }.map { [UInt8]($0) } ?? []
        return ScannedCode(text: String(bytes: bytes, encoding: .utf8), bytes: bytes, symbology: .qr)
    }
}

private enum Fixtures {
    static var directory: URL? { Bundle.module.url(forResource: "Fixtures", withExtension: nil) }

    static func all() throws -> [FixtureCase] {
        guard let dir = directory else { return [] }
        let names = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".json") }.sorted()
        return try names.flatMap { try JSONDecoder().decode([FixtureCase].self, from: Data(contentsOf: dir.appendingPathComponent($0))) }
    }
}

@Test func everyFixtureRunsThroughTheSwiftAPI() throws {
    let cases = try Fixtures.all()
    #expect(cases.count > 400)
    let plain = Analyzer(data: BundledData.shared)
    for c in cases {
        var known = KnownAddresses()
        for (i, domain) in (c.known ?? []).enumerated() { known.add(domain, at: Date(timeIntervalSince1970: Double(i))) }
        let analyzer = c.known == nil ? plain : Analyzer(data: BundledData.shared, known: known)
        let a = analyzer.analyze(c.code)
        #expect(a.payload.typeID == c.expect.type, "\(c.id)")
        #expect(a.level.rawValue == c.expect.level, "\(c.id)")
        #expect(Set(a.allSignals.filter { $0.severity != .info }.map(\.id.rawValue)) == Set(c.expect.signals), "\(c.id)")
        if let info = c.expect.info {
            #expect(Set(a.allSignals.filter { $0.severity == .info }.map(\.id.rawValue)) == Set(info), "\(c.id)")
        }
        let fields = a.payload.fields
        for (k, v) in c.expect.fields ?? [:] { #expect(fields[k] == v, "\(c.id) \(k)") }
    }
}

@Test func swiftSpellingsReachTheCore() {
    #expect(SignalID(rawValue: "plainHTTP") == .plainHttp)
    #expect(SignalID.riskyTld.rawValue == "riskyTLD")
    #expect(SignalID.allCases.count == 34)
    #expect(Signal(.shortener).severity == .warning)
    #expect([Signal(.assumedHttps), Signal(.lookalikeBrand)].inStableOrder().map(\.id) == [.lookalikeBrand, .assumedHttps])
    #expect(Level.likelyScam > .careful && Level.careful > .normal)
    #expect(Level.allCases.map(\.rawValue) == [0, 1, 2])
    let link = LinkInfo(original: "https://Example.com", scheme: "https", hostASCII: "example.com", hostUnicode: "example.com",
                        port: 8443, normalized: "https://example.com/")
    #expect(link.port == 8443 && link.hostASCII == "example.com" && !link.isIPAddress)
    #expect(Payload.link(link).typeID == "link")
    #expect(PayloadEncoder.encode(.wifi(WiFiInfo(ssid: "Home", security: "WPA", password: "pw"))).hasPrefix("WIFI:"))
    #expect(CryptoInfo(network: "Bitcoin", address: "bc1qar0srrr7").addressGroups == ["bc1q", "ar0s", "rrr7"])
    #expect(ListsBundle.maxBytes == ListsStore.maxBytes)
    #expect(BundledData.shared.lists.version >= BundledData.bundledListsVersion().version)
    #expect(!BundledData.shared.trackingParams.isEmpty)
    #expect(BundledData.shared.publicSuffixes.match(hostASCII: "www.example.co.uk").registrableDomain == "example.co.uk")
    #expect(QuietZone.modules(for: .qr) == (4, 4))
    let m = QuietZone.add(to: BitMatrix(width: 1, height: 1, bits: [true]), horizontal: 1, vertical: 2)
    #expect(m.columns == 3 && m.rows == 5 && m[1, 2] && !m[0, 0] && !m[9, 9])
    #expect(SheetLayout.cells(grid: .grid3x4, paper: .a4).count == SheetGrid.grid3x4.columns * SheetGrid.grid3x4.rows)
    #expect(RGBColor.white.hex == "#ffffff")
}

@Test func aPeekPairRunsThroughTheSwiftAPI() throws {
    let dir = try #require(Fixtures.directory).appendingPathComponent("peek")
    let html = try Data(contentsOf: dir.appendingPathComponent("login.html"))
    let original = Analyzer(data: BundledData.shared).analyze(ScannedCode(text: "https://example-bank.com/login"))
    let capture = PeekCapture(hops: [PeekHop(url: "https://example-bank.com/login", status: 200)], stoppedBecause: .finished,
                              contentType: "text/html; charset=utf-8", body: html)
    let report = PeekAnalyzer(data: BundledData.shared).report(original: original, capture: capture, ageDays: 4000)
    #expect(report.title == "Sign in")
    #expect(report.signals.map(\.id).contains(.asksForSecret))
}

@Test func specGapAPIsReachTheCore() {
    let analyzer = Analyzer(data: BundledData.shared)
    let https = analyzer.analyze(ScannedCode(text: "https://bit.ly/abc"))
    #expect(https.canPeek)
    #expect(analyzer.analyze(ScannedCode(text: "http://example.com/")).canPeek)
    #expect(!analyzer.analyze(ScannedCode(text: "javascript:alert(1)")).canPeek)
    let capture = PeekCapture(hops: [PeekHop(url: "https://bit.ly/abc", status: 301),
                                     PeekHop(url: "https://landing.example.org/", status: 200)], stoppedBecause: .finished)
    #expect(PeekAge.link(original: https, capture: capture, data: BundledData.shared)?.hostASCII == "landing.example.org")
    var known = KnownAddresses()
    known.add("example.org", at: Date(timeIntervalSince1970: 0))
    let report = PeekAnalyzer.withKnown(data: BundledData.shared, known: known)
        .report(original: https, capture: capture, ageDays: 4000)
    #expect(report.hopAnalyses.count == 2)
    #expect(report.hopAnalyses[1].signals.map(\.id).contains(.knownByYou))
    #expect(PhoneNumber.international("0063 917 123 4567") == "+639171234567")
    #expect(PhoneNumber.international("0917 123 4567") == nil)
    let csv = HistoryExport.csv([], known: known)
    #expect(csv.hasSuffix(",knownAddress,,,example.org,,,,\r\n"))
    #expect(batchReview(text: "").issueKind == .empty)
}

@Test func historyAndKnownFilesRoundTrip() throws {
    let analysis = Analyzer(data: BundledData.shared).analyze(ScannedCode(text: "http://paypa1-secure.example.zip/login"))
    let item = HistoryItem(analysis: analysis, kind: .scanned, date: Date(timeIntervalSince1970: 1_800_000_000))
    #expect(item.typeID == "link" && !item.signalIDs.isEmpty && item.scannedCode.text == analysis.code.text)
    #expect(HistoryFile.decode(try HistoryFile.encode([item])) == .items([item]))
    #expect(HistoryFile.decode(Data("nope".utf8)) == .unreadable)
    var known = KnownAddresses()
    known.add("Example.COM.", at: Date(timeIntervalSince1970: 1_800_000_000))
    #expect(known.contains("example.com") && known.entries.count == 1)
    #expect(KnownAddressesFile.decode(try KnownAddressesFile.encode(known)) == .addresses(known: known))
    known.removeAll()
    #expect(known.isEmpty)
    #expect(HistoryFile.fileName == "history.v1.json" && KnownAddressesFile.fileName == "known.v1.json")
}
