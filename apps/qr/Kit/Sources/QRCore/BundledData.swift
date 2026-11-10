import Foundation

// Loads every file in Data/, missing files give empty values. `shared` is what the analyzer and
// Make read; accepted downloaded lists swap it in place (SPEC §4.3, Lists/BundledDataLists.swift).

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
    /// Tracking tags Make offers to remove (qr-contract-2, SPEC §8).
    public var trackingParams: [TrackingParam]
    /// When these lists were built (qr-contract-2, SPEC §4.3).
    public var lists: ListsVersion

    public init(publicSuffixes: PublicSuffixList, brands: [Brand] = [], shorteners: Set<String> = [],
                riskyTLDs: [RiskyTLD] = [], confusables: [String: String] = [:], baitWords: [String] = [],
                paymentNetworks: PaymentNetworks = PaymentNetworks(), currencies: [String: String] = [:],
                rdap: RDAPBootstrap = RDAPBootstrap(json: Data()), trackingParams: [TrackingParam] = [],
                lists: ListsVersion = ListsVersion(schema: ListsBundle.currentSchema, version: 0, built: "")) {
        self.publicSuffixes = publicSuffixes
        self.brands = brands
        self.shorteners = shorteners
        self.riskyTLDs = riskyTLDs
        self.confusables = confusables
        self.baitWords = baitWords
        self.paymentNetworks = paymentNetworks
        self.currencies = currencies
        self.rdap = rdap
        self.trackingParams = trackingParams
        self.lists = lists
    }

    /// The lists in use: the bundled ones, or a saved download when it is newer (SPEC §4.3).
    /// Loaded on first use, thread-safely. The app touches it on a background task at launch
    /// so the first `analyze` rarely waits. `reload(downloaded:)` replaces it.
    public static var shared: BundledData {
        sharedBox.get { load(downloaded: ListsStore().read()) }
    }

    /// Swaps in the bundled lists or a newer download, whichever has the higher version. QRUI
    /// calls this after accepting a download (with its bytes) or after a reset (with nil).
    public static func reload(downloaded: Data?) {
        sharedBox.set(load(downloaded: downloaded))
    }

    /// Starts loading `shared` on a background task. Cheap; call once at launch.
    public static func preload() {
        _ = Task.detached(priority: .utility) { _ = BundledData.shared }
    }

    private static let sharedBox = SharedBox()

    /// One file of `Data/`, from the package's own resource bundle (copied as a folder).
    static func resource(_ name: String) -> Data? {
        guard let dir = Bundle.module.url(forResource: "Data", withExtension: nil) else { return nil }
        return try? Data(contentsOf: dir.appendingPathComponent(name))
    }

    /// The version of the lists that shipped with the app, whatever is saved.
    public static func bundledListsVersion() -> ListsVersion {
        resource("lists-version.json").flatMap { try? JSONDecoder().decode(ListsVersion.self, from: $0) }
            ?? ListsVersion(schema: ListsBundle.currentSchema, version: 0, built: "")
    }

    /// Loads only what shipped with the app (`Data/` copied as a folder).
    public static func load() -> BundledData {
        func data(_ name: String) -> Data? { resource(name) }
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
            rdap: RDAPBootstrap(json: data("rdap-dns.json") ?? Data()),
            trackingParams: json("tracking-params.json", as: [TrackingParam].self) ?? [],
            lists: json("lists-version.json", as: ListsVersion.self)
                ?? ListsVersion(schema: ListsBundle.currentSchema, version: 0, built: "")
        )
    }
}

/// The one shared instance, behind a lock so it can be replaced.
private final class SharedBox: @unchecked Sendable {
    private let lock = NSLock()
    private var value: BundledData?

    func get(_ make: () -> BundledData) -> BundledData {
        lock.lock()
        defer { lock.unlock() }
        if let value { return value }
        let made = make()
        value = made
        return made
    }

    func set(_ new: BundledData) {
        lock.lock()
        value = new
        lock.unlock()
    }
}
