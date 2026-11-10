import Testing
@testable import QRCore

@Suite struct EMVParserTests {
    static let pix = EMVNetwork(id: "br.gov.bcb.pix", name: "Pix", country: "BR", party: "unknown", source: "https://example.invalid/pix")
    static let paynow = EMVNetwork(id: "SG.PAYNOW", name: "PayNow", country: "SG", party: "business", source: "https://example.invalid/paynow")
    static let nets = [pix, paynow]
    static let cur = ["608": "PHP", "986": "BRL", "702": "SGD", "764": "THB"]

    static func parse(_ s: String) -> PaymentInfo? { EMVParser.parse(s, networks: nets, currencies: cur) }

    static func tlv(_ t: String, _ v: String) -> String { t + (v.utf8.count < 10 ? "0" : "") + "\(v.utf8.count)" + v }
    static func withCRC(_ body: String) -> String {
        let s = body + "6304"
        let c = String(CRC16.ccittFalse(Array(s.utf8)), radix: 16, uppercase: true)
        return s + String(repeating: "0", count: 4 - c.count) + c
    }

    @Test func notEMV() {
        #expect(Self.parse("hello") == nil)
        #expect(Self.parse("") == nil)
        #expect(Self.parse("00020") == nil)
        #expect(Self.parse(" 000201") == nil)
    }

    @Test func headerOnly() {
        let i = Self.parse("000201")
        #expect(i?.format == .emv)
        #expect(i?.checksum == .absent)
    }

    @Test func fullFields() throws {
        let acct = Self.tlv("00", "SG.paynow") + Self.tlv("01", "A1") + Self.tlv("02", "B2")
        let body = Self.tlv("00", "01") + Self.tlv("01", "12") + Self.tlv("26", acct) + Self.tlv("52", "5411")
            + Self.tlv("53", "702") + Self.tlv("54", "1.20") + Self.tlv("58", "SG") + Self.tlv("59", "Shop")
            + Self.tlv("60", "City")
            + Self.tlv("62", Self.tlv("05", "R1") + Self.tlv("08", "why") + Self.tlv("03", "x"))
            + Self.tlv("64", Self.tlv("00", "ZH") + Self.tlv("01", "店") + Self.tlv("02", "城"))
        let i = try #require(Self.parse(Self.withCRC(body)))
        #expect(i.checksum == .valid)
        #expect(i.isDynamic == true)
        #expect(i.network == "PayNow")
        #expect(i.party == .business)
        #expect(i.payeeAccount == "A1")
        #expect(i.currency == "SGD")
        #expect(i.amount == "1.20")
        #expect(i.country == "SG" && i.payeeName == "Shop" && i.city == "City")
        #expect(i.reference == "R1" && i.note == "why")
        #expect(i.extra["mcc"] == "5411")
        #expect(i.extra["additional.03"] == "x")
        #expect(i.extra["alternateLanguage"] == "ZH")
        #expect(i.extra["alternateName"] == "店" && i.extra["alternateCity"] == "城")
    }

    @Test func accountFallsBackToSub02() throws {
        let acct = Self.tlv("00", "br.gov.bcb.pix") + Self.tlv("02", "ONLY2")
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("26", acct))))
        #expect(i.payeeAccount == "ONLY2")
        #expect(i.network == "Pix")
        #expect(i.party == .unknown)
    }

    @Test func unknownNetworkKeepsID() throws {
        let acct = Self.tlv("00", "xx.other") + Self.tlv("01", "A")
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("27", acct))))
        #expect(i.network == nil)
        #expect(i.party == .unknown)
        #expect(i.payeeAccount == nil)
        #expect(i.extra["networkID"] == "xx.other")
    }

    @Test func secondTemplateMatches() throws {
        let a = Self.tlv("00", "xx.other") + Self.tlv("01", "A")
        let b = Self.tlv("00", "BR.GOV.BCB.PIX") + Self.tlv("01", "B")
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("26", a) + Self.tlv("27", b))))
        #expect(i.network == "Pix")
        #expect(i.payeeAccount == "B")
        #expect(i.extra["networkID"] == nil)
    }

    @Test func unknownCurrencyKept() throws {
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("53", "999"))))
        #expect(i.currency == nil)
        #expect(i.extra["currencyNumeric"] == "999")
    }

    @Test func isDynamicValues() throws {
        for (v, expected) in [("11", false), ("12", true)] {
            let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("01", v))))
            #expect(i.isDynamic == expected)
        }
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("01", "13"))))
        #expect(i.isDynamic == nil)
    }

    @Test func crcCases() throws {
        let good = Self.withCRC(Self.tlv("00", "01") + Self.tlv("59", "Shop"))
        #expect(Self.parse(good)?.checksum == .valid)
        #expect(Self.parse(String(good.dropLast(4)) + good.suffix(4).lowercased())?.checksum == .valid)
        let flipped = String(good.dropLast()) + (good.last == "0" ? "1" : "0")
        #expect(Self.parse(flipped)?.checksum == .invalid)
        #expect(Self.parse(String(good.dropLast(4)) + "ZZZZ")?.checksum == .invalid)
        #expect(Self.parse("000201" + Self.tlv("59", "Shop"))?.checksum == .absent)
    }

    @Test func utf16LengthsAreAccepted() throws {
        // "JOSÉ" is 5 UTF-8 bytes but 4 UTF-16 units; the length says 04.
        let body = "000201" + "5904JOSÉ" + "6007Bangkok"
        let i = try #require(Self.parse(Self.withCRC(body)))
        #expect(i.payeeName == "JOSÉ")
        #expect(i.city == "Bangkok")
        #expect(i.checksum == .valid)
    }

    @Test func truncatedKeepsWhatParsed() throws {
        let full = Self.withCRC(Self.tlv("00", "01") + Self.tlv("58", "SG") + Self.tlv("59", "Shop Name Here"))
        let cut = String(full.prefix(20)) + String(full.suffix(8))
        let i = try #require(Self.parse(cut))
        #expect(i.country == "SG")
        #expect(i.checksum == .invalid)
        let noTail = try #require(Self.parse(String(full.prefix(20))))
        #expect(noTail.country == "SG")
        #expect(noTail.checksum == .absent)
    }

    @Test func garbageDoesNotCrash() {
        for s in ["000201ab", "0002015999", "00020159", "000201" + String(repeating: "9", count: 50),
                  "000201" + String(repeating: "6304", count: 300)] {
            #expect(Self.parse(s)?.format == .emv)
        }
    }

    @Test func oversizeIsBoundedAndInvalid() {
        let big = "000201" + String(repeating: "x", count: 9000)
        let i = Self.parse(big)
        #expect(i?.checksum == .invalid)
        #expect(i?.payeeName == nil)
    }

    @Test func nestedTemplateDoesNotRecurse() throws {
        let inner = Self.tlv("00", "br.gov.bcb.pix") + Self.tlv("01", Self.tlv("00", "nested"))
        let i = try #require(Self.parse(Self.withCRC(Self.tlv("00", "01") + Self.tlv("26", inner))))
        #expect(i.payeeAccount == Self.tlv("00", "nested"))
    }
}
