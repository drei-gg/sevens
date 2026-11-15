import Foundation
import Testing
@testable import QRCore

/// WP 1.2: every bundled data file (other than the PSL and RDAP bootstrap) decodes into its
/// type and obeys the rules in SPEC §4.3. The count of brands is 120–200.
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
                #expect(k.count >= 3, "\(k) shorter than 3")
                #expect(k.count >= 4 || b.ambiguous, "\(b.name): three-letter keyword \(k) needs ambiguous = true")
                #expect(k.unicodeScalars.allSatisfy { $0.properties.isLowercase || $0.properties.numericType != nil }, "\(k) not lowercase letters or digits")
                #expect(k.unicodeScalars.allSatisfy { $0.isASCII }, "\(k) not ASCII")
                #expect(!bait.contains(k), "\(k) is a bait word")
                if let other = seen[k] { Issue.record("keyword \(k) in \(other) and \(b.name)") }
                seen[k] = b.name
            }
        }
    }

    @Test func brandDomainsBelongToOneBrand() {
        var owner: [String: String] = [:]
        for b in Self.data.brands {
            for d in b.domains {
                if let other = owner[d], other != b.name { Issue.record("domain \(d) in \(other) and \(b.name)") }
                owner[d] = b.name
            }
            #expect(Set(b.domains).count == b.domains.count, "\(b.name) lists a domain twice")
        }
    }

    @Test func brandsKnowTheirSecondaryDomains() {
        func domains(_ name: String) -> Set<String> {
            Set(Self.data.brands.first { $0.name == name }?.domains ?? [])
        }
        #expect(domains("PayPal").isSuperset(of: ["paypal.com", "paypal.me"]))
        #expect(domains("WhatsApp").isSuperset(of: ["whatsapp.com", "wa.me", "whatsapp.net"]))
        #expect(domains("Facebook").isSuperset(of: ["fb.me", "m.me"]))
        #expect(domains("Microsoft").isSuperset(of: ["microsoft.de", "microsoft.co.uk"]))
        #expect(domains("Netflix").isSuperset(of: ["netflix.com", "netflix.co"]))
        #expect(domains("Mastercard").contains("mastercard.com.ph"))
        #expect(domains("Google").isSuperset(of: ["google.co.uk", "google.com.ph"]))
        #expect(domains("Amazon").isSuperset(of: ["amazon.de", "amazon.co.uk"]))
        // Left out on purpose: no source could be read for these, and the fixtures use netflix.xyz as
        // an abused-ending example.
        #expect(!domains("Netflix").contains("netflix.xyz"))
        #expect(!domains("Facebook").contains("facebook.co.uk"))
        #expect(!domains("GCash").contains("gcash.ph"))
    }

    @Test func brandsHaveThreeLetterKeywordsForTheMostImpersonatedShortNames() {
        for (name, keyword) in [("DHL", "dhl"), ("BPI", "bpi"), ("BDO Unibank", "bdo"), ("UPS", "ups")] {
            let brand = Self.data.brands.first { $0.name == name }
            #expect(brand?.keywords.contains(keyword) == true, "\(name) lacks \(keyword)")
            #expect(brand?.ambiguous == true, "\(name) must need a bait word")
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
        #expect(Set(raw).isSuperset(of: ["login", "verify", "secure", "account", "tracking", "delivery", "parcel", "online"]))
    }

    // MARK: tracking parameters

    @Test func trackingParamsAreSourcedAndWellFormed() throws {
        let list = Self.data.trackingParams
        #expect(list.count >= 15, "tracking param count \(list.count)")
        #expect(Set(list.map(\.name)).count == list.count, "duplicate tracking param names")
        for p in list {
            let body = p.name.hasSuffix("*") ? String(p.name.dropLast()) : p.name
            #expect(!body.isEmpty && body.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "_" }, "name \(p.name)")
            #expect(p.source.hasPrefix("https://") && p.source.count > 12 && !p.source.contains(" "), "\(p.name) source")
        }
        let names = Set(list.map(\.name))
        #expect(names.isSuperset(of: ["utm_*", "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_eid", "mc_cid", "yclid", "twclid", "ttclid", "li_fat_id", "_hsenc", "_hsmi", "mkt_tok"]))
        #expect(list.first { $0.name == "utm_*" }?.matches("UTM_Source") == true)
        if let raw = Self.sourceFile("tracking-params.json") {
            #expect(try JSONDecoder().decode([TrackingParam].self, from: raw).count == list.count)
        }
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
        #expect(n.count >= 5)
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
