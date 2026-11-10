import Foundation
import Testing
@testable import QRCore

@Suite struct ChecksBrandTests {
    let data = BundledData.shared
    let ctx = CheckContext(data: BundledData.shared)

    func link(_ s: String) throws -> WebAddress { try #require(WebAddress.parse(s, psl: data.publicSuffixes)) }
    func ids(_ s: String) throws -> [SignalID] { LinkChecks.signals(for: try link(s), ctx).map(\.id) }

    @Test func checksTokensSplitOnSeparatorsAndDigits() {
        #expect(BrandMatcher.tokens(inLabels: ["secure-paypal_login"]) == ["secure", "paypal", "login"])
        #expect(BrandMatcher.tokens(inLabels: ["paypa1", "amazon24x"]) == ["paypa", "1", "amazon", "24", "x"])
        #expect(BrandMatcher.tokens(inLabels: ["b\u{00FC}cher"]) == ["b\u{00FC}cher"])
    }

    @Test func checksDamerauLevenshtein() {
        func d(_ a: String, _ b: String) -> Int { DamerauLevenshtein.distance(Array(a.unicodeScalars), Array(b.unicodeScalars)) }
        #expect(d("paypal", "paypal") == 0)
        #expect(d("paypa", "paypal") == 1)
        #expect(d("gogole", "google") == 1)
        #expect(d("amazon", "amazonn") == 1)
        #expect(d("abc", "xyz") == 3)
        #expect(d("", "abc") == 3)
    }

    @Test func checksBrandSignalArgs() throws {
        let sig = LinkChecks.signals(for: try link("https://paypa1.com/"), ctx)
        #expect(sig.first?.id == .lookalikeBrand)
        #expect(sig.first?.args["brand"] == "PayPal")
        let u = LinkChecks.signals(for: try link("https://bank.com@evil.site/"), ctx)
        #expect(u.first(where: { $0.id == .userinfoTrick })?.args["host"] == "evil.site")
        #expect(LinkChecks.signals(for: try link("https://x.top/"), ctx).first?.args["tld"] == "top")
    }

    @Test func checksBrandChecksSkipUnknownSuffixAndIPs() throws {
        #expect(try ids("https://paypal.com.example/login").isEmpty, "unknown suffix: no registrable domain")
        #expect(try ids("https://203.0.113.9/paypal").contains(.brandInDomain) == false)
    }

    @Test func checksSkeletonMatchNeverFiresOnExactBrandLabel() throws {
        // paypay.ne.jp is PayPay's own domain, one edit from PayPal.
        #expect(try ids("https://paypay.ne.jp/").isEmpty)
        #expect(try ids("https://paypal.xyz/").contains(.brandInDomain))
        #expect(try !ids("https://paypal.xyz/").contains(.lookalikeBrand))
    }

    @Test func checksOnlyOneSignalPerIDAndStableOrder() throws {
        let s = LinkChecks.signals(for: try link("http://a.b.c.d.paypa1-secure.top/login"), ctx)
        #expect(Set(s.map(\.id)).count == s.count)
        #expect(s == s.inStableOrder())
    }

    @Test func checksBrandOverridesKeepRegionalSitesQuiet() throws {
        for u in ["https://www.google.co.uk/", "https://www.ebay.de/", "https://www.yahoo.co.jp/", "https://www.paypal.com.au/"] {
            #expect(try ids(u).isEmpty, "\(u)")
        }
        #expect(try ids("https://google.xyz/").contains(.brandInDomain))
    }

    @Test func checksCommonWordsDoNotMisfire() throws {
        // C16 words from the brief: ordinary sites that use them.
        for u in ["https://www.smart-home-guide.com/", "https://www.zoom.com/", "https://store.steampowered.com/", "https://www.bookingholdings.com/",
                  "https://www.grabcad.com/", "https://www.ubereats.com/", "https://www.ledger.com/", "https://www.orange.com/", "https://www.claroshop.com/",
                  "https://www.bostonglobe.com/", "https://www.mayoclinic.org/", "https://www.visa.co.uk/", "https://www.targetcorp.com/", "https://www.dito.example.com/"] {
            let r = try ids(u)
            #expect(!r.contains(where: { $0.severity == .strong }), "\(u): \(r)")
        }
    }
}
