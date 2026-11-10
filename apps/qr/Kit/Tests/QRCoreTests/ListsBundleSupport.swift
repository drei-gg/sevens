import Foundation
@testable import QRCore

/// Builds a `lists.json` model from the files that ship in `Data/`, for the Lists tests.
enum ListsBundleSupport {
    static func decode<T: Decodable>(_ name: String, as: T.Type) throws -> T {
        let data = try need(BundledData.resource(name), name)
        return try JSONDecoder().decode(T.self, from: data)
    }

    static func files() throws -> ListsFiles {
        let psl = String(decoding: try need(BundledData.resource("public_suffix_list.dat"), "psl"), as: UTF8.self)
        let rdap = try decode("rdap-dns.json", as: RDAPFile.self)
        return ListsFiles(
            brands: try decode("brands.json", as: [Brand].self),
            shorteners: try decode("shorteners.json", as: [String].self),
            riskyTLDs: try decode("risky-tlds.json", as: [RiskyTLD].self),
            baitWords: try decode("bait-words.json", as: [String].self),
            paymentNetworks: try decode("payment-networks.json", as: PaymentNetworks.self),
            confusables: try decode("confusables.json", as: [String: String].self),
            publicSuffixList: psl,
            rdap: RDAPFile(description: rdap.description, publication: rdap.publication, version: rdap.version,
                           services: rdap.services),
            trackingParams: try decode("tracking-params.json", as: [TrackingParam].self))
    }

    static func bundle(version: Int, built: String = "2027-01-02") throws -> ListsBundle {
        ListsBundle(schema: ListsBundle.currentSchema, version: version, built: built, files: try files())
    }

    static func bytes(_ bundle: ListsBundle) throws -> Data {
        try JSONEncoder().encode(bundle)
    }

    struct Missing: Error { let what: String }
    static func need<T>(_ value: T?, _ what: String) throws -> T {
        guard let value else { throw Missing(what: what) }
        return value
    }
}
