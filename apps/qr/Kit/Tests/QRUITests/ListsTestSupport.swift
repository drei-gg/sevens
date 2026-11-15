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
    var installed = 100

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

    static func files() -> ListsFiles {
        let brands = (0..<130).map { i in
            Brand(name: "Brand \(i)", keywords: ["brand\(i)x"], domains: ["brand\(i).com"],
                  category: "bank", region: "us", ambiguous: false)
        }
        var confusables = (0..<45).reduce(into: [String: String]()) { $0["k\($1)"] = "a" }
        for (k, v) in ["0": "o", "1": "l", "i": "l", "rn": "m", "vv": "w", "\u{0430}": "a", "\u{03BF}": "o", "\u{FF41}": "a"] {
            confusables[k] = v
        }
        let crypto = ["bitcoin", "ethereum", "litecoin", "bitcoincash", "dogecoin", "monero", "lightning"]
        let bait = ["login", "verify", "secure", "account"] + (0..<30).map { "bait\($0)" }
        return ListsFiles(
            brands: brands,
            shorteners: (0..<65).map { "s\($0).io" },
            riskyTLDs: (0..<20).map { RiskyTLD(tld: "t\($0)", source: "https://example.org/report/\($0)") },
            baitWords: bait,
            paymentNetworks: PaymentNetworks(
                emv: [EMVNetwork(id: "com.example", name: "Example Pay", country: "PH", party: "person", source: "https://example.org/spec")],
                cryptoSchemes: crypto.map { CryptoScheme(scheme: $0, name: $0.capitalized) }),
            confusables: confusables,
            publicSuffixList: "// ===BEGIN ICANN DOMAINS===\ncom\nio\n// ===END ICANN DOMAINS===\n",
            rdap: RDAPFile(description: "test", publication: "2026-11-10T00:00:00Z", version: "1.0",
                           services: (0..<60).map { [["tld\($0)"], ["https://rdap.example.org/"]] } + [[["com"], ["https://rdap.example.org/"]]]),
            trackingParams: [TrackingParam(name: "utm_*", source: "https://example.org/utm")])
    }

    static func bundle(version: Int, built: String = "2026-12-01", schema: Int = ListsBundle.currentSchema) -> ListsBundle {
        ListsBundle(schema: schema, version: version, built: built, files: files())
    }

    static func bytes(_ bundle: ListsBundle) -> Data { (try? JSONEncoder().encode(bundle)) ?? Data() }
}
