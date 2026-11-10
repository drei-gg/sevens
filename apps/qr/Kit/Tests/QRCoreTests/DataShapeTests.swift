import Foundation
import Testing
@testable import QRCore

/// WP 1.2: every bundled data file (other than the PSL and RDAP bootstrap) decodes into its
/// type and obeys the rules in PLAN §7. The count of brands is 120–200.
@Suite struct DataShapeTests {
    static let data = BundledData.load()

    /// The source file in `Data/`, found from this file's path. Used only for checks that the
    /// parsed types hide (a `Set` drops duplicates); nil when the sources aren't next to the tests.
    private static func sourceFile(_ name: String) -> Data? {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<3 { url.deleteLastPathComponent() }   // Tests/QRCoreTests/DataShapeTests.swift → Kit
        url.appendPathComponent("Sources/QRCore/Data/\(name)")
        return try? Data(contentsOf: url)
    }

    // MARK: brands

    @Test func brandsHaveRightCount() {
        let n = Self.data.brands.count
        #expect(n >= 120 && n <= 200, "brand count \(n)")
    }

    @Test func brandsHaveDomainsAndLowercaseKeywords() {
        let bait = Set(Self.data.baitWords)
        var seen: [String: String] = [:]
        for b in Self.data.brands {
            #expect(!b.name.isEmpty)
            #expect(!b.domains.isEmpty, "\(b.name) has no domain")
            #expect(!b.category.isEmpty && b.category == b.category.lowercased(), "\(b.name) category")
            #expect(!b.region.isEmpty && b.region == b.region.lowercased(), "\(b.name) region")
            for d in b.domains {
                #expect(d == d.lowercased(), "\(b.name): \(d) not lowercase")
                #expect(d.contains("."), "\(b.name): \(d) has no dot")
                #expect(!d.contains("/") && !d.contains(" "), "\(b.name): \(d) not a bare domain")
            }
            for k in b.keywords {
                #expect(k == k.lowercased(), "\(k) not lowercase")
                #expect(k.count >= 4, "\(k) shorter than 4")
                #expect(k.unicodeScalars.allSatisfy { $0.isASCII }, "\(k) not ASCII")
                #expect(!bait.contains(k), "\(k) is a bait word")
                if let other = seen[k] { Issue.record("keyword \(k) in \(other) and \(b.name)") }
                seen[k] = b.name
            }
        }
    }

    @Test func brandNamesAreUnique() {
        let names = Self.data.brands.map { $0.name.lowercased() }
        #expect(Set(names).count == names.count)
    }

    @Test func brandsSpanSeveralRegionsAndCategories() {
        let regions = Set(Self.data.brands.map(\.region))
        #expect(regions.contains("ph"))
        #expect(regions.contains("us") && regions.contains("in") && regions.contains("br"))
        #expect(Set(Self.data.brands.map(\.category)).isSuperset(of: ["bank", "courier", "payments", "telecom", "government", "marketplace", "crypto"]))
    }

    // MARK: shorteners, TLDs, bait words

    @Test func shortenersAreLowercaseAndUnique() throws {
        let s = Self.data.shorteners
        #expect(s.count >= 60, "shortener count \(s.count)")
        #expect(s.allSatisfy { $0 == $0.lowercased() && $0.contains(".") && !$0.contains("/") })
        #expect(s.contains("bit.ly"))
        if let raw = Self.sourceFile("shorteners.json") {
            let list = try JSONDecoder().decode([String].self, from: raw)
            #expect(list.count == s.count, "duplicate entries in shorteners.json")
        }
    }

    @Test func riskyTLDsAreSourced() {
        let t = Self.data.riskyTLDs
        #expect(t.count >= 15 && t.count <= 40, "tld count \(t.count)")
        #expect(Set(t.map(\.tld)).count == t.count)
        for r in t {
            #expect(!r.tld.isEmpty && r.tld == r.tld.lowercased() && !r.tld.contains("."), "tld \(r.tld)")
            #expect(r.tld.unicodeScalars.allSatisfy { $0.isASCII })
            #expect(r.source.hasPrefix("https://") && r.source.count > 12, "\(r.tld) source")
        }
    }

    @Test func baitWordsAreAsciiAndUnique() {
        let raw = Self.data.baitWords
        #expect(raw.count >= 30 && raw.count <= 60, "bait count \(raw.count)")
        #expect(Set(raw).count == raw.count)
        #expect(raw.allSatisfy { $0 == $0.lowercased() && !$0.isEmpty && $0.unicodeScalars.allSatisfy { $0.isASCII } })
        #expect(Set(raw).isSuperset(of: ["login", "verify", "secure", "account"]))
    }

    // MARK: confusables

    @Test func confusablesAreLowercaseAsciiAndHaveThePairs() {
        let c = Self.data.confusables
        #expect(c.count > 40)
        for (k, v) in c {
            #expect(!k.isEmpty && k == k.lowercased(), "key \(k)")
            #expect(!v.isEmpty && v == v.lowercased() && v.unicodeScalars.allSatisfy { $0.isASCII }, "value for \(k)")
        }
        #expect(c["0"] == "o")
        #expect(c["1"] == "l")
        #expect(c["i"] == "l")
        #expect(c["rn"] == "m")
        #expect(c["vv"] == "w")
        #expect(c["\u{0430}"] == "a")  // Cyrillic a
        #expect(c["\u{03BF}"] == "o")  // Greek omicron
        #expect(c["\u{FF41}"] == "a")  // fullwidth a
    }

    // MARK: payment networks

    @Test func emvNetworksAreSourced() {
        let n = Self.data.paymentNetworks.emv
        #expect(!n.isEmpty)
        #expect(Set(n.map { $0.id.lowercased() }).count == n.count)
        for e in n {
            #expect(!e.id.isEmpty && !e.name.isEmpty)
            #expect(e.country.count == 2 && e.country == e.country.uppercased(), "\(e.id) country")
            #expect(["person", "business", "unknown"].contains(e.party), "\(e.id) party")
            #expect(e.source.hasPrefix("https://") && e.source.count > 12, "\(e.id) source")
        }
    }

    @Test func cryptoSchemesAreLowercaseAndUnique() {
        let s = Self.data.paymentNetworks.cryptoSchemes
        #expect(Set(s.map(\.scheme)).count == s.count)
        #expect(s.allSatisfy { $0.scheme == $0.scheme.lowercased() && !$0.scheme.contains(":") && !$0.name.isEmpty })
        #expect(Set(s.map(\.scheme)).isSuperset(of: ["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"]))
    }

    // MARK: currencies

    @Test func currenciesAreIso4217() {
        let c = Self.data.currencies
        #expect(c.count >= 150)
        for (k, v) in c {
            #expect(k.count == 3 && k.allSatisfy(\.isASCII) && k.allSatisfy(\.isNumber), "numeric \(k)")
            #expect(v.count == 3 && v == v.uppercased() && v.allSatisfy(\.isLetter) && v.allSatisfy(\.isASCII), "alpha \(v)")
        }
        #expect(c["608"] == "PHP")
        #expect(c["840"] == "USD")
        #expect(c["978"] == "EUR")
    }
}
