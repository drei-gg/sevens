import CryptoKit
import Foundation

/// Where newer lists come from and which keys may sign them (SPEC §4.3). Both come from the
/// brand's `Brand.xcconfig` through Info.plist. Without a repository and at least one valid key
/// there is no configuration, and Settings hides the Check for newer lists button.
struct ListsConfiguration: Sendable, Equatable {
    static let host = "raw.githubusercontent.com"
    /// The branch the publishing workflow writes to.
    static let branch = "lists"

    let owner: String
    let repository: String
    /// Raw 32-byte Ed25519 public keys: the current one and, while a key is being rotated, the next.
    let keys: [Data]

    /// - Parameters:
    ///   - repositoryURL: `https://github.com/<owner>/<repo>`, as in the public code's address.
    ///   - keys: 64-character hex strings; blank or malformed ones are ignored.
    init?(repositoryURL: String?, keys hex: [String?]) {
        guard let (owner, repository) = Self.parse(repositoryURL) else { return nil }
        var keys: [Data] = []
        for text in hex {
            guard let raw = Self.data(hex: text), (try? Curve25519.Signing.PublicKey(rawRepresentation: raw)) != nil,
                  !keys.contains(raw) else { continue }
            keys.append(raw)
        }
        guard !keys.isEmpty else { return nil }
        self.owner = owner
        self.repository = repository
        self.keys = keys
    }

    /// `https://raw.githubusercontent.com/<owner>/<repo>/lists/<name>`.
    func fileURL(_ name: String) -> URL? {
        URL(string: "https://\(Self.host)/\(owner)/\(repository)/\(Self.branch)/\(name)")
    }

    private static func parse(_ text: String?) -> (String, String)? {
        guard let text = text?.trimmingCharacters(in: .whitespacesAndNewlines) else { return nil }
        let prefix = "https://github.com/"
        guard text.lowercased().hasPrefix(prefix) else { return nil }
        var parts = text.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        guard parts.count == 2 else { return nil }
        if parts[1].hasSuffix(".git") { parts[1].removeLast(4) }
        let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")
        for part in parts {
            guard !part.isEmpty, part != ".", part != "..", part.count <= 100, part.allSatisfy({ allowed.contains($0) })
            else { return nil }
        }
        return (parts[0], parts[1])
    }

    private static func data(hex: String?) -> Data? {
        guard let text = hex?.trimmingCharacters(in: .whitespacesAndNewlines), text.utf8.count == 64 else { return nil }
        var bytes: [UInt8] = []
        var high: UInt8?
        for unit in text.utf8 {
            let value: UInt8
            switch unit {
            case 0x30...0x39: value = unit - 0x30
            case 0x61...0x66: value = unit - 0x61 + 10
            case 0x41...0x46: value = unit - 0x41 + 10
            default: return nil
            }
            if let h = high { bytes.append(h << 4 | value); high = nil } else { high = value }
        }
        return Data(bytes)
    }
}
