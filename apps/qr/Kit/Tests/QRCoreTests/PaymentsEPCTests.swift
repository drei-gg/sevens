import Testing
@testable import QRCore

@Suite struct PaymentsEPCTests {
    private let full = "BCD\n002\n1\nSCT\n\nRed Cross\nDE89 3704 0044 0532 0130 00\nEUR12.34\nCHAR\n\nDonation\nThanks"

    @Test func fullV002() throws {
        let p = try #require(EPCParser.parse(full))
        #expect(p.format == .epc)
        #expect(p.payeeName == "Red Cross")
        #expect(p.payeeAccount == "DE89370400440532013000")
        #expect(p.currency == "EUR")
        #expect(p.amount == "12.34")
        #expect(p.extra["purpose"] == "CHAR")
        #expect(p.note == "Donation")
        #expect(p.extra["information"] == "Thanks")
        #expect(p.extra["bic"] == nil)
        #expect(p.checksum == .valid)
    }

    @Test func v001WithBICAndCRLF() throws {
        let p = try #require(EPCParser.parse("BCD\r\n001\r\n1\r\nSCT\r\nBHBLDEHHXXX\r\nA\r\nDE89370400440532013000\r\nEUR1\r\n\r\nRF18539007547034\r\n"))
        #expect(p.extra["bic"] == "BHBLDEHHXXX")
        #expect(p.reference == "RF18539007547034")
        #expect(p.amount == "1")
        #expect(p.checksum == .valid)
    }

    @Test func badIBAN() throws {
        let p = try #require(EPCParser.parse(full.replacingOccurrences(of: "00\nEUR", with: "01\nEUR")))
        #expect(p.checksum == .invalid)
    }

    @Test func shortAndMissingAmount() throws {
        let p = try #require(EPCParser.parse("BCD\n002\n1\nSCT\n\nName\nDE89370400440532013000"))
        #expect(p.amount == nil)
        #expect(p.currency == nil)
        #expect(p.checksum == .valid)
    }

    @Test func noIBANIsAbsent() throws {
        #expect(try #require(EPCParser.parse("BCD\n002\n1\nSCT")).checksum == .absent)
    }

    @Test func rejected() {
        #expect(EPCParser.parse("") == nil)
        #expect(EPCParser.parse("BCD\n002\n1\nXYZ") == nil)
        #expect(EPCParser.parse("XCD\n002\n1\nSCT") == nil)
    }
}
