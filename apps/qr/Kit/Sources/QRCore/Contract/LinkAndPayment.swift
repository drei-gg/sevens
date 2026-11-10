// Contract, frozen at qr-contract-1.

public struct LinkInfo: Sendable, Hashable {
    /// Exactly what was scanned.
    public var original: String
    public var scheme: String
    public var userinfo: String?
    /// xn-- form, lowercased. Empty for dangerous schemes without an authority.
    public var hostASCII: String
    /// Decoded form, for display.
    public var hostUnicode: String
    /// Unicode form; nil for IPs and unknown suffixes.
    public var registrableDomain: String?
    public var publicSuffix: String?
    /// e.g. github.io
    public var suffixIsPrivate: Bool
    public var port: Int?
    public var pathAndQuery: String
    public var isIPAddress: Bool
    /// What Open and Peek use.
    public var normalized: String

    public init(original: String, scheme: String, userinfo: String? = nil, hostASCII: String,
                hostUnicode: String, registrableDomain: String? = nil, publicSuffix: String? = nil,
                suffixIsPrivate: Bool = false, port: Int? = nil, pathAndQuery: String = "",
                isIPAddress: Bool = false, normalized: String) {
        self.original = original
        self.scheme = scheme
        self.userinfo = userinfo
        self.hostASCII = hostASCII
        self.hostUnicode = hostUnicode
        self.registrableDomain = registrableDomain
        self.publicSuffix = publicSuffix
        self.suffixIsPrivate = suffixIsPrivate
        self.port = port
        self.pathAndQuery = pathAndQuery
        self.isIPAddress = isIPAddress
        self.normalized = normalized
    }
}

public struct PaymentInfo: Sendable, Hashable {
    public enum Format: String, Sendable { case emv, upi, epc, swiss }
    public enum Party: String, Sendable { case person, business, unknown }
    public enum Checksum: String, Sendable { case valid, invalid, absent }

    public var format: Format
    public var payeeName: String?, payeeAccount: String?, city: String?, country: String?
    /// Decimal text as given; never a Double.
    public var amount: String?
    /// ISO 4217 alpha code.
    public var currency: String?
    /// Display name from payment-networks.json.
    public var network: String?
    public var isDynamic: Bool?
    public var reference: String?, note: String?
    public var party: Party
    public var checksum: Checksum
    /// Format-specific extras (e.g. "alternateName", "networkID", "bic", "debtorName").
    public var extra: [String: String]

    public init(format: Format, payeeName: String? = nil, payeeAccount: String? = nil,
                city: String? = nil, country: String? = nil, amount: String? = nil,
                currency: String? = nil, network: String? = nil, isDynamic: Bool? = nil,
                reference: String? = nil, note: String? = nil, party: Party = .unknown,
                checksum: Checksum = .absent, extra: [String: String] = [:]) {
        self.format = format
        self.payeeName = payeeName
        self.payeeAccount = payeeAccount
        self.city = city
        self.country = country
        self.amount = amount
        self.currency = currency
        self.network = network
        self.isDynamic = isDynamic
        self.reference = reference
        self.note = note
        self.party = party
        self.checksum = checksum
        self.extra = extra
    }
}

public struct CryptoInfo: Sendable, Hashable {
    /// URI scheme as scanned, lowercased ("bitcoin", "ethereum", "lightning", …); nil for a bare address.
    public var scheme: String?
    /// Display name ("Bitcoin", "Ethereum", …) from the scheme list or the address pattern.
    public var network: String
    /// The address, or for `lightning:` the invoice.
    public var address: String
    /// Decimal text as given (BIP 21 `amount`, EIP-681 `value` as given, lightning prefix amount in BTC).
    public var amount: String?
    public var label: String?
    public var message: String?
    /// EIP-681 `@chain`.
    public var chainID: String?
    public var isLightningInvoice: Bool

    public init(scheme: String? = nil, network: String, address: String, amount: String? = nil,
                label: String? = nil, message: String? = nil, chainID: String? = nil,
                isLightningInvoice: Bool = false) {
        self.scheme = scheme
        self.network = network
        self.address = address
        self.amount = amount
        self.label = label
        self.message = message
        self.chainID = chainID
        self.isLightningInvoice = isLightningInvoice
    }

    /// The address in groups of four (SPEC §5), for display.
    public var addressGroups: [String] {
        var groups: [String] = []
        var current = ""
        for ch in address {
            current.append(ch)
            if current.count == 4 { groups.append(current); current = "" }
        }
        if !current.isEmpty { groups.append(current) }
        return groups
    }
}

public struct AppLinkInfo: Sendable, Hashable {
    /// Lowercased.
    public var scheme: String
    public var original: String

    public init(scheme: String, original: String) {
        self.scheme = scheme
        self.original = original
    }
}

public struct TextInfo: Sendable, Hashable {
    /// The text, cut at 8 KB when `isTruncated`.
    public var text: String
    public var isTruncated: Bool
    /// Links found inside (SPEC §4.2 embedded links), at most 10.
    public var links: [String]

    public init(text: String, isTruncated: Bool = false, links: [String] = []) {
        self.text = text
        self.isTruncated = isTruncated
        self.links = links
    }
}
