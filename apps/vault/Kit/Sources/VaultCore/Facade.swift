import Foundation

// Vault's logic is the Rust crate apps/vault/core; its Swift bindings (Generated/VaultCoreFFI.swift)
// compile into this module. This file keeps the Swift spellings the app was written against
// (PatternPath, `kind.validate`, `order.sorted`, `item.type`, `VaultLibrary(name:)`) and only
// converts: Int to the core's Int32, error sentences, Identifiable and CaseIterable conformances.
// No rule or calculation lives here.

// MARK: errors

// The core reports typed cases; the app words them through its String Catalog
// (App/Localizable.xcstrings, looked up in the main bundle), so translations never pass through the
// core. The core's English is only the fallback when a key is missing (and in this package's tests,
// whose main bundle has no catalog). Every switch is exhaustive: a new core case fails to compile
// here until it has a key, and FacadeTests checks each key is in the catalog.

/// The catalog's format for `key`, filled with `arguments`, or `fallback` when the key is missing.
func catalogMessage(_ key: String, _ arguments: [CVarArg] = [], fallback: () -> String) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: key, table: nil)
    if format == key { return fallback() }
    return arguments.isEmpty ? format : String(format: format, locale: .current, arguments: arguments)
}

public extension VaultError {
    var catalogKey: String {
        switch self {
        case .WeakPin: "error.weakPin"
        case .WeakPattern: "error.weakPattern"
        case .InvalidCredential: "error.invalidCredential"
        case .WeakPassword: "error.weakPassword"
        case .Damaged: "error.damaged"
        case .Unsupported: "error.unsupported"
        case .Locked: "error.locked"
        case .DuplicateVault: "error.duplicateVault"
        case .InvalidName: "error.invalidName"
        case .MissingFile: "error.missingFile"
        case .NoSpace: "error.noSpace"
        case .OlderBackup: "error.olderBackup"
        case .SecretInUse: "error.secretInUse"
        }
    }
    var message: String { catalogMessage(catalogKey) { vaultErrorDescription(error: self) } }
}

public extension RecognitionFailure {
    var catalogKey: String {
        switch self {
        case .TooMuchText: "error.recognition.tooMuchText"
        case .InvalidPage: "error.recognition.invalidPage"
        }
    }
    var message: String { catalogMessage(catalogKey) { recognitionFailureDescription(error: self) } }
}

public extension VaultIssue {
    var catalogKey: String {
        switch self {
        case .noReadablePages: "issue.noReadablePages"
        case .pageRange: "issue.pageRange"
        case .tooManyPages: "issue.tooManyPages"
        case .nothingSelected: "issue.nothingSelected"
        case .tooManyFiles: "issue.tooManyFiles"
        case .selectionChanged: "issue.selectionChanged"
        case .unsupportedSelection: "issue.unsupportedSelection"
        case .queryTooLong: "issue.queryTooLong"
        }
    }
    /// The numbers the sentence names, in order (each `%lld` in the catalog).
    var arguments: [CVarArg] {
        switch self {
        case .noReadablePages, .nothingSelected, .selectionChanged, .unsupportedSelection: []
        case .pageRange(let pageCount): [Int(pageCount)]
        case .tooManyPages(let maximum), .tooManyFiles(let maximum), .queryTooLong(let maximum): [Int(maximum)]
        }
    }
    var message: String { catalogMessage(catalogKey, arguments) { vaultIssueDescription(issue: self) } }
}

public extension Error {
    /// The sentence to show the user: Vault's own errors through the catalog; anything else is
    /// Foundation's. (The generated `errorDescription` is only the case name.)
    var vaultMessage: String {
        if let error = self as? VaultError { return error.message }
        if let error = self as? RecognitionFailure { return error.message }
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
