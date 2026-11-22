#if canImport(CryptoKit)
import Foundation
import CryptoKit
import VaultCore

public struct CreatedVault: Sendable { public let library: VaultLibrary; public let recoveryKey: String }

/// The open vault's keys. Version 2 (the cross-platform format: the core's `age` envelopes,
/// authenticated index and originals) is what new vaults use. Version 1 (PBKDF2 + AES-GCM envelopes,
/// Apple Encrypted Archive originals) stays readable and writable, so a vault opened by its recovery
/// key or Face ID keeps working; the first unlock with its PIN, pattern or passphrase migrates it.
enum VaultKeys: @unchecked Sendable {
    case v1(SymmetricKey)
    case v2(VaultIdentity)
    var version: Int { if case .v1 = self { return 1 }; return 2 }
    func wipe() { if case .v2(let identity) = self { identity.wipe() } }
}

public actor VaultRepository {
    public let root: URL
    public let temporary: URL
    private var keys: VaultKeys?
    private var library: VaultLibrary?
    private var directory: URL?
    private var migratedRecoveryKey: String?
    private var sessionRevision: UInt64 = 0
    public init(root: URL, temporary: URL) { self.root = root; self.temporary = temporary }

    public func prepare() throws {
        try ProtectedFiles.directory(root)
        try clearTemporary()
        // Staging is never treated as a committed vault. Failed work does not overwrite older data.
        for url in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            let name = url.lastPathComponent
            if name.hasPrefix(".stage-") || name.hasPrefix(".migrate-") { try? FileManager.default.removeItem(at: url) }
            // A migration interrupted between its two renames: keep the new vault if it was
            // published, otherwise put the original back.
            if name.hasPrefix(".old-") {
                let published = root.appendingPathComponent(String(name.dropFirst(5)))
                if FileManager.default.fileExists(atPath: published.path) { try? FileManager.default.removeItem(at: url) }
                else { try? FileManager.default.moveItem(at: url, to: published) }
            }
        }
    }
    public func clearTemporary() throws {
        if FileManager.default.fileExists(atPath: temporary.path) { try FileManager.default.removeItem(at: temporary) }
        try ProtectedFiles.directory(temporary)
    }
    public func lock() { sessionRevision &+= 1; keys?.wipe(); keys = nil; library = nil; directory = nil; migratedRecoveryKey = nil; try? clearTemporary() }
    public func snapshot() throws -> VaultLibrary { guard let library else { throw VaultError.Locked }; return library }
    /// The open vault's format, 1 or 2.
    public func formatVersion() throws -> Int { try session().0.version }
    /// The recovery key a migration just issued, handed out once. A version 1 recovery key cannot be
    /// carried over, so the person must keep this one instead. The migrated index records it as
    /// unconfirmed, so losing it before `confirmRecoveryKey` leads to a replacement prompt.
    public func takeMigratedRecoveryKey() -> String? { defer { migratedRecoveryKey = nil }; return migratedRecoveryKey }
    /// The IDs of the vaults on this device (their directory names), for cleaning up per-vault
    /// state kept elsewhere, such as a biometric Keychain item.
    public func vaultIDs() throws -> [UUID] { try folders().compactMap { UUID(uuidString: $0.lastPathComponent) } }
    private func session() throws -> (VaultKeys, VaultLibrary, URL) {
        guard let keys, let library, let directory else { throw VaultError.Locked }; return (keys, library, directory)
    }
    private func open(_ keys: VaultKeys, _ value: VaultLibrary, _ folder: URL) { sessionRevision &+= 1; self.keys = keys; library = value; directory = folder }
    private func folders() throws -> [URL] {
        try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]).filter {
            UUID(uuidString: $0.lastPathComponent) != nil && (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true && (try? $0.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true
        }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    // MARK: headers

    private enum Header {
        case v1(VaultHeader), v2(VaultHeaderV2)
        var id: UUID { switch self { case .v1(let h): return h.id; case .v2(let h): return h.id } }
    }
    private func headerData(at folder: URL) throws -> Data {
        try ProtectedFiles.boundedRead(folder.appendingPathComponent("header.json"), max: Int(VaultCrypto.format.headerMaxBytes))
    }
    private func header(at folder: URL) throws -> Header {
        let data = try headerData(at: folder)
        return try headerFormatVersion(data: data) == 1 ? .v1(headerDecode(data: data)) : .v2(headerV2Decode(data: data))
    }
    private func headerV1(at folder: URL) throws -> VaultHeader {
        guard case .v1(let header) = try header(at: folder) else { throw VaultError.Damaged }; return header
    }
    private func headerV2(at folder: URL) throws -> VaultHeaderV2 {
        guard case .v2(let header) = try header(at: folder) else { throw VaultError.Damaged }; return header
    }
    private func writeHeader(_ header: VaultHeader, to folder: URL) throws {
        try ProtectedFiles.write(headerEncode(header: header), to: folder.appendingPathComponent("header.json"))
    }
    private func writeHeader(_ header: VaultHeaderV2, to folder: URL) throws {
        try ProtectedFiles.write(headerV2Encode(header: header), to: folder.appendingPathComponent("header.json"))
    }
    /// The version 1 envelope, derivation input and associated data to try. The password route is
    /// the core's (`unlockRoute`, which also skips a vault whose envelope is for another kind).
    private func route(_ header: VaultHeader, credential: String, recovery: Bool, kind: VaultCredentialKind) -> (envelope: KeyEnvelope, material: String, context: String)? {
        if recovery { return (envelope: header.recovery, material: credential, context: recoveryContext(id: header.id)) }
        guard let route = unlockRoute(header: header, credential: credential, kind: kind, recovery: false) else { return nil }
        return (envelope: route.envelope, material: route.material, context: route.context)
    }
    /// The keys `credential` opens, nil when it doesn't (another kind, a wrong secret). Throws when
    /// the header is damaged (an envelope from another vault).
    private func keys(for header: Header, credential: String, recovery: Bool, kind: VaultCredentialKind) throws -> VaultKeys? {
        switch header {
        case .v1(let header):
            guard let route = route(header, credential: credential, recovery: recovery, kind: kind),
                  let key = try? VaultCrypto.unwrap(route.envelope, credential: route.material, context: route.context) else { return nil }
            return .v1(key)
        case .v2(let header):
            if !recovery && header.credentialKind != kind { return nil }
            do { return .v2(try headerV2Open(header: header, credential: credential, kind: kind, recovery: recovery)) }
            catch VaultError.InvalidCredential { return nil }
        }
    }
    /// Whether `secret` as `kind` already opens the vault in `folder`. Throws if its header cannot be read.
    private func opens(_ folder: URL, secret: String, kind: VaultCredentialKind) throws -> Bool {
        switch try header(at: folder) {
        case .v2(let header): return headerV2Opens(header: header, credential: secret, kind: kind)
        case .v1(let header):
            guard let route = route(header, credential: secret, recovery: false, kind: kind) else { return false }
            return (try? VaultCrypto.unwrap(route.envelope, credential: route.material, context: route.context)) != nil
        }
    }

    // MARK: index and originals, per format

    private func indexURL(_ keys: VaultKeys, in folder: URL) -> URL {
        folder.appendingPathComponent(keys.version == 1 ? "index.sealed" : "index.age")
    }
    private func load(at folder: URL, keys: VaultKeys, id: UUID) throws -> VaultLibrary {
        let sealed = try ProtectedFiles.boundedRead(indexURL(keys, in: folder))
        switch keys {
        case .v1(let key): return try libraryDecode(data: VaultCrypto.open(sealed, key: key, context: indexContext(id: id)), headerId: id)
        case .v2(let identity): return try indexOpen(data: sealed, identity: identity, headerId: id)
        }
    }
    private func save(_ changed: VaultLibrary, keys: VaultKeys, at folder: URL) throws {
        let sealed: Data
        switch keys {
        case .v1(let key): sealed = try VaultCrypto.seal(libraryEncode(library: changed), key: key, context: indexContext(id: changed.id))
        case .v2(let identity): sealed = try indexSeal(library: changed, identity: identity)
        }
        try ProtectedFiles.write(sealed, to: indexURL(keys, in: folder))
    }
    private func blob(_ id: UUID, keys: VaultKeys, in folder: URL) -> URL {
        folder.appendingPathComponent(keys.version == 1 ? blobName(item: id) : blobNameV2(item: id))
    }
    /// Encrypt `source` as `item`'s original at `destination` (which must not exist), then verify the
    /// written copy decrypts to the measured bytes before anything is committed.
    private func encrypt(_ source: URL, item: VaultItem, keys: VaultKeys, to destination: URL) throws {
        switch keys {
        case .v1(let key):
            try VaultCrypto.encrypt(source, to: destination, key: VaultCrypto.fileKey(key, id: item.id))
        case .v2(let identity):
            let measured = try fileEncrypt(recipient: identity.recipient(), source: source.path, destination: destination.path)
            // The source changed between measuring and encrypting: never commit a mismatch.
            guard measured.bytes == item.bytes, measured.digest == item.digest else {
                try? FileManager.default.removeItem(at: destination); throw VaultError.Damaged
            }
        }
        try ProtectedFiles.protect(destination)
        try verify(destination, keys: keys, item: item)
    }
    private func verify(_ source: URL, keys: VaultKeys, item: VaultItem) throws {
        switch keys {
        case .v1(let key): try VaultCrypto.verify(source, key: key, item: item)
        case .v2(let identity): try fileVerify(identity: identity, path: source.path, bytes: item.bytes, digest: item.digest)
        }
    }
    /// Decrypt `item`'s original to a new file at `destination`, verified against its size and digest.
    private func decrypt(_ source: URL, keys: VaultKeys, item: VaultItem, to destination: URL) throws {
        switch keys {
        case .v1(let key):
            guard FileManager.default.createFile(atPath: destination.path, contents: nil) else { throw VaultError.Damaged }
            try ProtectedFiles.protect(destination)
            let handle = try FileHandle(forWritingTo: destination); defer { try? handle.close() }
            try VaultCrypto.verify(source, key: key, item: item, output: handle)
            try handle.synchronize()
        case .v2(let identity):
            try fileDecrypt(identity: identity, path: source.path, destination: destination.path, bytes: item.bytes, digest: item.digest)
            try ProtectedFiles.protect(destination)
        }
    }

    // MARK: vaults

    public func create(name: String, passphrase: String, kind: VaultCredentialKind = .passphrase) throws -> CreatedVault {
        try libraryValidateName(name: name); try kind.validate(passphrase)
        // A credential must select exactly one vault. Check without changing the existing session.
        for folder in try folders() { if try opens(folder, secret: passphrase, kind: kind) { throw VaultError.DuplicateVault } }
        // The recovery key is shown next; until the person confirms saving it, the index says so.
        let value = librarySetRecoveryUnconfirmed(library: VaultLibrary(name: name), unconfirmed: true)
        let identity = vaultIdentityGenerate(), recovery = generateRecoveryKey()
        let header = try headerV2New(id: value.id, identity: identity, kind: kind, credential: passphrase, recoveryKey: recovery)
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        try writeHeader(header, to: staging); try save(value, keys: .v2(identity), at: staging)
        let folder = root.appendingPathComponent(value.id.uuidString); try FileManager.default.moveItem(at: staging, to: folder)
        keys?.wipe(); open(.v2(identity), value, folder)
        return CreatedVault(library: value, recoveryKey: recovery)
    }
    public func unlock(credential: String, recovery: Bool = false, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        lock()
        var unreadable = false
        for folder in try folders() {
            guard let header = try? header(at: folder) else { unreadable = true; continue }
            let found: VaultKeys?
            do { found = try keys(for: header, credential: credential, recovery: recovery, kind: kind) }
            catch { unreadable = true; continue }
            guard let keys = found else { continue }
            let value: VaultLibrary
            do { value = try confirmedByRecovery(load(at: folder, keys: keys, id: header.id), recovery: recovery, keys: keys, at: folder) }
            catch { keys.wipe(); throw error }
            open(keys, value, folder)
            if case .v1(let old) = header, !recovery { migrate(header: old, credential: credential) }
            return library ?? value
        }
        if unreadable { throw VaultError.Damaged }; throw VaultError.InvalidCredential
    }
    /// A vault whose recovery key was never confirmed as saved, just opened by that very key: the
    /// person evidently has it, so the reminder is cleared.
    private func confirmedByRecovery(_ value: VaultLibrary, recovery: Bool, keys: VaultKeys, at folder: URL) throws -> VaultLibrary {
        guard recovery, value.recoveryUnconfirmed else { return value }
        let confirmed = librarySetRecoveryUnconfirmed(library: value, unconfirmed: false)
        try save(confirmed, keys: keys, at: folder); return confirmed
    }
    /// The open vault's key material for Face ID / Touch ID custody: the version 2 identity's
    /// export, or the version 1 key.
    public func biometricMaterial() throws -> (UUID, Data) {
        let (keys, value, _) = try session()
        switch keys {
        case .v1(let key): return (value.id, key.withUnsafeBytes { Data($0) })
        case .v2(let identity): return (value.id, try identity.exportForCustody())
        }
    }
    public func unlock(id: UUID, biometricKey: Data) throws -> VaultLibrary {
        lock()
        let folder = root.appendingPathComponent(id.uuidString)
        let keys: VaultKeys
        switch try header(at: folder) {
        case .v1:
            guard biometricKey.count == Int(VaultCrypto.format.keyBytes) else { throw VaultError.InvalidCredential }
            keys = .v1(SymmetricKey(data: biometricKey))
        case .v2(let header):
            keys = .v2(try vaultIdentityFromCustody(secret: biometricKey, header: header))
        }
        let value: VaultLibrary
        do { value = try load(at: folder, keys: keys, id: id) } catch { keys.wipe(); throw error }
        open(keys, value, folder); return value
    }
    public func changePassphrase(_ passphrase: String, kind: VaultCredentialKind = .passphrase) throws {
        try kind.validate(passphrase)
        let (keys, value, folder) = try session()
        for other in try folders() where other != folder { if try opens(other, secret: passphrase, kind: kind) { throw VaultError.DuplicateVault } }
        switch keys {
        case .v1(let key):
            var header = try headerV1(at: folder)
            header.credentialKind = kind
            header.password = try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: passwordContext(id: value.id))
            try writeHeader(header, to: folder)
        case .v2(let identity):
            try writeHeader(headerV2RewrapPassword(header: headerV2(at: folder), identity: identity, kind: kind, credential: passphrase), to: folder)
        }
        // The required new secret (after a recovery-key restore) is now set.
        if value.secretChangeRequired {
            let cleared = librarySetSecretChangeRequired(library: value, required: false)
            try save(cleared, keys: keys, at: folder); library = cleared
        }
    }
    /// A new recovery key to show once. The index records it as unconfirmed first, so an interruption
    /// or a lock before the person saves it leads to another replacement prompt, never to a key
    /// nobody saw.
    public func replaceRecoveryKey() throws -> String {
        let (keys, current, folder) = try session()
        let value = librarySetRecoveryUnconfirmed(library: current, unconfirmed: true)
        try save(value, keys: keys, at: folder); library = value
        switch keys {
        case .v1(let key):
            let recovery = try VaultCrypto.random(Int(VaultCrypto.format.recoveryKeyBytes)).base64EncodedString()
            var header = try headerV1(at: folder)
            header.recovery = try VaultCrypto.wrap(key, credential: recovery, context: recoveryContext(id: value.id))
            try writeHeader(header, to: folder)
            return recovery
        case .v2(let identity):
            let recovery = generateRecoveryKey()
            try writeHeader(headerV2RewrapRecovery(header: headerV2(at: folder), identity: identity, recoveryKey: recovery), to: folder)
            return recovery
        }
    }

    /// The person confirmed saving the current recovery key.
    public func confirmRecoveryKey() throws {
        guard try snapshot().recoveryUnconfirmed else { return }
        try change { librarySetRecoveryUnconfirmed(library: $0, unconfirmed: false) }
    }

    // MARK: migration, version 1 → 2

    /// Re-encrypt the open version 1 vault as version 2 beside it, verify it, then swap the folders.
    /// `credential` is the PIN, pattern or passphrase that just opened it. A version 1 recovery key
    /// cannot be carried over, so a new one is issued (`takeMigratedRecoveryKey`). Any failure (no
    /// space, an unreadable original) leaves the version 1 vault untouched and open; the next unlock
    /// tries again.
    private func migrate(header old: VaultHeader, credential: String) {
        guard case .v1(let key)? = keys, let opened = library, let folder = directory else { return }
        let current = librarySetRecoveryUnconfirmed(library: opened, unconfirmed: true)
        let staging = root.appendingPathComponent(".migrate-" + current.id.uuidString)
        let retired = root.appendingPathComponent(".old-" + current.id.uuidString)
        let identity = vaultIdentityGenerate(), recovery = generateRecoveryKey()
        let v1 = VaultKeys.v1(key), v2 = VaultKeys.v2(identity)
        do {
            try? FileManager.default.removeItem(at: staging)
            try ProtectedFiles.directory(staging)
            let header = try headerV2New(id: current.id, identity: identity, kind: headerCredentialKind(header: old), credential: credential, recoveryKey: recovery)
            for item in current.items {
                // One original's plaintext at a time, in the cleared-on-lock temporary folder.
                let plain = temporary.appendingPathComponent(".migrate-" + UUID().uuidString)
                defer { try? FileManager.default.removeItem(at: plain) }
                try decrypt(blob(item.id, keys: v1, in: folder), keys: v1, item: item, to: plain)
                try encrypt(plain, item: item, keys: v2, to: blob(item.id, keys: v2, in: staging))
            }
            try writeHeader(header, to: staging)
            try save(current, keys: v2, at: staging)
            _ = try load(at: staging, keys: v2, id: current.id)
            guard try headerV2Opens(header: headerV2(at: staging), credential: credential, kind: headerCredentialKind(header: old)) else { throw VaultError.Damaged }
            // Publish with two renames; `prepare` completes either half after a crash.
            try FileManager.default.moveItem(at: folder, to: retired)
            do { try FileManager.default.moveItem(at: staging, to: folder) }
            catch { try? FileManager.default.moveItem(at: retired, to: folder); throw error }
            try? FileManager.default.removeItem(at: retired)
            open(v2, current, folder)
            migratedRecoveryKey = recovery
        } catch {
            identity.wipe()
            try? FileManager.default.removeItem(at: staging)
        }
    }

    // MARK: items

    public func importFile(_ url: URL, name: String, type: String, folder parent: UUID? = nil) throws -> VaultItem {
        let (keys, current, folder) = try session(); try libraryCheckImport(library: current, name: name, folder: parent)
        let resource = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard resource.isRegularFile == true, resource.isSymbolicLink != true else { throw VaultError.Unsupported }
        let (bytes, digest) = try VaultCrypto.digest(url)
        let item = libraryImportItem(library: current, name: name, contentType: type, bytes: bytes, digest: digest, folder: parent)
        // Verify the actual encrypted copy before committing or offering source removal.
        try commitImport(item, keys: keys, library: current, folder: folder) { try encrypt(url, item: item, keys: keys, to: $0) }
        return item
    }
    /// Encrypt (with `write`, to a staging name), publish the original under its blob name, then
    /// commit the index; a failed index write removes the published original again.
    private func commitImport(_ item: VaultItem, keys: VaultKeys, library current: VaultLibrary, folder: URL, write: (URL) throws -> Void) throws {
        let target = blob(item.id, keys: keys, in: folder)
        let staging = folder.appendingPathComponent(".import-" + item.id.uuidString)
        defer { try? FileManager.default.removeItem(at: staging) }
        try write(staging)
        try FileManager.default.moveItem(at: staging, to: target)
        let changed = libraryAppendItem(library: current, item: item)
        do { try save(changed, keys: keys, at: folder); library = changed }
        catch { try? FileManager.default.removeItem(at: target); throw error }
    }
    /// Encrypt note text from memory as `item`'s original (no plaintext file), then verify it.
    private func encrypt(_ data: Data, item: VaultItem, keys: VaultKeys, to destination: URL) throws {
        switch keys {
        case .v1(let key):
            try VaultCrypto.encrypt(data, to: destination, key: VaultCrypto.fileKey(key, id: item.id))
        case .v2(let identity):
            let measured = try dataEncrypt(recipient: identity.recipient(), data: data, destination: destination.path)
            guard measured.bytes == item.bytes, measured.digest == item.digest else {
                try? FileManager.default.removeItem(at: destination); throw VaultError.Damaged
            }
        }
        try ProtectedFiles.protect(destination)
        try verify(destination, keys: keys, item: item)
    }
    private static func measure(_ data: Data) -> (Int64, String) {
        (Int64(data.count), SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined())
    }
    // Note text is vault content: only its size is checked here, against the core's limit, and the
    // core's note rules are given an empty text. It is encrypted from memory (version 2 through the
    // core's `dataEncrypt`, which needs only the public recipient); no plaintext file is written.
    public func addNote(name: String, text: String, folder parent: UUID?) throws {
        var data = Data(text.utf8); defer { data.resetBytes(in: 0..<data.count) }
        guard data.count <= Int(VaultCrypto.format.noteMaxBytes) else { throw VaultError.NoSpace }
        let stored = try noteName(name: name, text: "")
        let (keys, current, folder) = try session(); try libraryCheckImport(library: current, name: stored, folder: parent)
        let (bytes, digest) = Self.measure(data)
        let item = libraryImportItem(library: current, name: stored, contentType: "public.plain-text", bytes: bytes, digest: digest, folder: parent)
        try commitImport(item, keys: keys, library: current, folder: folder) { try encrypt(data, item: item, keys: keys, to: $0) }
    }
    public func editNote(_ id: UUID, text: String) throws {
        let (keys, current, folder) = try session()
        let old = try libraryCheckNoteEdit(library: current, id: id, text: "")
        var data = Data(text.utf8); defer { data.resetBytes(in: 0..<data.count) }
        guard data.count <= Int(VaultCrypto.format.noteMaxBytes) else { throw VaultError.Unsupported }
        let (bytes, digest) = Self.measure(data)
        let replacement = noteReplacement(old: old, bytes: bytes, digest: digest)
        let target = blob(replacement.id, keys: keys, in: folder)
        do {
            try encrypt(data, item: replacement, keys: keys, to: target)
            let changed = libraryReplaceItem(library: current, id: id, replacement: replacement)
            try save(changed, keys: keys, at: folder); library = changed
        } catch { try? FileManager.default.removeItem(at: target); throw error }
        try? FileManager.default.removeItem(at: blob(id, keys: keys, in: folder))
    }
    public func readNote(_ id: UUID) throws -> String {
        var data = try noteData(id); defer { data.resetBytes(in: 0..<data.count) }
        guard let text = String(data: data, encoding: .utf8) else { throw VaultError.Damaged }
        return text
    }
    /// Verified note plaintext stays in memory, including for legacy vaults.
    private func noteData(_ id: UUID) throws -> Data {
        let (keys, value, directory) = try session()
        let item = try libraryCheckNoteRead(library: value, id: id)
        let source = blob(id, keys: keys, in: directory)
        switch keys {
        case .v2(let identity): return try dataDecrypt(identity: identity, path: source.path, bytes: item.bytes, digest: item.digest)
        case .v1(let key):
            var data = Data()
            do { try VaultCrypto.verify(source, key: key, item: item, consume: { data.append($0) }); return data }
            catch { data.resetBytes(in: 0..<data.count); throw error }
        }
    }
    public func searchNotes(query: String, trash: Bool = false, favorites: Bool = false) async throws -> NoteSearchResult {
        let current = try snapshot(); let revision = sessionRevision
        let plan = noteSearchPlan(items: current.items, query: query, trash: trash, favorites: favorites)
        var matches: [UUID] = []; var searched: UInt32 = 0; var unavailable: UInt32 = 0
        for id in plan.ids {
            await Task.yield(); try Task.checkCancellation()
            guard sessionRevision == revision, library == current else { throw VaultError.Locked }
            do {
                var data = try noteData(id); defer { data.resetBytes(in: 0..<data.count) }
                if try noteSearchMatches(data: data, query: query) { matches.append(id) }
                searched += 1
            } catch VaultError.Locked { throw VaultError.Locked }
            catch { unavailable += 1 }
        }
        try Task.checkCancellation()
        guard sessionRevision == revision, library == current else { throw VaultError.Locked }
        return NoteSearchResult(ids: matches, searched: searched, unavailable: unavailable, omitted: plan.omitted, issue: plan.issue)
    }
    private func change(_ edit: (VaultLibrary) throws -> VaultLibrary) throws {
        let (keys, current, folder) = try session()
        let changed = try edit(current)
        try save(changed, keys: keys, at: folder); library = changed
    }
    public func updateItem(_ id: UUID, name: String? = nil, favorite: Bool? = nil, trashed: Bool? = nil) throws {
        try change { try libraryUpdateItem(library: $0, id: id, name: name, favorite: favorite, trashed: trashed, now: Date()) }
    }
    public func move(_ ids: Set<UUID>, to parent: UUID?) throws { try change { try libraryMove(library: $0, ids: Array(ids), parent: parent) } }
    public func addFolder(name: String, parent: UUID?) throws { try change { try libraryAddFolder(library: $0, name: name, parent: parent) } }
    public func renameFolder(_ id: UUID, name: String) throws { try change { try libraryRenameFolder(library: $0, id: id, name: name) } }
    public func preferences(sort: VaultOrder, grid: Bool) throws { try change { librarySetPreferences(library: $0, sort: sort, grid: grid) } }
    public func reorder(_ ids: [UUID]) throws { try change { try libraryReorder(library: $0, ids: ids) } }
    public func permanentlyDelete(_ ids: Set<UUID>) throws {
        let (keys, _, folder) = try session()
        try change { try libraryPermanentlyDelete(library: $0, ids: Array(ids)) }
        for id in ids { try FileManager.default.removeItem(at: blob(id, keys: keys, in: folder)) }
    }
    public func export(_ id: UUID, into destination: URL? = nil) throws -> URL {
        let (keys, current, folder) = try session()
        guard let item = current.items.first(where: { $0.id == id }) else { throw VaultError.MissingFile }
        let outputFolder = (destination ?? temporary).appendingPathComponent(id.uuidString)
        try ProtectedFiles.directory(outputFolder)
        let output = outputFolder.appendingPathComponent(item.name)
        let partial = outputFolder.appendingPathComponent(".partial-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: partial) }
        try decrypt(blob(id, keys: keys, in: folder), keys: keys, item: item, to: partial)
        if FileManager.default.fileExists(atPath: output.path) { try FileManager.default.removeItem(at: output) }
        try FileManager.default.moveItem(at: partial, to: output); return output
    }

    // MARK: backups

    private func writeBackup(keys: VaultKeys, library current: VaultLibrary, folder: URL, to output: URL) throws {
        for item in current.items { try verify(blob(item.id, keys: keys, in: folder), keys: keys, item: item) }
        do {
            switch keys {
            case .v1: try BackupContainer.write(directory: folder, names: backupEntryNames(library: current), to: output)
            case .v2: try backupV2Write(directory: folder.path, library: current, output: output.path); try ProtectedFiles.protect(output)
            }
        } catch { try? FileManager.default.removeItem(at: output); throw error }
    }
    public func backup() throws -> URL {
        let (keys, current, folder) = try session()
        let output = temporary.appendingPathComponent(backupFileName(id: UUID()))
        try writeBackup(keys: keys, library: current, folder: folder, to: output)
        let changed = libraryMarkBackupPrepared(library: current, now: Date()); try save(changed, keys: keys, at: folder); library = changed
        return output
    }
    public func backupRevision() throws -> String {
        let (keys, _, folder) = try session()
        if keys.version == 2 { return try vaultRevisionV2(directory: folder.path) }
        var hash = SHA256()
        hash.update(data: try headerData(at: folder))
        hash.update(data: try ProtectedFiles.boundedRead(indexURL(keys, in: folder)))
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
    public func automaticBackup(to output: URL) throws -> (UUID, String) {
        let (keys, current, folder) = try session()
        let revision = try backupRevision()
        try writeBackup(keys: keys, library: current, folder: folder, to: output)
        return (current.id, revision)
    }
    /// Restore a backup as a new vault: version 2 from either platform, or an older iPhone's version 1
    /// (migrated at once when restored with its PIN, pattern or passphrase). The whole container and
    /// every original are verified in staging first; an existing vault is never replaced.
    public func restore(_ url: URL, credential: String, recovery: Bool, kind: VaultCredentialKind = .passphrase) throws -> VaultLibrary {
        let staging = root.appendingPathComponent(".stage-" + UUID().uuidString); try ProtectedFiles.directory(staging)
        defer { try? FileManager.default.removeItem(at: staging) }
        let reader = try FileHandle(forReadingFrom: url)
        let prefix = (try reader.read(upToCount: 8)) ?? Data(); try? reader.close()
        let names: [String]
        if backupFormatVersion(prefix: prefix) == 1 { names = Array(try BackupContainer.read(url, into: staging)) }
        else { names = try backupV2Read(input: url.path, staging: staging.path) }
        let header = try header(at: staging)
        guard let keys = try keys(for: header, credential: credential, recovery: recovery, kind: kind) else { throw VaultError.InvalidCredential }
        do {
            let loaded = try load(at: staging, keys: keys, id: header.id)
            if keys.version == 1 { try backupNamesMatch(names: names, library: loaded) } else { try backupV2NamesMatch(names: names, library: loaded) }
            let target = root.appendingPathComponent(loaded.id.uuidString)
            guard !FileManager.default.fileExists(atPath: target.path) else { throw VaultError.DuplicateVault }
            // As at creation and change: one unlock secret opens one vault. (A recovery-key restore
            // can't check the backup's secret; the person can change it afterwards.)
            if !recovery { for folder in try folders() { if try opens(folder, secret: credential, kind: kind) { throw VaultError.SecretInUse } } }
            for item in loaded.items { try verify(blob(item.id, keys: keys, in: staging), keys: keys, item: item) }
            // A recovery-key restore can't check the backup's own secret against this device's vaults,
            // so the index requires a new one before the library opens. The recovery key has just
            // proved itself, so its reminder is cleared.
            var value = loaded
            if recovery {
                value = librarySetSecretChangeRequired(library: librarySetRecoveryUnconfirmed(library: loaded, unconfirmed: false), required: true)
                try save(value, keys: keys, at: staging)
            }
            try FileManager.default.moveItem(at: staging, to: target)
            lock(); open(keys, value, target)
            if case .v1(let old) = header, !recovery { migrate(header: old, credential: credential) }
            return library ?? value
        } catch { keys.wipe(); throw error }
    }
}
#endif
