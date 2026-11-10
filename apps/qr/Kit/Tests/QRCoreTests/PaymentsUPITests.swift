import Testing
@testable import QRCore

@Suite struct PaymentsUPITests {
    @Test func payWithAmount() throws {
        let p = try #require(UPIParser.parse("upi://pay?pa=shop@okbank&pn=Corner%20Shop&am=250.00&cu=inr&tn=Order+1&tr=T1&mc=5411"))
        #expect(p.format == .upi)
        #expect(p.payeeAccount == "shop@okbank")
        #expect(p.payeeName == "Corner Shop")
        #expect(p.amount == "250.00")
        #expect(p.currency == "INR")
        #expect(p.note == "Order+1")
        #expect(p.reference == "T1")
        #expect(p.extra["mcc"] == "5411")
        #expect(p.extra["kind"] == "pay")
        #expect(p.checksum == .absent)
        #expect(p.party == .unknown)
    }

    @Test func payWithoutAmountCaseInsensitive() throws {
        let p = try #require(UPIParser.parse("UPI://Pay?PA=a@b"))
        #expect(p.payeeAccount == "a@b")
        #expect(p.amount == nil)
        #expect(p.currency == nil)
    }

    @Test func mandate() throws {
        let p = try #require(UPIParser.parse("upi://mandate?pa=x@y&mode=13&url=https%3A%2F%2Fa.example"))
        #expect(p.extra["kind"] == "mandate")
        #expect(p.extra["mode"] == "13")
        #expect(p.extra["url"] == "https://a.example")
    }

    @Test func notUPI() {
        #expect(UPIParser.parse("upi://collect?pa=x@y") == nil)
        #expect(UPIParser.parse("https://pay") == nil)
        #expect(UPIParser.parse("") == nil)
        #expect(UPIParser.parse("upi://" + String(repeating: "a", count: 9000)) == nil)
    }

    @Test func badPercentEscapeStaysLiteral() throws {
        let p = try #require(UPIParser.parse("upi://pay?pa=a@b&pn=100%"))
        #expect(p.payeeName == "100%")
    }
}
