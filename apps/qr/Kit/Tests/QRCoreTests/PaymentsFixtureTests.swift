import Testing
@testable import QRCore

@Suite struct PaymentsFixtureTests {
    static let schemes = [CryptoScheme(scheme: "litecoin", name: "Litecoin")]

    static var cases: [FixtureCase] {
        ((try? Fixtures.load("payments-other.json")) ?? [])
            .filter { ["payment", "crypto", "text"].contains($0.expect.type) }
    }

    @Test func fixtureFileLoads() throws {
        #expect(try Fixtures.load("payments-other.json").count >= 25)
    }

    @Test(arguments: PaymentsFixtureTests.cases)
    func fixtureMatches(_ c: FixtureCase) throws {
        let input = try #require(c.input)
        let id = c.id
        var fields: [String: String]?
        let level: Int, signals: [String]
        if id.hasPrefix("upi-") { fields = UPIParser.parse(input)?.fields }
        else if id.hasPrefix("epc-") { fields = EPCParser.parse(input)?.fields }
        else if id.hasPrefix("swiss-") { fields = SwissQRParser.parse(input)?.fields }
        else { fields = (CryptoURIParser.parse(input, schemes: Self.schemes) ?? CryptoAddress.detect(input))?.fields }
        if c.expect.type == "text" {
            #expect(fields == nil, "\(id) must not parse")
            return
        }
        let got = try #require(fields, "\(id) did not parse")
        for (k, v) in c.expect.fields ?? [:] { #expect(got[k] == v, "\(id) fields[\(k)]") }
        // Level and signals follow from the checksum: invalid → level 2 + paymentChecksumFail.
        let invalid = got["checksum"] == "invalid"
        level = invalid ? 2 : 0
        signals = invalid ? ["paymentChecksumFail"] : []
        #expect(c.expect.level == level, "\(id) level")
        #expect(c.expect.signals == signals, "\(id) signals")
    }
}
