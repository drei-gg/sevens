import Foundation

// Signature checking is not here: it needs CryptoKit and lives in QRUI's `ListsUpdater`. This half
// is pure and runs on Linux (SPEC §4.3).

extension BundledData {
    /// Data built from a downloaded bundle. Currencies, which the bundle doesn't carry, come from
    /// `base`, and so do tracking tags when the bundle has none.
    public init(bundle: ListsBundle, base: BundledData) {
        let files = bundle.files
        let rdapJSON = (try? JSONEncoder().encode(files.rdap)) ?? Data()
        self.init(
            publicSuffixes: PublicSuffixList(text: files.publicSuffixList),
            brands: files.brands,
            shorteners: Set(files.shorteners),
            riskyTLDs: files.riskyTLDs,
            confusables: files.confusables,
            baitWords: files.baitWords,
            paymentNetworks: files.paymentNetworks,
            currencies: base.currencies,
            rdap: RDAPBootstrap(json: rdapJSON),
            trackingParams: files.trackingParams ?? base.trackingParams,
            lists: bundle.header
        )
    }

    /// The same shape rules as `DataShapeTests`, for every file in the bundle, plus the schema.
    /// Throws `ListsBundleError.unknownSchema` or `.badShape(<file name>)`.
    public static func validate(_ bundle: ListsBundle) throws {
        guard bundle.schema == ListsBundle.currentSchema else { throw ListsBundleError.unknownSchema(bundle.schema) }
        guard bundle.version > 0 else { throw ListsBundleError.badShape("version") }
        guard ListsValidation.isISODate(bundle.built) else { throw ListsBundleError.badShape("built") }
        let files = bundle.files
        let checks: [(String, Bool)] = [
            ("brands", ListsValidation.brands(files.brands, bait: Set(files.baitWords))),
            ("shorteners", ListsValidation.shorteners(files.shorteners)),
            ("riskyTLDs", ListsValidation.riskyTLDs(files.riskyTLDs)),
            ("baitWords", ListsValidation.baitWords(files.baitWords)),
            ("paymentNetworks", ListsValidation.paymentNetworks(files.paymentNetworks)),
            ("confusables", ListsValidation.confusables(files.confusables)),
            ("publicSuffixList", ListsValidation.publicSuffixList(files.publicSuffixList)),
            ("rdap", ListsValidation.rdap(files.rdap)),
            ("trackingParams", files.trackingParams.map(ListsValidation.trackingParams) ?? true),
        ]
        if let bad = checks.first(where: { !$0.1 }) { throw ListsBundleError.badShape(bad.0) }
    }

    /// Whichever is newer of the bundled lists and a saved download (the exact bytes of a
    /// `lists.json` that `ListsUpdater` accepted). A missing, unreadable, invalid or older download
    /// gives the bundled data.
    public static func load(downloaded: Data?) -> BundledData {
        let bundled = load()
        guard let downloaded, downloaded.count <= ListsBundle.maxBytes,
              let bundle = try? JSONDecoder().decode(ListsBundle.self, from: downloaded),
              bundle.version > bundled.lists.version,
              (try? validate(bundle)) != nil else { return bundled }
        return BundledData(bundle: bundle, base: bundled)
    }
}
