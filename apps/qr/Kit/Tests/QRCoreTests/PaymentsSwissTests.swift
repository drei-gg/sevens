import Testing
@testable import QRCore

@Suite struct PaymentsSwissTests {
    private func bill(iban: String = "CH4431999123000889012", ref type: String = "QRR",
                      _ ref: String = "210000000003139471430009017") -> String {
        (["SPC", "0200", "1", iban, "S", "Robert Schneider AG", "Rue du Lac", "1268", "2501", "Biel", "CH"]
            + Array(repeating: "", count: 7)
            + ["1949.75", "CHF", "S", "Pia Rutschmann", "Hauptstrasse", "1", "8001", "Zürich", "CH", type, ref, "Invoice", "EPD", "Billing"])
            .joined(separator: "\n")
    }

    @Test func qrrValid() throws {
        let p = try #require(SwissQRParser.parse(bill()))
        #expect(p.format == .swiss)
        #expect(p.payeeAccount == "CH4431999123000889012")
        #expect(p.payeeName == "Robert Schneider AG")
        #expect(p.city == "Biel")
        #expect(p.country == "CH")
        #expect(p.amount == "1949.75")
        #expect(p.currency == "CHF")
        #expect(p.extra["debtorName"] == "Pia Rutschmann")
        #expect(p.extra["referenceType"] == "QRR")
        #expect(p.note == "Invoice")
        #expect(p.extra["billingInfo"] == "Billing")
        #expect(p.checksum == .valid)
    }

    @Test func qrrBadCheckDigit() throws {
        #expect(try #require(SwissQRParser.parse(bill(ref: "QRR", "210000000003139471430009018"))).checksum == .invalid)
        #expect(try #require(SwissQRParser.parse(bill(ref: "QRR", "2100"))).checksum == .invalid)
    }

    @Test func scor() throws {
        #expect(try #require(SwissQRParser.parse(bill(ref: "SCOR", "RF18539007547034"))).checksum == .valid)
        #expect(try #require(SwissQRParser.parse(bill(ref: "SCOR", "RF19539007547034"))).checksum == .invalid)
        #expect(try #require(SwissQRParser.parse(bill(ref: "SCOR", "RF47 ABC1 23"))).checksum == .valid)
    }

    @Test func nonReference() throws {
        let p = try #require(SwissQRParser.parse(bill(ref: "NON", "")))
        #expect(p.extra["referenceType"] == "NON")
        #expect(p.reference == nil)
        #expect(p.checksum == .valid)
    }

    @Test func badIBAN() throws {
        #expect(try #require(SwissQRParser.parse(bill(iban: "CH4431999123000889013"))).checksum == .invalid)
    }

    @Test func combinedAddress() throws {
        let t = "SPC\r\n0200\r\n1\r\nCH9300762011623852957\r\nK\r\nMuster GmbH\r\nHauptstrasse 5\r\n8000 Zürich\r\n\r\n\r\nCH"
        let p = try #require(SwissQRParser.parse(t))
        #expect(p.city == nil)
        #expect(p.extra["creditorAddress"] == "Hauptstrasse 5\n8000 Zürich")
        #expect(p.country == "CH")
    }

    @Test func truncated() throws {
        let p = try #require(SwissQRParser.parse("SPC\n0200\n1\nCH9300762011623852957\nS\nMuster"))
        #expect(p.payeeName == "Muster")
        #expect(p.amount == nil)
        #expect(p.checksum == .valid)
        #expect(try #require(SwissQRParser.parse("SPC\n0200\n1")).checksum == .absent)
    }

    @Test func rejected() {
        #expect(SwissQRParser.parse("SPC\n0100\n1\nCH93") == nil)
        #expect(SwissQRParser.parse("SPC\n0200\n2\nCH93") == nil)
        #expect(SwissQRParser.parse("XPC\n0200\n1") == nil)
        #expect(SwissQRParser.parse("") == nil)
    }
}
