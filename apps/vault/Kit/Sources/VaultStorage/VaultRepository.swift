#if canImport(CryptoKit)
import Foundation
import CryptoKit
import VaultCore

public struct CreatedVault: Sendable { public let library: VaultLibrary; public let recoveryKey: String }
public actor VaultRepository {
    public let root: URL
    public let temporary: URL
    private var key: SymmetricKey?
    private var library: VaultLibrary?
    private var directory: URL?
    public init(root: URL, temporary: URL) { self.root = root; self.temporary = temporary }

    public func prepare() throws {
        try ProtectedFiles.directory(root)
        try clearTemporary()
        // Staging is never treated as a committed vault. Failed work does not overwrite older data.
        for url in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) where url.lastPathComponent.hasPrefix(".stage-") { try? FileManager.default.removeItem(at: url) }
    }
    public func clearTemporary() throws {
        if FileManager.default.fileExists(atPath: temporary.path) { try FileManager.default.removeItem(at: temporary) }
        try ProtectedFiles.directory(temporary)
    }
    public func lock() { key = nil; library = nil; directory = nil; try? clearTemporary() }
    public func snapshot() throws -> VaultLibrary { guard let library else { throw VaultError.locked }; return library }
    private func session() throws -> (SymmetricKey, VaultLibrary, URL) {
        guard let key, let library, let directory else { throw VaultError.locked }; return (key, library, directory)
    }
    private func folders() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]).filter {
            UUID(uuidString: $0.lastPathComponent) != nil && (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true && (try? $0.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true
        }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
    private func header(at folder: URL) throws -> VaultHeader {
        let header = try JSONDecoder().decode(VaultHeader.self, from: ProtectedFiles.boundedRead(folder.appendingPathComponent("header.json"), max: 16_384))
        guard header.version == 1 else { throw VaultError.unsupported }; return header
    }
    private func load(at folder: URL, header: VaultHeader, key: SymmetricKey) throws -> VaultLibrary {
        let sealed = try ProtectedFiles.boundedRead(folder.appendingPathComponent("index.sealed"))
        let data = try VaultCrypto.open(sealed, key: key, context: "Vault/index/v1/" + header.id.uuidString)
        let library = try JSONDecoder().decode(VaultLibrary.self, from: data)
        guard library.id == header.id else { throw VaultError.damaged }; try library.validate(); return library
    }
    private func save(_ changed: VaultLibrary, key: SymmetricKey, at folder: URL) throws {
        try changed.validate()
        let data = try JSONEncoder().encode(changed); guard data.count <= 31_000_000 else { throw VaultError.noSpace }
        try ProtectedFiles.write(VaultCrypto.seal(data, key: key, context: "Vault/index/v1/" + changed.id.uuidString), to: folder.appendingPathComponent("index.sealed"))
    }
    public func create(name: String, passphrase: String, kind: VaultCredentialKind = .passphrase) throws -> CreatedVault {
        try VaultLibrary.validateName(name); try kind.validate(passphrase)
        // A credential must select exactly one vault. Check without changing the existing session.
        for folder in try folders() {
            let h = try header(at: folder)
            if (h.credentialKind ?? .passphrase) == kind, (try? VaultCrypto.unwrap(h.password, credential: kind.material(passphrase), context: "Vault/password/v1/" + h.id.uuidString)) != nil { throw VaultError.duplicateVault }
        }
        let value = VaultLibrary(name: name); let key = SymmetricKey(size: .bits256)
        let recovery = try VaultCrypto.random(32).base64EncodedString()
        let header = VaultHeader(id: value.id,
            password: try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: "Vault/password/v1/" + value.id.uuidString),
            recovery: try VaultCrypto.wrap(key, credential: recovery, context: "Vault/recovery/v1/" + value.id.uuidString), credentialKind: kind)
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        try ProtectedFiles.write(JSONEncoder().encode(header), to: staging.appendingPathComponent("header.json")); try save(value, key: key, at: staging)
        let folder = root.appendingPathComponent(value.id.uuidString); try FileManager.default.moveItem(at: staging, to: folder)
        self.key = key; self.library = value; self.directory = folder
        return CreatedVault(library: value, recoveryKey: recovery)
    }
    public func unlock(credential: String, recovery: Bool = false, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        lock()
        var unreadable = false
        for folder in try folders() {
            guard let header = try? header(at: folder) else { unreadable = true; continue }
            guard recovery || (header.credentialKind ?? .passphrase) == kind else { continue }
            let context = "Vault/" + (recovery ? "recovery" : "password") + "/v1/" + header.id.uuidString
            guard let key = try? VaultCrypto.unwrap(recovery ? header.recovery : header.password, credential: recovery ? credential : kind.material(credential), context: context) else { continue }
            let value = try load(at: folder, header: header, key: key)
            self.key = key; self.library = value; self.directory = folder; return value
        }
        if unreadable { throw VaultError.damaged }; throw VaultError.invalidCredential
    }
    public func biometricMaterial() throws -> (UUID, Data) {
        let (key, value, _) = try session(); return (value.id, key.withUnsafeBytes { Data($0) })
    }
    public func unlock(id: UUID, biometricKey: Data) throws -> VaultLibrary {
        lock(); guard biometricKey.count == 32 else { throw VaultError.invalidCredential }
        let folder = root.appendingPathComponent(id.uuidString), key = SymmetricKey(data: biometricKey)
        let value = try load(at: folder, header: header(at: folder), key: key)
        self.key = key; library = value; directory = folder; return value
    }
    public func changePassphrase(_ passphrase: String, kind: VaultCredentialKind = .passphrase) throws {
        try kind.validate(passphrase)
        let (key, value, folder) = try session()
        for other in try folders() where other != folder {
            let h = try header(at: other)
            if (h.credentialKind ?? .passphrase) == kind, (try? VaultCrypto.unwrap(h.password, credential: kind.material(passphrase), context: "Vault/password/v1/" + h.id.uuidString)) != nil { throw VaultError.duplicateVault }
        }
        var header = try header(at: folder)
        header.credentialKind = kind
        header.password = try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: "Vault/password/v1/" + value.id.uuidString)
        try ProtectedFiles.write(JSONEncoder().encode(header), to: folder.appendingPathComponent("header.json"))
    }
    public func replaceRecoveryKey() throws -> String {
        let (key, value, folder) = try session()
        let recovery = try VaultCrypto.random(32).base64EncodedString()
        var header = try header(at: folder)
        header.recovery = try VaultCrypto.wrap(key, credential: recovery, context: "Vault/recovery/v1/" + value.id.uuidString)
        try ProtectedFiles.write(JSONEncoder().encode(header), to: folder.appendingPathComponent("header.json"))
        return recovery
    }
    public func importFile(_ url: URL, name: String, type: String, folder parent: UUID? = nil) throws -> VaultItem {
        let (key, current, folder) = try session(); try VaultLibrary.validateName(name)
        if let parent { guard current.folders.contains(where: { $0.id == parent }) else { throw VaultError.damaged } }
        let resource = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard resource.isRegularFile == true, resource.isSymbolicLink != true else { throw VaultError.unsupported }
        let (bytes, digest) = try VaultCrypto.digest(url)
        let item = VaultItem(name: name, type: type, bytes: bytes, digest: digest, folder: parent, order: (current.items.map(\.order).max() ?? -1) + 1)
        let target = folder.appendingPathComponent(item.id.uuidString + ".aea")
        let staging = folder.appendingPathComponent(".import-" + item.id.uuidString)
        defer { try? FileManager.default.removeItem(at: staging) }
        try VaultCrypto.encrypt(url, to: staging, key: VaultCrypto.fileKey(key, id: item.id)); try ProtectedFiles.protect(staging)
        // Verify the actual encrypted copy before committing or offering source removal.
        try VaultCrypto.verify(staging, key: key, item: item)
        try FileManager.default.moveItem(at: staging, to: target)
        var changed = current; changed.items.append(item)
        do { try save(changed, key: key, at: folder); library = changed }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        return item
    }
    public func addNote(name: String, text: String, folder: UUID?) throws {
        guard text.utf8.count <= 5_000_000 else { throw VaultError.noSpace }
        try VaultLibrary.validateName(name)
        let source = temporary.appendingPathComponent(UUID().uuidString + ".txt")
        defer { try? FileManager.default.removeItem(at: source) }
        try ProtectedFiles.write(Data(text.utf8), to: source)
        _ = try importFile(source, name: name.hasSuffix(".txt") ? name : name + ".txt", type: "public.plain-text", folder: folder)
    }
    public func editNote(_ id: UUID, text: String) throws {
        let (key, current, folder) = try session()
        guard let old = current.items.first(where: { $0.id == id }), old.type == "public.plain-text", text.utf8.count <= 5_000_000 else { throw VaultError.unsupported }
        let source = temporary.appendingPathComponent(UUID().uuidString + ".txt")
        defer { try? FileManager.default.removeItem(at: source) }
        try ProtectedFiles.write(Data(text.utf8), to: source)
        let (bytes, digest) = try VaultCrypto.digest(source)
        var replacement = VaultItem(name: old.name, type: old.type, bytes: bytes, digest: digest, folder: old.folder, order: old.order)
        replacement.created = old.created; replacement.favorite = old.favorite; replacement.deleted = old.deleted
        let target = folder.appendingPathComponent(replacement.id.uuidString + ".aea")
        try VaultCrypto.encrypt(source, to: target, key: VaultCrypto.fileKey(key, id: replacement.id)); try ProtectedFiles.protect(target)
        do {
            try VaultCrypto.verify(target, key: key, item: replacement)
            var changed = current; changed.items = changed.items.map { $0.id == id ? replacement : $0 }
            try save(changed, key: key, at: folder); library = changed
        } catch { try? FileManager.default.removeItem(at: target); throw error }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent(id.uuidString + ".aea"))
    }
    public func readNote(_ id: UUID) throws -> String {
        let (_, value, _) = try session()
        guard let item = value.items.first(where: { $0.id == id }), item.type == "public.plain-text", item.bytes <= 5_000_000 else { throw VaultError.unsupported }
        let url = try export(id); defer { try? FileManager.default.removeItem(at: url) }
        return try String(contentsOf: url, encoding: .utf8)
    }
    public func updateItem(_ id: UUID, name: String? = nil, favorite: Bool? = nil, trashed: Bool? = nil) throws {
        let (key, current, folder) = try session(); var changed = current
        guard let index = changed.items.firstIndex(where: { $0.id == id }) else { throw VaultError.missingFile }
        if let name { try VaultLibrary.validateName(name); changed.items[index].name = name }
        if let favorite { changed.items[index].favorite = favorite }
        if let trashed { changed.items[index].deleted = trashed ? Date() : nil }
        try save(changed, key: key, at: folder); library = changed
    }
    public func move(_ ids: Set<UUID>, to parent: UUID?) throws {
        let (key, current, folder) = try session(); var changed = current
        if let parent { guard changed.folders.contains(where: { $0.id == parent }) else { throw VaultError.damaged } }
        for index in changed.items.indices where ids.contains(changed.items[index].id) { changed.items[index].folder = parent }
        try save(changed, key: key, at: folder); library = changed
    }
    public func addFolder(name: String, parent: UUID?) throws {
        let (key, current, folder) = try session(); var changed = current
        try VaultLibrary.validateName(name); changed.folders.append(VaultFolder(name: name, parent: parent))
        try save(changed, key: key, at: folder); library = changed
    }
    public func renameFolder(_ id: UUID, name: String) throws {
        let (key, current, folder) = try session(); var changed = current; try VaultLibrary.validateName(name)
        guard let index = changed.folders.firstIndex(where: { $0.id == id }) else { throw VaultError.missingFile }
        changed.folders[index].name = name; try save(changed, key: key, at: folder); library = changed
    }
    public func preferences(sort: VaultOrder, grid: Bool) throws {
        let (key, current, folder) = try session(); var changed = current; changed.sort = sort; changed.grid = grid
        try save(changed, key: key, at: folder); library = changed
    }
    public func reorder(_ ids: [UUID]) throws {
        let (key, current, folder) = try session(); var changed = current
        guard Set(ids).count == ids.count, Set(ids).isSubset(of: Set(current.items.map(\.id))) else { throw VaultError.damaged }
        for (order, id) in ids.enumerated() { if let index = changed.items.firstIndex(where: { $0.id == id }) { changed.items[index].order = order } }
        changed.sort = .manual; try save(changed, key: key, at: folder); library = changed
    }
    public func permanentlyDelete(_ ids: Set<UUID>) throws {
        let (key, current, folder) = try session()
        guard current.items.filter({ ids.contains($0.id) }).allSatisfy({ $0.deleted != nil }) else { throw VaultError.damaged }
        var changed = current; changed.items.removeAll { ids.contains($0.id) }
        try save(changed, key: key, at: folder); library = changed
        for id in ids { try FileManager.default.removeItem(at: folder.appendingPathComponent(id.uuidString + ".aea")) }
    }
    public func export(_ id: UUID, into destination: URL? = nil) throws -> URL {
        let (key, current, folder) = try session()
        guard let item = current.items.first(where: { $0.id == id }) else { throw VaultError.missingFile }
        let outputFolder = (destination ?? temporary).appendingPathComponent(id.uuidString)
        try ProtectedFiles.directory(outputFolder)
        let output = outputFolder.appendingPathComponent(item.name)
        let partial = outputFolder.appendingPathComponent(".partial-" + UUID().uuidString)
        guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { throw VaultError.damaged }
        try ProtectedFiles.protect(partial)
        defer { try? FileManager.default.removeItem(at: partial) }
        let handle = try FileHandle(forWritingTo: partial); defer { try? handle.close() }
        try VaultCrypto.verify(folder.appendingPathComponent(id.uuidString + ".aea"), key: key, item: item, output: handle)
        try handle.synchronize(); try handle.close()
        if FileManager.default.fileExists(atPath: output.path) { try FileManager.default.removeItem(at: output) }
        try FileManager.default.moveItem(at: partial, to: output); return output
    }
    public func backup() throws -> URL {
        let (key, current, folder) = try session()
        for item in current.items { try VaultCrypto.verify(folder.appendingPathComponent(item.id.uuidString + ".aea"), key: key, item: item) }
        let names = ["header.json", "index.sealed"] + current.items.map { $0.id.uuidString + ".aea" }
        let output = temporary.appendingPathComponent("Vault-" + UUID().uuidString + ".vault7")
        do { try BackupContainer.write(directory: folder, names: names, to: output) }
        catch { try? FileManager.default.removeItem(at: output); throw error }
        var changed = current; changed.backupPrepared = Date(); try save(changed, key: key, at: folder); library = changed
        return output
    }
    public func backupRevision() throws -> String {
        let (_, _, folder) = try session()
        var hash = SHA256()
        hash.update(data: try ProtectedFiles.boundedRead(folder.appendingPathComponent("header.json"), max: 16_384))
        hash.update(data: try ProtectedFiles.boundedRead(folder.appendingPathComponent("index.sealed")))
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    public func automaticBackup(to output: URL) throws -> (UUID, String) {
        let (key, current, folder) = try session()
        let revision = try backupRevision()
        for item in current.items { try VaultCrypto.verify(folder.appendingPathComponent(item.id.uuidString + ".aea"), key: key, item: item) }
        do { try BackupContainer.write(directory: folder, names: ["header.json", "index.sealed"] + current.items.map { $0.id.uuidString + ".aea" }, to: output) }
        catch { try? FileManager.default.removeItem(at: output); throw error }
        return (current.id, revision)
    }
    public func restore(_ url: URL, credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        let names = try BackupContainer.read(url, into: staging)
        let header = try header(at: staging)
        guard recovery || (header.credentialKind ?? .passphrase) == kind else { throw VaultError.invalidCredential }
        let context = "Vault/" + (recovery ? "recovery" : "password") + "/v1/" + header.id.uuidString
        let key: SymmetricKey
        do { key = try VaultCrypto.unwrap(recovery ? header.recovery : header.password, credential: recovery ? credential : kind.material(credential), context: context) }
        catch { throw VaultError.invalidCredential }
        let value = try load(at: staging, header: header, key: key)
        guard names == Set(["header.json", "index.sealed"] + value.items.map { $0.id.uuidString + ".aea" }) else { throw VaultError.damaged }
        for item in value.items { try VaultCrypto.verify(staging.appendingPathComponent(item.id.uuidString + ".aea"), key: key, item: item) }
        let target = root.appendingPathComponent(value.id.uuidString)
        guard !FileManager.default.fileExists(atPath: target.path) else { throw VaultError.duplicateVault }
        try FileManager.default.moveItem(at: staging, to: target)
        self.key = key; library = value; directory = target; return value
    }
}
#endif
