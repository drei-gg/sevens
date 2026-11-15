import CryptoKit
import Foundation
import QRCore
@testable import QRUI

/// A signed bundle, a key pair and a place to save it, all made inside the test.
final class ListsTestRig: @unchecked Sendable {
    let key = Curve25519.Signing.PrivateKey()
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("lists-ui-\(UUID().uuidString)")
    private let lock = NSLock()
    private var applied: [Data?] = []
    var installed: Int64 = 100

    deinit { try? FileManager.default.removeItem(at: directory) }

    var publicHex: String { Self.hex(key.publicKey.rawRepresentation) }
    var store: ListsStore { ListsStore(directory: directory) }
    var appliedCalls: [Data?] { lock.withLock { applied } }

    static func hex(_ data: Data) -> String { data.map { String(format: "%02x", $0) }.joined() }

    func configuration(owner: String = "acme", repo: String = "kit", extraKeys: [String] = []) -> ListsConfiguration {
        ListsConfiguration(repositoryURL: "https://github.com/\(owner)/\(repo)", keys: [publicHex] + extraKeys)!
    }

    func updater(owner: String = "acme", repo: String = "kit", stubbed: Bool = true,
                 configuration custom: ListsConfiguration? = nil) -> ListsUpdater {
        ListsUpdater(configuration: custom ?? configuration(owner: owner, repo: repo), store: store,
                     protocolClasses: stubbed ? [PeekStubProtocol.self] : nil,
                     installedVersion: { [unowned self] in lock.withLock { installed } },
                     apply: { [unowned self] data in lock.withLock { applied.append(data) } })
    }

    func sign(_ bytes: Data) -> Data { (try? key.signature(for: bytes)) ?? Data() }

    // MARK: Synthetic lists that pass the shape rules

    static func files() -> TestListsFiles {
        let brands = (0..<130).map { i in
            TestBrand(name: "Brand \(i)", keywords: ["brand\(i)x"], domains: ["brand\(i).com"],
                      category: "bank", region: "us", ambiguous: false)
        }
        var confusables = (0..<45).reduce(into: [String: String]()) { $0["k\($1)"] = "a" }
        for (k, v) in ["0": "o", "1": "l", "i": "l", "rn": "m", "vv": "w", "\u{0430}": "a", "\u{03BF}": "o", "\u{FF41}": "a"] {
            confusables[k] = v
        }
        let crypto = ["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"]
        let bait = ["login", "verify", "secure", "account"] + (0..<30).map { "bait\($0)" }
        return TestListsFiles(
            brands: brands,
            shorteners: (0..<65).map { "s\($0).io" },
            riskyTLDs: (0..<20).map { TestSourced(tld: "t\($0)", source: "https://example.org/report/\($0)") },
            baitWords: bait,
            paymentNetworks: TestPaymentNetworks(
                emv: [TestEMVNetwork(id: "com.example", name: "Example Pay", country: "PH", party: "person", source: "https://example.org/spec")],
                cryptoSchemes: crypto.map { TestCryptoScheme(scheme: $0, name: $0.capitalized) }),
            confusables: confusables,
            publicSuffixList: "// ===BEGIN ICANN DOMAINS===\ncom\nio\n// ===END ICANN DOMAINS===\n",
            rdap: TestRDAPFile(description: "test", publication: "2026-11-10T00:00:00Z", version: "1.0",
                               services: (0..<60).map { [["tld\($0)"], ["https://rdap.example.org/"]] } + [[["com"], ["https://rdap.example.org/"]]]),
            trackingParams: [TestTrackingParam(name: "utm_*", source: "https://example.org/utm")])
    }

    static func bundle(version: Int, built: String = "2026-12-01", schema: Int = 1) -> TestLists {
        TestLists(schema: schema, version: version, built: built, files: files())
    }

    static func bytes(_ bundle: TestLists) -> Data { (try? JSONEncoder().encode(bundle)) ?? Data() }
}

// The `lists.json` format as `scripts/refresh-lists` writes it, built here so the tests can sign and
// break it. The app never decodes it in Swift: the core does (`lists_bundle_check`).

struct TestLists: Codable {
    var schema: Int
    var version: Int
    var built: String
    var files: TestListsFiles
}

struct TestListsFiles: Codable {
    var brands: [TestBrand]
    var shorteners: [String]
    var riskyTLDs: [TestSourced]
    var baitWords: [String]
    var paymentNetworks: TestPaymentNetworks
    var confusables: [String: String]
    var publicSuffixList: String
    var rdap: TestRDAPFile
    var trackingParams: [TestTrackingParam]?
}

struct TestBrand: Codable {
    var name: String
    var keywords: [String]
    var domains: [String]
    var category: String
    var region: String
    var ambiguous: Bool
}

struct TestSourced: Codable {
    var tld: String
    var source: String
}

struct TestEMVNetwork: Codable {
    var id: String
    var name: String
    var country: String
    var party: String
    var source: String
}

struct TestCryptoScheme: Codable {
    var scheme: String
    var name: String
}

struct TestPaymentNetworks: Codable {
    var emv: [TestEMVNetwork]
    var cryptoSchemes: [TestCryptoScheme]
}

struct TestRDAPFile: Codable {
    var description: String?
    var publication: String?
    var version: String?
    var services: [[[String]]]
}

struct TestTrackingParam: Codable {
    var name: String
    var source: String
}
