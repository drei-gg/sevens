import Foundation
import Testing
@testable import QRCore

@Suite struct BundledDataTests {
    @Test func bundledDataLoadsEveryFileFromTheBundle() {
        let start = Date()
        let d = BundledData.load()
        let elapsed = Date().timeIntervalSince(start)
        print("bundled data: loaded in \(Int(elapsed * 1000)) ms")
        #expect(d.brands.count >= 120)
        #expect(!d.shorteners.isEmpty && !d.riskyTLDs.isEmpty && !d.confusables.isEmpty && !d.baitWords.isEmpty)
        #expect(!d.paymentNetworks.emv.isEmpty && !d.paymentNetworks.cryptoSchemes.isEmpty && !d.currencies.isEmpty)
        #expect(d.publicSuffixes.match(hostASCII: "www.example.com").registrableDomain == "example.com")
        #expect(d.rdap.baseURL(forTLD: "com") != nil)
        #expect(elapsed < 1.0, "load took \(elapsed) s")
    }

    @Test func bundledDataSharedIsTheSameAcrossThreads() async {
        async let a = Task.detached { BundledData.shared.brands.count }.value
        async let b = Task.detached { BundledData.shared.brands.count }.value
        let (x, y) = await (a, b)
        #expect(x == y && x >= 120)
    }

    @Test func bundledDataPreloadReturnsImmediately() {
        let start = Date()
        BundledData.preload()
        #expect(Date().timeIntervalSince(start) < 0.5)
    }

    @Test func bundledDataBrandsHaveDomainsAndLowercaseKeywords() {
        for b in BundledData.shared.brands {
            #expect(!b.domains.isEmpty, "\(b.name)")
            #expect(b.keywords.allSatisfy { $0 == $0.lowercased() }, "\(b.name)")
        }
    }

    @Test func bundledDataLoadsTrackingParams() {
        let d = BundledData.shared
        #expect(d.trackingParams.contains { $0.matches("utm_campaign") })
        #expect(d.trackingParams.contains { $0.matches("fbclid") })
        #expect(!d.trackingParams.contains { $0.matches("q") || $0.matches("id") || $0.matches("page") })
    }
}
