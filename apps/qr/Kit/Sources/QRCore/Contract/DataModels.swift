// Contract (PLAN §5.4 and §7), frozen at qr-contract-1.
// The shapes of the bundled data files in `Data/`. WP 1.2 writes the files; WP 2.1's
// `BundledData` loads them; checks read them through these types.

public struct Brand: Codable, Sendable, Hashable {
    public var name: String
    /// Lowercase tokens matched against domain labels and page titles (PLAN §6.2 `brandInDomain`).
    public var keywords: [String]
    /// The brand's own registrable domains, lowercase, Unicode form.
    public var domains: [String]
    public var category: String
    public var region: String
    /// Ordinary words (apple, chase, target…): fire only next to a bait word.
    public var ambiguous: Bool

    public init(name: String, keywords: [String], domains: [String], category: String, region: String, ambiguous: Bool) {
        self.name = name
        self.keywords = keywords
        self.domains = domains
        self.category = category
        self.region = region
        self.ambiguous = ambiguous
    }
}

public struct RiskyTLD: Codable, Sendable, Hashable {
    /// Without the dot, lowercase, ASCII (xn-- form for IDN endings).
    public var tld: String
    /// URL of the public abuse report that names it.
    public var source: String

    public init(tld: String, source: String) {
        self.tld = tld
        self.source = source
    }
}

public struct EMVNetwork: Codable, Sendable, Hashable {
    /// The network identifier as it appears in sub-tag 00 of tags 26–51 (compared case-insensitively).
    public var id: String
    public var name: String
    /// ISO 3166-1 alpha-2.
    public var country: String
    /// "person", "business" or "unknown" (D11: only from the published spec).
    public var party: String
    /// URL of the scheme's published spec.
    public var source: String

    public init(id: String, name: String, country: String, party: String, source: String) {
        self.id = id
        self.name = name
        self.country = country
        self.party = party
        self.source = source
    }
}

public struct CryptoScheme: Codable, Sendable, Hashable {
    /// Lowercase URI scheme without the colon ("bitcoin").
    public var scheme: String
    public var name: String

    public init(scheme: String, name: String) {
        self.scheme = scheme
        self.name = name
    }
}

public struct PaymentNetworks: Codable, Sendable, Hashable {
    public var emv: [EMVNetwork]
    public var cryptoSchemes: [CryptoScheme]

    public init(emv: [EMVNetwork] = [], cryptoSchemes: [CryptoScheme] = []) {
        self.emv = emv
        self.cryptoSchemes = cryptoSchemes
    }
}
