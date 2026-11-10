import Foundation

// WP 2.1 owns this file. Phase 0 draft: loads every file in Data/, missing files give empty
// values. Public members are the seam; keep them.

/// Everything in `Data/`, parsed once. Immutable after load.
public struct BundledData: Sendable {
    public var publicSuffixes: PublicSuffixList
    public var brands: [Brand]
    public var shorteners: Set<String>
    public var riskyTLDs: [RiskyTLD]
    /// Single characters or short sequences → their Latin look-alike (SPEC §4.2 `lookalikeBrand`).
    public var confusables: [String: String]
    public var baitWords: [String]
    public var paymentNetworks: PaymentNetworks
    /// ISO 4217 numeric → alpha.
    public var currencies: [String: String]
    public var rdap: RDAPBootstrap

    public init(publicSuffixes: PublicSuffixList, brands: [Brand] = [], shorteners: Set<String> = [],
                riskyTLDs: [RiskyTLD] = [], confusables: [String: String] = [:], baitWords: [String] = [],
                paymentNetworks: PaymentNetworks = PaymentNetworks(), currencies: [String: String] = [:],
                rdap: RDAPBootstrap = RDAPBootstrap(json: Data())) {
        self.publicSuffixes = publicSuffixes
        self.brands = brands
        self.shorteners = shorteners
        self.riskyTLDs = riskyTLDs
        self.confusables = confusables
        self.baitWords = baitWords
        self.paymentNetworks = paymentNetworks
        self.currencies = currencies
        self.rdap = rdap
    }

    /// Loaded on first use, thread-safely. The app touches it on a background task at launch
    /// so the first `analyze` rarely waits (SPEC §4.3).
    public static let shared: BundledData = load()

    /// Starts loading `shared` on a background task. Cheap; call once at launch.
    public static func preload() {
        _ = Task.detached(priority: .utility) { _ = BundledData.shared }
    }

    /// Loads from the package's own resource bundle (`Data/` copied as a folder).
    public static func load() -> BundledData {
        func data(_ name: String) -> Data? {
            guard let dir = Bundle.module.url(forResource: "Data", withExtension: nil) else { return nil }
            return try? Data(contentsOf: dir.appendingPathComponent(name))
        }
        func json<T: Decodable>(_ name: String, as: T.Type) -> T? {
            guard let d = data(name) else { return nil }
            return try? JSONDecoder().decode(T.self, from: d)
        }
        let psl = data("public_suffix_list.dat").flatMap { String(data: $0, encoding: .utf8) } ?? ""
        return BundledData(
            publicSuffixes: PublicSuffixList(text: psl),
            brands: json("brands.json", as: [Brand].self) ?? [],
            shorteners: Set(json("shorteners.json", as: [String].self) ?? []),
            riskyTLDs: json("risky-tlds.json", as: [RiskyTLD].self) ?? [],
            confusables: json("confusables.json", as: [String: String].self) ?? [:],
            baitWords: json("bait-words.json", as: [String].self) ?? [],
            paymentNetworks: json("payment-networks.json", as: PaymentNetworks.self) ?? PaymentNetworks(),
            currencies: json("currencies.json", as: [String: String].self) ?? [:],
            rdap: RDAPBootstrap(json: data("rdap-dns.json") ?? Data())
        )
    }
}
