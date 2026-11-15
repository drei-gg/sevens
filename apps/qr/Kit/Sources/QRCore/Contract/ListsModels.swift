// Contract, added at qr-contract-2 (SPEC §4.3, §8): the shapes of the lists that can age and of the
// signed lists bundle that "Check for newer lists" fetches.

/// One tracking tag Make offers to remove (`tracking-params.json`, SPEC §8).
public struct TrackingParam: Codable, Sendable, Hashable {
    /// Lowercase query-parameter name. A trailing `*` means "this prefix" (`utm_*`).
    public var name: String
    /// URL of a public page that documents the tag.
    public var source: String

    public init(name: String, source: String) {
        self.name = name
        self.source = source
    }

    /// Case-insensitive; `utm_*` matches `utm_source` but not `utm` or `xutm_source`.
    public func matches(_ key: String) -> Bool {
        let key = key.lowercased()
        if name.hasSuffix("*") {
            let prefix = String(name.dropLast())
            return !prefix.isEmpty && key.hasPrefix(prefix) && key.count > prefix.count
        }
        return key == name
    }
}

/// `lists-version.json`, and the header of a downloaded bundle.
public struct ListsVersion: Codable, Sendable, Hashable, Comparable {
    /// Bundle format this file belongs to. The app understands `ListsBundle.currentSchema`.
    public var schema: Int
    /// Strictly increasing across every lists release (bundled or published): yyyymmdd × 100 + n.
    public var version: Int
    /// ISO 8601 date the lists were built ("2026-11-10").
    public var built: String

    public init(schema: Int, version: Int, built: String) {
        self.schema = schema
        self.version = version
        self.built = built
    }

    public static func < (a: ListsVersion, b: ListsVersion) -> Bool { a.version < b.version }
}

/// IANA's RDAP bootstrap file, as carried inside a bundle (`rdap-dns.json`).
public struct RDAPFile: Codable, Sendable, Hashable {
    public var description: String?
    public var publication: String?
    public var version: String?
    /// [[endings], [service URLs]] pairs.
    public var services: [[[String]]]

    public init(description: String? = nil, publication: String? = nil, version: String? = nil,
                services: [[[String]]]) {
        self.description = description
        self.publication = publication
        self.version = version
        self.services = services
    }
}

/// The files a bundle replaces. Currencies (ISO 4217) don't age and aren't carried.
public struct ListsFiles: Codable, Sendable, Hashable {
    public var brands: [Brand]
    public var shorteners: [String]
    public var riskyTLDs: [RiskyTLD]
    public var baitWords: [String]
    public var paymentNetworks: PaymentNetworks
    public var confusables: [String: String]
    /// The text of `public_suffix_list.dat`.
    public var publicSuffixList: String
    public var rdap: RDAPFile
    /// Optional so a bundle from before `tracking-params.json` existed still decodes.
    public var trackingParams: [TrackingParam]?

    public init(brands: [Brand], shorteners: [String], riskyTLDs: [RiskyTLD], baitWords: [String],
                paymentNetworks: PaymentNetworks, confusables: [String: String], publicSuffixList: String,
                rdap: RDAPFile, trackingParams: [TrackingParam]? = nil) {
        self.brands = brands
        self.shorteners = shorteners
        self.riskyTLDs = riskyTLDs
        self.baitWords = baitWords
        self.paymentNetworks = paymentNetworks
        self.confusables = confusables
        self.publicSuffixList = publicSuffixList
        self.rdap = rdap
        self.trackingParams = trackingParams
    }
}

/// `lists.json`: `{ "schema", "version", "built", "files" }` (SPEC §4.3).
public struct ListsBundle: Codable, Sendable, Hashable {
    /// The only schema this app version accepts.
    public static let currentSchema = 1
    /// Larger downloads are refused before decoding.
    public static let maxBytes = 2 * 1024 * 1024

    public var schema: Int
    public var version: Int
    public var built: String
    public var files: ListsFiles

    public init(schema: Int, version: Int, built: String, files: ListsFiles) {
        self.schema = schema
        self.version = version
        self.built = built
        self.files = files
    }

    public var header: ListsVersion { ListsVersion(schema: schema, version: version, built: built) }
}

/// Why a downloaded bundle was not used. QRUI turns each into one plain sentence.
public enum ListsBundleError: Error, Sendable, Hashable {
    case tooLarge
    case badSignature
    case unreadable
    case unknownSchema(Int)
    /// Not newer than what is installed (no rollback).
    case notNewer(installed: Int, offered: Int)
    /// The named file broke a shape rule.
    case badShape(String)
}
