import Foundation

// Vault's logic is the Rust crate apps/vault/core; its Swift bindings (Generated/VaultCoreFFI.swift)
// compile into this module. This file keeps the Swift spellings the app was written against
// (PatternPath, `kind.validate`, `order.sorted`, `item.type`, `VaultLibrary(name:)`) and only
// converts: Int to the core's Int32, error sentences, Identifiable and CaseIterable conformances.
// No rule or calculation lives here.

// MARK: errors

public extension Error {
    /// The sentence to show the user. The generated `VaultError.errorDescription` is only the case
    /// name, so Vault's own errors take the core's sentence; anything else is Foundation's.
    var vaultMessage: String {
        if let error = self as? VaultError { return vaultErrorDescription(error: error) }
        if let error = self as? RecognitionFailure { return recognitionFailureDescription(error: error) }
        return localizedDescription
    }
}

// MARK: credentials

extension VaultCredentialKind: CaseIterable {
    public static var allCases: [VaultCredentialKind] { [.passphrase, .pin, .pattern] }
}

public extension VaultCredentialKind {
    var title: String { credentialKindTitle(kind: self) }
    func validate(_ value: String) throws { try credentialKindValidate(kind: self, value: value) }
    /// The string fed to key derivation for `value` typed as this kind.
    func material(_ value: String) -> String { credentialKindMaterial(kind: self, value: value) }
}

/// The 5×5 pattern as the gesture view handles it: dot indexes as `Int`.
public enum PatternPath {
    public static func encode(_ path: [Int]) -> String { patternEncode(path: path.map { Int32(clamping: $0) }) }
    public static func decode(_ value: String) -> [Int]? { patternDecode(value: value)?.map(Int.init) }
    public static func append(_ dot: Int, to path: inout [Int]) {
        path = patternAppend(dot: Int32(clamping: dot), path: path.map { Int32(clamping: $0) }).map(Int.init)
    }
}

// MARK: library

extension VaultItem: Identifiable {}
extension VaultFolder: Identifiable {}

public extension VaultItem {
    /// The uniform type identifier (the index's `type` key).
    var type: String { get { contentType } set { contentType = newValue } }
    func matches(_ query: String) -> Bool { vaultItemMatches(item: self, query: query) }
}

extension VaultOrder: CaseIterable {
    public static var allCases: [VaultOrder] { [.newest, .oldest, .name, .largest, .manual] }
}

public extension VaultOrder {
    /// The index's name: "newest", "oldest", "name", "largest", "manual".
    var rawValue: String { String(describing: self) }
    func sorted(_ items: [VaultItem]) -> [VaultItem] { vaultOrderSorted(order: self, items: items) }
}

public extension VaultLibrary {
    init(name: String) { self = newVaultLibrary(name: name) }
}
