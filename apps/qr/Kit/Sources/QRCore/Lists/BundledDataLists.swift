import Foundation

// Seam added at qr-contract-2 (SPEC §4.3, "Check for newer lists"). R6 owns this folder: keep
// every public signature; replace each `fatalError("R6")`. Signature checking is not here: it
// needs CryptoKit and lives in QRUI's `ListsUpdater`. This half is pure and runs on Linux.
//
// R6 may turn `BundledData.shared` into a computed `static var shared: BundledData { get }` backed
// by a lock, so `reload()` can swap in accepted lists without a relaunch; callers keep writing
// `BundledData.shared`.

extension BundledData {
    /// Data built from a downloaded bundle. Currencies, which the bundle doesn't carry, come from
    /// `base`.
    public init(bundle: ListsBundle, base: BundledData) {
        fatalError("R6")
    }

    /// The same shape rules as `DataShapeTests`, for every file in the bundle, plus the schema.
    /// Throws `ListsBundleError.unknownSchema` or `.badShape(<file name>)`.
    public static func validate(_ bundle: ListsBundle) throws {
        fatalError("R6")
    }

    /// Whichever is newer of the bundled lists and a saved download (the exact bytes of a
    /// `lists.json` that `ListsUpdater` accepted). A missing, unreadable, invalid or older download
    /// gives the bundled data.
    public static func load(downloaded: Data?) -> BundledData {
        fatalError("R6")
    }
}
