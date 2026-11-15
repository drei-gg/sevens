import Foundation

public enum VaultCredentialKind: String, Codable, CaseIterable, Sendable {
    case passphrase, pin, pattern
    public var title: String { switch self { case .passphrase: "Passphrase"; case .pin: "PIN"; case .pattern: "Pattern" } }
    public func validate(_ value: String) throws {
        switch self {
        case .passphrase:
            guard value.count >= 12, value.utf8.count <= 4096 else { throw VaultError.weakPassword }
        case .pin:
            guard (8...32).contains(value.count), value.utf8.allSatisfy({ (48...57).contains($0) }) else { throw VaultError.weakPIN }
        case .pattern:
            guard let path = PatternPath.decode(value), path.count >= 8 else { throw VaultError.weakPattern }
        }
    }
    /// Domain separation keeps a typed passphrase from impersonating a PIN or pattern.
    public func material(_ value: String) -> String {
        switch self { case .passphrase: value; case .pin: "Vault/PIN/v1/" + value; case .pattern: "Vault/Pattern/5/v1/" + value }
    }
}
public enum PatternPath {
    public static let side = 5
    public static func encode(_ path: [Int]) -> String { path.map(String.init).joined(separator: ",") }
    public static func decode(_ value: String) -> [Int]? {
        let parts = value.split(separator: ",", omittingEmptySubsequences: false)
        let path = parts.compactMap { Int($0) }
        guard !path.isEmpty, path.count == parts.count, path.count <= 25,
              Set(path).count == path.count, path.allSatisfy({ (0..<25).contains($0) }), encode(path) == value else { return nil }
        var canonical: [Int] = []
        for dot in path { append(dot, to: &canonical) }
        return canonical == path ? path : nil
    }
    /// Fill crossed collinear dots so sampling frequency cannot change the credential.
    public static func append(_ dot: Int, to path: inout [Int]) {
        guard (0..<25).contains(dot), !path.contains(dot) else { return }
        if let last = path.last {
            let dx = dot % side - last % side, dy = dot / side - last / side
            var a = abs(dx), b = abs(dy)
            while b != 0 { (a, b) = (b, a % b) }
            if a > 1 {
                for step in 1..<a {
                    let middle = (last / side + dy / a * step) * side + last % side + dx / a * step
                    if !path.contains(middle) { path.append(middle) }
                }
            }
        }
        path.append(dot)
    }
}
