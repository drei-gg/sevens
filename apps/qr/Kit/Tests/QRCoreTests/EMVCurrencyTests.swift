import Testing
@testable import QRCore

@Suite struct EMVCurrencyTests {
    let table = ["608": "PHP", "986": "BRL", "036": "AUD"]

    @Test func known() {
        #expect(Currency.alpha(numeric: "608", table: table) == "PHP")
        #expect(Currency.alpha(numeric: "36", table: table) == "AUD")
    }

    @Test func unknownOrMalformed() {
        #expect(Currency.alpha(numeric: "999", table: table) == nil)
        #expect(Currency.alpha(numeric: "", table: table) == nil)
        #expect(Currency.alpha(numeric: "6O8", table: table) == nil)
        #expect(Currency.alpha(numeric: "0608", table: table) == nil)
    }
}
