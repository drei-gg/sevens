import Foundation

/// The shape rules of SPEC §4.3, one function per file. Each returns false on the first broken
/// rule. They mirror `DataShapeTests`, which checks the files that ship with the app.
enum ListsValidation {
    private static func ascii(_ text: String) -> Bool { text.unicodeScalars.allSatisfy { $0.isASCII } }
    private static func httpsSource(_ text: String) -> Bool { text.hasPrefix("https://") && text.count > 12 }

    static func isISODate(_ text: String) -> Bool {
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]) else { return false }
        return y >= 2000 && (1...12).contains(m) && (1...31).contains(d)
            && text.utf8.allSatisfy { $0 == 0x2D || ($0 >= 0x30 && $0 <= 0x39) }
    }

    static func brands(_ list: [Brand], bait: Set<String>) -> Bool {
        guard (120...200).contains(list.count) else { return false }
        var names = Set<String>(), keywords = Set<String>()
        for brand in list {
            guard !brand.name.isEmpty, names.insert(brand.name.lowercased()).inserted,
                  !brand.domains.isEmpty,
                  !brand.category.isEmpty, brand.category == brand.category.lowercased(),
                  !brand.region.isEmpty, brand.region == brand.region.lowercased() else { return false }
            for domain in brand.domains {
                guard domain == domain.lowercased(), domain.contains("."),
                      !domain.contains("/"), !domain.contains(" ") else { return false }
            }
            for word in brand.keywords {
                guard word == word.lowercased(), word.count >= 3, ascii(word),
                      !bait.contains(word), keywords.insert(word).inserted else { return false }
            }
        }
        return true
    }

    static func shorteners(_ list: [String]) -> Bool {
        list.count >= 60 && Set(list).count == list.count
            && list.allSatisfy { $0 == $0.lowercased() && $0.contains(".") && !$0.contains("/") }
    }

    static func riskyTLDs(_ list: [RiskyTLD]) -> Bool {
        guard (15...40).contains(list.count), Set(list.map(\.tld)).count == list.count else { return false }
        return list.allSatisfy {
            !$0.tld.isEmpty && $0.tld == $0.tld.lowercased() && !$0.tld.contains(".") && ascii($0.tld)
                && httpsSource($0.source)
        }
    }

    static func baitWords(_ list: [String]) -> Bool {
        (30...60).contains(list.count) && Set(list).count == list.count
            && list.allSatisfy { $0 == $0.lowercased() && !$0.isEmpty && ascii($0) }
            && Set(list).isSuperset(of: ["login", "verify", "secure", "account"])
    }

    static func paymentNetworks(_ networks: PaymentNetworks) -> Bool {
        guard !networks.emv.isEmpty, Set(networks.emv.map { $0.id.lowercased() }).count == networks.emv.count else { return false }
        let emvOK = networks.emv.allSatisfy {
            !$0.id.isEmpty && !$0.name.isEmpty && $0.country.count == 2 && $0.country == $0.country.uppercased()
                && ["person", "business", "unknown"].contains($0.party) && httpsSource($0.source)
        }
        let schemes = networks.cryptoSchemes
        return emvOK && Set(schemes.map(\.scheme)).count == schemes.count
            && schemes.allSatisfy { $0.scheme == $0.scheme.lowercased() && !$0.scheme.contains(":") && !$0.name.isEmpty }
            && Set(schemes.map(\.scheme)).isSuperset(of: ["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"])
    }

    static func confusables(_ map: [String: String]) -> Bool {
        guard map.count > 40 else { return false }
        for (key, value) in map {
            guard !key.isEmpty, key == key.lowercased(),
                  !value.isEmpty, value == value.lowercased(), ascii(value) else { return false }
        }
        return map["0"] == "o" && map["1"] == "l" && map["i"] == "l" && map["rn"] == "m" && map["vv"] == "w"
            && map["\u{0430}"] == "a" && map["\u{03BF}"] == "o" && map["\u{FF41}"] == "a"
    }

    static func publicSuffixList(_ text: String) -> Bool {
        guard text.contains("===BEGIN ICANN DOMAINS==="), text.contains("===END ICANN DOMAINS===") else { return false }
        let match = PublicSuffixList(text: text).match(hostASCII: "example.com")
        return match.publicSuffix == "com" && match.registrableDomain == "example.com" && match.matchedExplicitRule
    }

    static func rdap(_ file: RDAPFile) -> Bool {
        guard file.services.count >= 50 else { return false }
        for service in file.services {
            guard service.count == 2, !service[0].isEmpty, !service[1].isEmpty,
                  service[0].allSatisfy({ !$0.isEmpty }),
                  service[1].allSatisfy({ $0.lowercased().hasPrefix("https://") || $0.lowercased().hasPrefix("http://") })
            else { return false }
        }
        guard let data = try? JSONEncoder().encode(file) else { return false }
        return RDAPBootstrap(json: data).baseURL(forTLD: "com") != nil
    }

    static func trackingParams(_ list: [TrackingParam]) -> Bool {
        guard Set(list.map(\.name)).count == list.count else { return false }
        return list.allSatisfy { param in
            let body = param.name.hasSuffix("*") ? String(param.name.dropLast()) : param.name
            return !body.isEmpty
                && body.utf8.allSatisfy { ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x5F }
                && httpsSource(param.source)
        }
    }
}
