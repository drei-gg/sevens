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
    public func snapshot() throws -> VaultLibrary { guard let library else { throw VaultError.Locked }; return library }
    private func session() throws -> (SymmetricKey, VaultLibrary, URL) {
        guard let key, let library, let directory else { throw VaultError.Locked }; return (key, library, directory)
    }
    private func folders() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]).filter {
            UUID(uuidString: $0.lastPathComponent) != nil && (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true && (try? $0.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true
        }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }
    private func header(at folder: URL) throws -> VaultHeader {
        try headerDecode(data: ProtectedFiles.boundedRead(folder.appendingPathComponent("header.json"), max: Int(VaultCrypto.format.headerMaxBytes)))
    }
    private func writeHeader(_ header: VaultHeader, to folder: URL) throws {
        try ProtectedFiles.write(headerEncode(header: header), to: folder.appendingPathComponent("header.json"))
    }
    private func load(at folder: URL, header: VaultHeader, key: SymmetricKey) throws -> VaultLibrary {
        let sealed = try ProtectedFiles.boundedRead(folder.appendingPathComponent("index.sealed"))
        let data = try VaultCrypto.open(sealed, key: key, context: indexContext(id: header.id))
        return try libraryDecode(data: data, headerId: header.id)
    }
    private func save(_ changed: VaultLibrary, key: SymmetricKey, at folder: URL) throws {
        let data = try libraryEncode(library: changed)
        try ProtectedFiles.write(VaultCrypto.seal(data, key: key, context: indexContext(id: changed.id)), to: folder.appendingPathComponent("index.sealed"))
    }
    private func blob(_ id: UUID, in folder: URL) -> URL { folder.appendingPathComponent(blobName(item: id)) }
    /// The envelope, derivation input and associated data to try. The password route is the core's
    /// (`unlockRoute`, which also skips a vault whose envelope is for another kind); a recovery key
    /// is never handed to the core, so its route is assembled here from the core's context string.
    private func route(_ header: VaultHeader, credential: String, recovery: Bool, kind: VaultCredentialKind) -> (envelope: KeyEnvelope, material: String, context: String)? {
        if recovery { return (envelope: header.recovery, material: credential, context: recoveryContext(id: header.id)) }
        guard let route = unlockRoute(header: header, credential: credential, kind: kind, recovery: false) else { return nil }
        return (envelope: route.envelope, material: route.material, context: route.context)
    }
    /// Whether `secret` as `kind` already opens the vault in `folder`. Throws if its header cannot be read.
    private func opens(_ folder: URL, secret: String, kind: VaultCredentialKind) throws -> Bool {
        guard let route = route(try header(at: folder), credential: secret, recovery: false, kind: kind) else { return false }
        return (try? VaultCrypto.unwrap(route.envelope, credential: route.material, context: route.context)) != nil
    }
    public func create(name: String, passphrase: String, kind: VaultCredentialKind = .passphrase) throws -> CreatedVault {
        try libraryValidateName(name: name); try kind.validate(passphrase)
        // A credential must select exactly one vault. Check without changing the existing session.
        for folder in try folders() { if try opens(folder, secret: passphrase, kind: kind) { throw VaultError.DuplicateVault } }
        let value = VaultLibrary(name: name); let key = SymmetricKey(size: .bits256)
        let recovery = try VaultCrypto.random(Int(VaultCrypto.format.recoveryKeyBytes)).base64EncodedString()
        let header = newVaultHeader(id: value.id,
            password: try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: passwordContext(id: value.id)),
            recovery: try VaultCrypto.wrap(key, credential: recovery, context: recoveryContext(id: value.id)), kind: kind)
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        try writeHeader(header, to: staging); try save(value, key: key, at: staging)
        let folder = root.appendingPathComponent(value.id.uuidString); try FileManager.default.moveItem(at: staging, to: folder)
        self.key = key; self.library = value; self.directory = folder
        return CreatedVault(library: value, recoveryKey: recovery)
    }
    public func unlock(credential: String, recovery: Bool = false, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        lock()
        var unreadable = false
        for folder in try folders() {
            guard let header = try? header(at: folder) else { unreadable = true; continue }
            guard let route = route(header, credential: credential, recovery: recovery, kind: kind) else { continue }
            guard let key = try? VaultCrypto.unwrap(route.envelope, credential: route.material, context: route.context) else { continue }
            let value = try load(at: folder, header: header, key: key)
            self.key = key; self.library = value; self.directory = folder; return value
        }
        if unreadable { throw VaultError.Damaged }; throw VaultError.InvalidCredential
    }
    public func biometricMaterial() throws -> (UUID, Data) {
        let (key, value, _) = try session(); return (value.id, key.withUnsafeBytes { Data($0) })
    }
    public func unlock(id: UUID, biometricKey: Data) throws -> VaultLibrary {
        lock(); guard biometricKey.count == Int(VaultCrypto.format.keyBytes) else { throw VaultError.InvalidCredential }
        let folder = root.appendingPathComponent(id.uuidString), key = SymmetricKey(data: biometricKey)
        let value = try load(at: folder, header: header(at: folder), key: key)
        self.key = key; library = value; directory = folder; return value
    }
    public func changePassphrase(_ passphrase: String, kind: VaultCredentialKind = .passphrase) throws {
        try kind.validate(passphrase)
        let (key, value, folder) = try session()
        for other in try folders() where other != folder { if try opens(other, secret: passphrase, kind: kind) { throw VaultError.DuplicateVault } }
        var header = try header(at: folder)
        header.credentialKind = kind
        header.password = try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: passwordContext(id: value.id))
        try writeHeader(header, to: folder)
    }
    public func replaceRecoveryKey() throws -> String {
        let (key, value, folder) = try session()
        let recovery = try VaultCrypto.random(Int(VaultCrypto.format.recoveryKeyBytes)).base64EncodedString()
        var header = try header(at: folder)
        header.recovery = try VaultCrypto.wrap(key, credential: recovery, context: recoveryContext(id: value.id))
        try writeHeader(header, to: folder)
        return recovery
    }
    public func importFile(_ url: URL, name: String, type: String, folder parent: UUID? = nil) throws -> VaultItem {
        let (key, current, folder) = try session(); try libraryCheckImport(library: current, name: name, folder: parent)
        let resource = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard resource.isRegularFile == true, resource.isSymbolicLink != true else { throw VaultError.Unsupported }
        let (bytes, digest) = try VaultCrypto.digest(url)
        let item = libraryImportItem(library: current, name: name, contentType: type, bytes: bytes, digest: digest, folder: parent)
        let target = blob(item.id, in: folder)
        let staging = folder.appendingPathComponent(".import-" + item.id.uuidString)
        defer { try? FileManager.default.removeItem(at: staging) }
        try VaultCrypto.encrypt(url, to: staging, key: VaultCrypto.fileKey(key, id: item.id)); try ProtectedFiles.protect(staging)
        // Verify the actual encrypted copy before committing or offering source removal.
        try VaultCrypto.verify(staging, key: key, item: item)
        try FileManager.default.moveItem(at: staging, to: target)
        let changed = libraryAppendItem(library: current, item: item)
        do { try save(changed, key: key, at: folder); library = changed }
        catch { try? FileManager.default.removeItem(at: target); throw error }
        return item
    }
    // Note text is vault content: only its size is checked here, against the core's limit, and the
    // core's note rules are given an empty text, so the plaintext never crosses into the core.
    public func addNote(name: String, text: String, folder: UUID?) throws {
        guard text.utf8.count <= Int(VaultCrypto.format.noteMaxBytes) else { throw VaultError.NoSpace }
        let stored = try noteName(name: name, text: "")
        let source = temporary.appendingPathComponent(UUID().uuidString + ".txt")
        defer { try? FileManager.default.removeItem(at: source) }
        try ProtectedFiles.write(Data(text.utf8), to: source)
        _ = try importFile(source, name: stored, type: "public.plain-text", folder: folder)
    }
    public func editNote(_ id: UUID, text: String) throws {
        let (key, current, folder) = try session()
        let old = try libraryCheckNoteEdit(library: current, id: id, text: "")
        guard text.utf8.count <= Int(VaultCrypto.format.noteMaxBytes) else { throw VaultError.Unsupported }
        let source = temporary.appendingPathComponent(UUID().uuidString + ".txt")
        defer { try? FileManager.default.removeItem(at: source) }
        try ProtectedFiles.write(Data(text.utf8), to: source)
        let (bytes, digest) = try VaultCrypto.digest(source)
        let replacement = noteReplacement(old: old, bytes: bytes, digest: digest)
        let target = blob(replacement.id, in: folder)
        try VaultCrypto.encrypt(source, to: target, key: VaultCrypto.fileKey(key, id: replacement.id)); try ProtectedFiles.protect(target)
        do {
            try VaultCrypto.verify(target, key: key, item: replacement)
            let changed = libraryReplaceItem(library: current, id: id, replacement: replacement)
            try save(changed, key: key, at: folder); library = changed
        } catch { try? FileManager.default.removeItem(at: target); throw error }
        try? FileManager.default.removeItem(at: blob(id, in: folder))
    }
    public func readNote(_ id: UUID) throws -> String {
        let (_, value, _) = try session()
        _ = try libraryCheckNoteRead(library: value, id: id)
        let url = try export(id); defer { try? FileManager.default.removeItem(at: url) }
        return try String(contentsOf: url, encoding: .utf8)
    }
    public func updateItem(_ id: UUID, name: String? = nil, favorite: Bool? = nil, trashed: Bool? = nil) throws {
        let (key, current, folder) = try session()
        let changed = try libraryUpdateItem(library: current, id: id, name: name, favorite: favorite, trashed: trashed, now: Date())
        try save(changed, key: key, at: folder); library = changed
    }
    public func move(_ ids: Set<UUID>, to parent: UUID?) throws {
        let (key, current, folder) = try session()
        let changed = try libraryMove(library: current, ids: Array(ids), parent: parent)
        try save(changed, key: key, at: folder); library = changed
    }
    public func addFolder(name: String, parent: UUID?) throws {
        let (key, current, folder) = try session()
        let changed = try libraryAddFolder(library: current, name: name, parent: parent)
        try save(changed, key: key, at: folder); library = changed
    }
    public func renameFolder(_ id: UUID, name: String) throws {
        let (key, current, folder) = try session()
        let changed = try libraryRenameFolder(library: current, id: id, name: name)
        try save(changed, key: key, at: folder); library = changed
    }
    public func preferences(sort: VaultOrder, grid: Bool) throws {
        let (key, current, folder) = try session()
        let changed = librarySetPreferences(library: current, sort: sort, grid: grid)
        try save(changed, key: key, at: folder); library = changed
    }
    public func reorder(_ ids: [UUID]) throws {
        let (key, current, folder) = try session()
        let changed = try libraryReorder(library: current, ids: ids)
        try save(changed, key: key, at: folder); library = changed
    }
    public func permanentlyDelete(_ ids: Set<UUID>) throws {
        let (key, current, folder) = try session()
        let changed = try libraryPermanentlyDelete(library: current, ids: Array(ids))
        try save(changed, key: key, at: folder); library = changed
        for id in ids { try FileManager.default.removeItem(at: blob(id, in: folder)) }
    }
    public func export(_ id: UUID, into destination: URL? = nil) throws -> URL {
        let (key, current, folder) = try session()
        guard let item = current.items.first(where: { $0.id == id }) else { throw VaultError.MissingFile }
        let outputFolder = (destination ?? temporary).appendingPathComponent(id.uuidString)
        try ProtectedFiles.directory(outputFolder)
        let output = outputFolder.appendingPathComponent(item.name)
        let partial = outputFolder.appendingPathComponent(".partial-" + UUID().uuidString)
        guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { throw VaultError.Damaged }
        try ProtectedFiles.protect(partial)
        defer { try? FileManager.default.removeItem(at: partial) }
        let handle = try FileHandle(forWritingTo: partial); defer { try? handle.close() }
        try VaultCrypto.verify(blob(id, in: folder), key: key, item: item, output: handle)
        try handle.synchronize(); try handle.close()
        if FileManager.default.fileExists(atPath: output.path) { try FileManager.default.removeItem(at: output) }
        try FileManager.default.moveItem(at: partial, to: output); return output
    }
    public func backup() throws -> URL {
        let (key, current, folder) = try session()
        for item in current.items { try VaultCrypto.verify(blob(item.id, in: folder), key: key, item: item) }
        let output = temporary.appendingPathComponent(backupFileName(id: UUID()))
        do { try BackupContainer.write(directory: folder, names: backupEntryNames(library: current), to: output) }
        catch { try? FileManager.default.removeItem(at: output); throw error }
        let changed = libraryMarkBackupPrepared(library: current, now: Date()); try save(changed, key: key, at: folder); library = changed
        return output
    }
    public func backupRevision() throws -> String {
        let (_, _, folder) = try session()
        var hash = SHA256()
        hash.update(data: try ProtectedFiles.boundedRead(folder.appendingPathComponent("header.json"), max: Int(VaultCrypto.format.headerMaxBytes)))
        hash.update(data: try ProtectedFiles.boundedRead(folder.appendingPathComponent("index.sealed")))
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    public func automaticBackup(to output: URL) throws -> (UUID, String) {
        let (key, current, folder) = try session()
        let revision = try backupRevision()
        for item in current.items { try VaultCrypto.verify(blob(item.id, in: folder), key: key, item: item) }
        do { try BackupContainer.write(directory: folder, names: backupEntryNames(library: current), to: output) }
        catch { try? FileManager.default.removeItem(at: output); throw error }
        return (current.id, revision)
    }
    public func restore(_ url: URL, credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        let names = try BackupContainer.read(url, into: staging)
        let header = try header(at: staging)
        guard let route = route(header, credential: credential, recovery: recovery, kind: kind) else { throw VaultError.InvalidCredential }
        let key: SymmetricKey
        do { key = try VaultCrypto.unwrap(route.envelope, credential: route.material, context: route.context) }
        catch { throw VaultError.InvalidCredential }
        let value = try load(at: staging, header: header, key: key)
        try backupNamesMatch(names: Array(names), library: value)
        for item in value.items { try VaultCrypto.verify(blob(item.id, in: staging), key: key, item: item) }
        let target = root.appendingPathComponent(value.id.uuidString)
        guard !FileManager.default.fileExists(atPath: target.path) else { throw VaultError.DuplicateVault }
        try FileManager.default.moveItem(at: staging, to: target)
        self.key = key; library = value; directory = target; return value
    }
}
#endif
