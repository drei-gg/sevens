import Foundation

public enum VaultError: Error, LocalizedError, Sendable {
    case weakPIN, weakPattern
    case invalidCredential, weakPassword, damaged, unsupported, locked, duplicateVault, invalidName, missingFile, noSpace
    public var errorDescription: String? {
        switch self {
        case .invalidCredential: "That credential did not open a vault. Check the passphrase or recovery key."
        case .weakPIN: "Use 8–32 digits. Avoid dates and repeated sequences."
        case .weakPattern: "Connect at least 8 different dots on the 5×5 grid."
        case .weakPassword: "Use a passphrase of at least 12 characters."
        case .damaged: "The data could not be verified. The existing vault has not been replaced."
        case .unsupported: "This format or version is not supported."
        case .locked: "Unlock the vault to continue."
        case .duplicateVault: "A vault with this identity or unlock secret already exists. Choose another secret; restore never replaces an existing vault."
        case .invalidName: "Enter a name without path separators, up to 200 characters."
        case .missingFile: "A file is missing. Existing data has been preserved."
        case .noSpace: "There is not enough available storage to finish this operation."
        }
    }
}

public struct VaultItem: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var name: String
    public var type: String
    public var bytes: Int64
    public var digest: String
    public var created: Date
    public var folder: UUID?
    public var favorite: Bool
    public var deleted: Date?
    public var order: Int
    public init(id: UUID = UUID(), name: String, type: String, bytes: Int64, digest: String, folder: UUID? = nil, order: Int = 0) {
        self.id = id; self.name = name; self.type = type; self.bytes = bytes; self.digest = digest
        self.folder = folder; self.order = order; created = Date(); favorite = false
    }
    public func matches(_ query: String) -> Bool { query.isEmpty || name.localizedStandardContains(query) }
}
public struct VaultFolder: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var name: String
    public var parent: UUID?
    public init(id: UUID = UUID(), name: String, parent: UUID? = nil) { self.id = id; self.name = name; self.parent = parent }
}
public enum VaultOrder: String, Codable, CaseIterable, Sendable {
    case newest, oldest, name, largest, manual
    public func sorted(_ items: [VaultItem]) -> [VaultItem] {
        items.sorted { a, b in
            switch self {
            case .newest: a.created == b.created ? a.id.uuidString < b.id.uuidString : a.created > b.created
            case .oldest: a.created == b.created ? a.id.uuidString < b.id.uuidString : a.created < b.created
            case .name: a.name == b.name ? a.id.uuidString < b.id.uuidString : a.name.localizedStandardCompare(b.name) == .orderedAscending
            case .largest: a.bytes == b.bytes ? a.id.uuidString < b.id.uuidString : a.bytes > b.bytes
            case .manual: a.order == b.order ? a.id.uuidString < b.id.uuidString : a.order < b.order
            }
        }
    }
}
public struct VaultLibrary: Codable, Equatable, Sendable {
    public var version = 1
    public var id: UUID
    public var name: String
    public var items: [VaultItem] = []
    public var folders: [VaultFolder] = []
    public var sort = VaultOrder.newest
    public var grid = false
    public var backupPrepared: Date?
    public init(id: UUID = UUID(), name: String) { self.id = id; self.name = name }
    public static func validateName(_ name: String) throws {
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.count <= 200, name != ".", name != "..", !name.contains("/"), !name.contains("\\"), !name.contains("\0") else { throw VaultError.invalidName }
    }
    public func validate() throws {
        guard version == 1 else { throw VaultError.unsupported }
        try Self.validateName(name)
        let folderIDs = Set(folders.map(\.id))
        guard folderIDs.count == folders.count, Set(items.map(\.id)).count == items.count else { throw VaultError.damaged }
        for folder in folders {
            try Self.validateName(folder.name)
            var visited: Set<UUID> = [folder.id]; var next = folder.parent
            while let current = next {
                guard visited.insert(current).inserted, let found = folders.first(where: { $0.id == current }) else { throw VaultError.damaged }
                next = found.parent
            }
        }
        for item in items {
            try Self.validateName(item.name)
            guard item.bytes >= 0, item.digest.count == 64, item.digest.allSatisfy({ $0.isHexDigit }), item.folder == nil || folderIDs.contains(item.folder!) else { throw VaultError.damaged }
        }
    }
}
