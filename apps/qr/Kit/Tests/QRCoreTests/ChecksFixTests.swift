import Foundation
import Testing
@testable import QRCore

/// Fix pass 1, scam checks: three-letter brands, other endings, unlisted endings, look-alike
/// words, nested links, compatibility folding (SPEC §4.2). Uses injected data so the rules are
/// tested independently of what brands.json lists.
@Suite struct ChecksFixTests {
    static let shared = BundledData.shared

    static func data(extraBait: [String] = ["tracking", "online", "login"]) -> BundledData {
        let brands = [
            Brand(name: "DHL", keywords: ["dhl"], domains: ["dhl.com"], category: "shipping", region: "global", ambiguous: false),
            Brand(name: "BPI", keywords: ["bpi"], domains: ["bpi.com.ph"], category: "bank", region: "ph", ambiguous: false),
            Brand(name: "PayPal", keywords: ["paypal"], domains: ["paypal.com", "paypal.me"], category: "payments", region: "global", ambiguous: false),
            Brand(name: "Apple", keywords: ["apple"], domains: ["apple.com"], category: "tech", region: "global", ambiguous: true),
        ]
        return BundledData(publicSuffixes: shared.publicSuffixes, brands: brands,
                           riskyTLDs: ["help", "top", "cc"].map { RiskyTLD(tld: $0, source: "https://example.org/report") },
                           confusables: shared.confusables, baitWords: extraBait)
    }

    let ctx = CheckContext(data: ChecksFixTests.data())

    func ids(_ s: String) throws -> [SignalID] {
        let a = try #require(WebAddress.parse(s, psl: Self.shared.publicSuffixes))
        return LinkChecks.signals(for: a, ctx).map(\.id)
    }

    @Test func checksFixThreeLetterBrandNeedsWholeTokenAndBait() throws {
        #expect(try ids("https://dhl-tracking.help/").contains(.brandInDomain))
        #expect(try ids("https://bpi-online.verify.site/") == [.brandInDomain])
        #expect(try ids("https://dhl.tracking.example.net/").contains(.brandInDomain), "bait in another label of the same host")
        #expect(try ids("https://tracking.dhl.com/").isEmpty, "the brand's own domain")
        #expect(try ids("https://dhl.de/").isEmpty, "no bait word")
        #expect(try ids("https://www.dhl-fans.net/").isEmpty, "no bait word")
        #expect(try ids("https://dhlx-tracking.help/") == [.riskyTLD], "dhlx is not the token dhl")
        #expect(try ids("https://adhl-online.net/").isEmpty)
        #expect(try ids("https://www.upsilon-online.net/").isEmpty)
    }

    @Test func checksFixThreeLetterBrandIsNeverGentleOrLookalike() throws {
        let r = try ids("https://online.dhl.de/")
        #expect(r.contains(.brandInDomain), "exact name beside a bait word: strong, not brandOtherEnding")
        #expect(!r.contains(.brandOtherEnding))
        for u in ["https://dh1-tracking.help/", "https://dh1.com/", "https://bp1-online.net/", "https://dhi-login.net/"] {
            #expect(try !ids(u).contains(.lookalikeBrand), "\(u)")
            #expect(try !ids(u).contains(.nearBrand), "\(u)")
        }
    }

    @Test func checksFixTheBaitWordMustBeAnotherToken() throws {
        let d = BundledData(publicSuffixes: Self.shared.publicSuffixes,
                            brands: [Brand(name: "DHL", keywords: ["dhl"], domains: ["dhl.com"], category: "shipping", region: "global", ambiguous: false)],
                            confusables: Self.shared.confusables, baitWords: ["dhl"])
        let a = try #require(WebAddress.parse("https://dhl.net/", psl: Self.shared.publicSuffixes))
        #expect(LinkChecks.signals(for: a, CheckContext(data: d)).isEmpty)
    }

    @Test func checksFixOtherEndingRule() throws {
        #expect(try ids("https://paypal.pt/") == [.brandOtherEnding])
        #expect(try ids("https://login.paypal.pt/") == [.brandOtherEnding])
        #expect(try ids("https://www.paypal.com.ph/") == [.brandOtherEnding])
        #expect(try ids("https://paypal.co.il/") == [.brandOtherEnding])
        #expect(try ids("https://paypal.org/") == [.brandOtherEnding])
        #expect(try ids("https://paypal.me/").isEmpty, "listed domain")
        #expect(try ids("https://paypal.help/").contains(.brandInDomain))
        #expect(try ids("https://paypal.cc/").contains(.brandInDomain), "abused two-letter ending")
        #expect(try ids("https://paypal.xyz/") == [.brandInDomain])
        #expect(try ids("https://paypal-login.ph/") == [.brandInDomain])
        #expect(try ids("https://paypal.evil.ph/") == [.brandInDomain])
        #expect(try ids("https://paypal.github.io/") == [.brandInDomain], "private suffix")
        #expect(try ids("https://apple.ph/").isEmpty, "ambiguous brand, no bait")
        #expect(try ids("https://login.apple.ph/") == [.brandInDomain], "ambiguous brand keeps the strong rule")
    }

