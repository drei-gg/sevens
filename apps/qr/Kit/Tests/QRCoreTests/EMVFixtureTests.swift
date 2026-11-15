import Testing
@testable import QRCore

@Suite struct EMVFixtureTests {
    static let currencies = ["986": "BRL", "702": "SGD", "360": "IDR", "608": "PHP", "764": "THB", "458": "MYR", "704": "VND"]

    @Test func fileIsWellFormed() throws {
        let cases = try Fixtures.load("payments-emv.json")
        #expect(cases.count >= 20)
        #expect(cases.allSatisfy { $0.id.hasPrefix("emv-") })
        #expect(Set(cases.map(\.id)).count == cases.count)
    }

    @Test func everyPaymentCaseParses() throws {
        let cases = try Fixtures.load("payments-emv.json").filter { $0.expect.type == "payment" }
        #expect(!cases.isEmpty)
        for c in cases {
            let input = try #require(c.input, "\(c.id)")
            let info = try #require(EMVParser.parse(input, networks: [], currencies: Self.currencies), "\(c.id)")
            let fields = Payload.payment(info).fields
            for (k, v) in c.expect.fields ?? [:] {
                #expect(fields[k] == v, "\(c.id) fields[\(k)]")
            }
            let invalid = c.expect.signals.contains("paymentChecksumFail")
            #expect((info.checksum == .invalid) == invalid, "\(c.id)")
            #expect(c.expect.level == (invalid ? 2 : 0), "\(c.id)")
        }
    }

    @Test func textCasesHaveNoCRCTail() throws {
        for c in try Fixtures.load("payments-emv.json") where c.expect.type == "text" {
            let input = try #require(c.input)
            let info = EMVParser.parse(input, networks: [], currencies: [:])
            #expect(info == nil || info?.checksum == .absent, "\(c.id)")
        }
    }
}