    @Test func checksFixStrongBeatsGentleWhenTwoBrandsMatch() throws {
        #expect(try ids("https://paypal.dhl-login.net/").contains(.brandInDomain))
        let r = try ids("https://dhl-login.paypal.ph/")
        #expect(r.contains(.brandInDomain) && !r.contains(.brandOtherEnding))
    }

    @Test func checksFixUnknownEndingFallsBackToLastLabel() throws {
        let a = try #require(WebAddress.parse("https://paypal.evil-site.newtldxyz/", psl: Self.shared.publicSuffixes))
        #expect(a.link.registrableDomain == "evil-site.newtldxyz")
        let r = LinkChecks.signals(for: a, ctx)
        #expect(r.map(\.id) == [.brandInDomain, .unknownEnding])
        #expect(r.first(where: { $0.id == .unknownEnding })?.severity == .info)
        #expect(try ids("https://example.com/").contains(.unknownEnding) == false)
        #expect(try ids("https://localhost/").isEmpty)
        #expect(try ids("https://10.0.0.1/") == [.ipHost])
        #expect(WebAddress.parseSchemeless("www.example.newtldxyz", psl: Self.shared.publicSuffixes) == nil)
    }

    @Test func checksFixLookalikeWordsInsideLabels() throws {
        #expect(try ids("https://secure-paypa1.com/") == [.lookalikeBrand])
        #expect(try ids("https://paypa1.evil.com/") == [.lookalikeBrand])
        #expect(try ids("https://my-paypal.com/") == [.brandInDomain])
        #expect(try ids("https://app1e-login.com/") == [.lookalikeBrand])
        #expect(try ids("https://app1e-garden.com/").isEmpty)
        #expect(try ids("https://secure-garden.com/").isEmpty)
    }

    @Test func checksFixNestedLinkOnlyCountsInThePath() {
        func fires(_ s: String) -> Bool {
            guard let a = WebAddress.parse(s, psl: Self.shared.publicSuffixes) else { return false }
            return ObfuscatedPathCheck.signal(a.link, baitWords: ["login"]) != nil
        }
        #expect(!fires("https://example.com/login?next=https://other.example/"))
        #expect(!fires("https://example.com/login?next=https%3A%2F%2Fother.example"))
        #expect(!fires("https://example.com/login#https://other.example/"))
        #expect(fires("https://example.com/login/https://other.example/"))
        #expect(fires("https://example.com/login/http%3A%2F%2Fother.example"))
        #expect(fires("https://example.com/login/x?y=1&u=http://z.example/") == false)
        #expect(!fires("https://example.com/login?x=" + String(repeating: "a", count: 200)))
        #expect(!fires("https://example.com/login?x=%41%42%43%44%45%46%47%48%49%4A%4B"))
        #expect(fires("https://example.com/login/" + String(repeating: "a", count: 200)))
        #expect(fires("https://example.com/login/%41%42%43%44%45%46%47%48%49%4A%4B"))
    }

    @Test func checksFixHostIsFoldedBeforeAnyCheck() throws {
        let psl = Self.shared.publicSuffixes
        for s in ["https://ｐａｙｐａｌ.com/", "https://paypal。com/", "https://paypal．com/", "https://paypal｡com/", "https://ＰＡＹＰＡＬ.COM/"] {
            let a = try #require(WebAddress.parse(s, psl: psl), "\(s)")
            #expect(a.link.hostASCII == "paypal.com", "\(s)")
            #expect(a.link.registrableDomain == "paypal.com")
            #expect(!a.hasMalformedLabel)
        }
        let ligature = try #require(WebAddress.parse("https://\u{FB01}le.example.com/", psl: psl))
        #expect(ligature.link.hostASCII == "file.example.com")
        // Folding that would put a path separator in the host is not a host.
        #expect(WebAddress.parse("https://example.com\u{FF0F}paypal.com/", psl: psl) == nil)
    }
}
