#if canImport(CryptoKit)
import Foundation
import CryptoKit
import Testing
import VaultCore
@testable import VaultStorage

struct Fixture {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("VaultTest-" + UUID().uuidString)
    var repo: VaultRepository { VaultRepository(root: directory.appendingPathComponent("data"), temporary: directory.appendingPathComponent("tmp")) }
    func cleanup() { try? FileManager.default.removeItem(at: directory) }
    func source(_ data: Data) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(UUID().uuidString); try data.write(to: url); return url
    }
}
let password = "four separate private words"

@Test func encryptedOriginalsRecoveryAndRotation() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let created = try await repo.create(name: "Documents", passphrase: password)
    let bytes = Data((0..<2_100_003).map { UInt8($0 % 251) }); let source = try f.source(bytes)
    let item = try await repo.importFile(source, name: "private-original.bin", type: "public.data")
    #expect(try Data(contentsOf: await repo.export(item.id)) == bytes)
    let path = f.directory.appendingPathComponent("data/" + created.library.id.uuidString)
    let originalCiphertext = try Data(contentsOf: path.appendingPathComponent(item.id.uuidString + ".age"))
    #expect(!originalCiphertext.starts(with: bytes.prefix(64)))
    let index = try Data(contentsOf: path.appendingPathComponent("index.age"))
    #expect(index.range(of: Data("private-original".utf8)) == nil)
    try await repo.changePassphrase("another completely separate password")
    #expect(try Data(contentsOf: path.appendingPathComponent(item.id.uuidString + ".age")) == originalCiphertext)
    await repo.lock()
    await #expect(throws: (any Error).self) { try await repo.unlock(credential: password) }
    #expect(try await repo.unlock(credential: created.recoveryKey, recovery: true).items.count == 1)
    await repo.lock()
    #expect(try await repo.unlock(credential: "another completely separate password").items.count == 1)
}

@Test func backupRestoreTrashAndNoOverwrite() async throws {
    let f = Fixture(), g = Fixture(); defer { f.cleanup(); g.cleanup() }
    let repo = f.repo, other = g.repo; try await repo.prepare(); try await other.prepare()
    let created = try await repo.create(name: "Travel", passphrase: password)
    let source = try f.source(Data("original evidence".utf8))
    let item = try await repo.importFile(source, name: "ticket.txt", type: "public.plain-text")
    try await repo.updateItem(item.id, favorite: true, trashed: true)
    let backup = try await repo.backup()
    let restored = try await other.restore(backup, credential: created.recoveryKey, recovery: true)
    #expect(restored.items.first?.deleted != nil); #expect(restored.items.first?.favorite == true)
    try await other.updateItem(item.id, trashed: false)
    #expect(try String(contentsOf: await other.export(item.id), encoding: .utf8) == "original evidence")
    await #expect(throws: VaultError.DuplicateVault) { try await other.restore(backup, credential: password, recovery: false) }
    #expect(try await other.snapshot().items.first?.deleted == nil)
}

@Test func tamperingNeverPublishesAndBackupRefusesCorruption() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Important", passphrase: password)
    let item = try await repo.importFile(f.source(Data(repeating: 73, count: 30_000)), name: "evidence.bin", type: "public.data")
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/" + item.id.uuidString + ".age")
    var bytes = try Data(contentsOf: url); bytes[bytes.count / 2] ^= 1; try bytes.write(to: url)
    await #expect(throws: (any Error).self) { try await repo.export(item.id) }
    await #expect(throws: (any Error).self) { try await repo.backup() }
    #expect(try await repo.snapshot().items.count == 1)
    let temp = f.directory.appendingPathComponent("tmp/" + item.id.uuidString + "/evidence.bin")
    #expect(!FileManager.default.fileExists(atPath: temp.path))
}

@Test func credentialsIsolateVaultsAndNoPlaintextSurvivesLock() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    _ = try await repo.create(name: "First", passphrase: password)
    let item = try await repo.importFile(f.source(Data("one".utf8)), name: "one.txt", type: "public.plain-text")
    let preview = try await repo.export(item.id); #expect(FileManager.default.fileExists(atPath: preview.path))
    await repo.lock(); #expect(!FileManager.default.fileExists(atPath: preview.path))
    await #expect(throws: (any Error).self) { try await repo.export(item.id) }
    _ = try await repo.create(name: "Other", passphrase: "a different vault password")
    #expect(try await repo.snapshot().items.isEmpty)
    #expect(try await repo.unlock(credential: password).items.count == 1)
    await #expect(throws: VaultError.DuplicateVault) { try await repo.create(name: "Collision", passphrase: password) }
}

@Test func malformedBackupsPreserveExistingState() async throws {
    let f = Fixture(), g = Fixture(); defer { f.cleanup(); g.cleanup() }; let repo = f.repo, other = g.repo
    try await repo.prepare(); try await other.prepare()
    _ = try await repo.create(name: "Original", passphrase: password)
    let backup = try await repo.backup(); let bytes = try Data(contentsOf: backup)
    for malformed in [bytes.dropLast(), bytes + Data([1]), Data("7DVAULT1".utf8) + Data(repeating: 255, count: 8)] {
        let url = try g.source(Data(malformed))
        await #expect(throws: (any Error).self) { try await other.restore(url, credential: password, recovery: false) }
    }
    let files = try FileManager.default.contentsOfDirectory(atPath: g.directory.appendingPathComponent("data").path)
    #expect(files.isEmpty)
    #expect(!BackupContainer.validName("../header.json")); #expect(!BackupContainer.validName("header.json/extra"))
    #expect(try await repo.snapshot().name == "Original")
}

@Test func metadataAuthenticationFailsClosed() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Private", passphrase: password)
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/index.age")
    var bytes = try Data(contentsOf: url); bytes[15] ^= 1; try bytes.write(to: url)
    await repo.lock(); await #expect(throws: (any Error).self) { try await repo.unlock(credential: password) }
    #expect(try Data(contentsOf: url) == bytes)
}

@Test func noteEditsAndRecoveryReplacementSurviveRestart() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Notes", passphrase: password)
    try await repo.addFolder(name: "Work", parent: nil)
    let folder = try await repo.snapshot().folders[0].id
    try await repo.addNote(name: "Plan", text: "original", folder: folder)
    let old = try await repo.snapshot().items[0].id
    try await repo.updateItem(old, favorite: true)
    try await repo.editNote(old, text: "updated without data loss")
    let item = try await repo.snapshot().items[0]
    #expect(item.id != old); #expect(item.favorite); #expect(item.folder == folder)
    #expect(try await repo.readNote(item.id) == "updated without data loss")
    let replacement = try await repo.replaceRecoveryKey()
    let restarted = f.repo
    await #expect(throws: (any Error).self) { try await restarted.unlock(credential: made.recoveryKey, recovery: true) }
    #expect(try await restarted.unlock(credential: replacement, recovery: true).items.count == 1)
    #expect(try await restarted.readNote(item.id) == "updated without data loss")
}

@Test func emptyFileAndTruncatedEncryptedStream() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Files", passphrase: password)
    let empty = try await repo.importFile(f.source(Data()), name: "empty.txt", type: "public.plain-text")
    #expect(try Data(contentsOf: await repo.export(empty.id)).isEmpty)
    let item = try await repo.importFile(f.source(Data(repeating: 11, count: 1_200_000)), name: "large.bin", type: "public.data")
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/" + item.id.uuidString + ".age")
    let data = try Data(contentsOf: url); try Data(data.dropLast(20)).write(to: url)
    await #expect(throws: (any Error).self) { try await repo.export(item.id) }
    #expect(try await repo.snapshot().items.count == 2)
}

@Test func swappedCiphertextAndFailedImportDoNotAlterLibrary() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Files", passphrase: password)
    let a = try await repo.importFile(f.source(Data("alpha".utf8)), name: "a.txt", type: "public.plain-text")
    let b = try await repo.importFile(f.source(Data("bravo".utf8)), name: "b.txt", type: "public.plain-text")
    let folder = f.directory.appendingPathComponent("data/" + made.library.id.uuidString)
    let aURL = folder.appendingPathComponent(a.id.uuidString + ".age"), bURL = folder.appendingPathComponent(b.id.uuidString + ".age")
    try Data(contentsOf: bURL).write(to: aURL)
    await #expect(throws: (any Error).self) { try await repo.export(a.id) }
    #expect(try await repo.readNote(b.id) == "bravo")
    let before = try await repo.snapshot()
    await #expect(throws: (any Error).self) { try await repo.importFile(f.directory.appendingPathComponent("missing"), name: "missing.txt", type: "public.plain-text") }
    #expect(try await repo.snapshot() == before)
}
@Test func pinAndPatternRouteIndependentlyAndRoundTripThroughBackup() async throws {
    let f = Fixture(), g = Fixture(); defer { f.cleanup(); g.cleanup() }; let repo = f.repo
    try await repo.prepare(); let pattern = "0,1,2,3,4,9,14,19,24"
    let pin = try await repo.create(name: "PIN vault", passphrase: "00123456", kind: .pin)
    try await repo.addNote(name: "PIN only", text: "separate secret", folder: nil)
    let drawn = try await repo.create(name: "Pattern vault", passphrase: pattern, kind: .pattern)
    #expect(try await repo.unlock(credential: "00123456", kind: .pin).id == pin.library.id)
    #expect(try await repo.snapshot().items.count == 1)
    await #expect(throws: (any Error).self) { try await repo.unlock(credential: pattern, kind: .passphrase) }
    #expect(try await repo.unlock(credential: pattern, kind: .pattern).id == drawn.library.id)
    #expect(try await repo.snapshot().items.isEmpty)
    let backup = try await repo.backup(), other = g.repo; try await other.prepare()
    #expect(try await other.restore(backup, credential: pattern, recovery: false, kind: .pattern).id == drawn.library.id)
    await #expect(throws: VaultError.DuplicateVault) { try await repo.create(name: "Collision", passphrase: "00123456", kind: .pin) }
    try await repo.changePassphrase("98765432", kind: .pin)
    await #expect(throws: (any Error).self) { try await repo.unlock(credential: pattern, kind: .pattern) }
    #expect(try await repo.unlock(credential: "98765432", kind: .pin).id == drawn.library.id)
    #expect(try await repo.unlock(credential: drawn.recoveryKey, recovery: true).id == drawn.library.id)
}
@Test func automaticSnapshotSurvivesLockAndDoesNotChangeRevision() async throws {
    let f = Fixture(), g = Fixture(); defer { f.cleanup(); g.cleanup() }; let repo = f.repo
    try await repo.prepare(); let made = try await repo.create(name: "Auto", passphrase: password)
    try await repo.addNote(name: "Latest", text: "original encrypted snapshot", folder: nil)
    let revision = try await repo.backupRevision(), output = f.directory.appendingPathComponent("auto.vault7")
    let (id, savedRevision) = try await repo.automaticBackup(to: output)
    #expect(id == made.library.id); #expect(revision == savedRevision); #expect(try await repo.backupRevision() == revision)
    await repo.lock(); #expect(FileManager.default.fileExists(atPath: output.path))
    let other = g.repo; try await other.prepare()
    let restored = try await other.restore(output, credential: password, recovery: false)
    #expect(try await other.readNote(restored.items[0].id) == "original encrypted snapshot")
    await #expect(throws: VaultError.Locked) { try await repo.automaticBackup(to: f.directory.appendingPathComponent("locked.vault7")) }
}
// MARK: version 1 → 2

/// A vault exactly as the version 1 app wrote it: PBKDF2/AES-GCM envelopes, an AES-GCM index and
/// Apple Encrypted Archive originals. `legacyKind` drops `credentialKind` (the first headers).
struct V1Vault { let id: UUID; let recoveryKey: String; let items: [VaultItem]; let folder: URL }
func makeV1Vault(_ f: Fixture, name: String = "Old", passphrase: String = password, kind: VaultCredentialKind = .passphrase,
                 files: [(String, Data)] = [("photo.jpg", Data((0..<1_300_000).map { UInt8($0 % 239) })), ("note.txt", Data("kept".utf8))],
                 legacyKind: Bool = false) throws -> V1Vault {
    var library = VaultLibrary(name: name); let key = SymmetricKey(size: .bits256)
    let recovery = try VaultCrypto.random(32).base64EncodedString()
    let folder = f.directory.appendingPathComponent("data/" + library.id.uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    for (name, data) in files {
        let source = try f.source(data); let (bytes, digest) = try VaultCrypto.digest(source)
        let item = libraryImportItem(library: library, name: name, contentType: "public.data", bytes: bytes, digest: digest, folder: nil)
        try VaultCrypto.encrypt(source, to: folder.appendingPathComponent(blobName(item: item.id)), key: VaultCrypto.fileKey(key, id: item.id))
        library = libraryAppendItem(library: library, item: item)
    }
    let header = newVaultHeader(id: library.id,
        password: try VaultCrypto.wrap(key, credential: kind.material(passphrase), context: passwordContext(id: library.id)),
        recovery: try VaultCrypto.wrap(key, credential: recovery, context: recoveryContext(id: library.id)), kind: legacyKind ? nil : kind)
    var json = try headerEncode(header: header)
    if legacyKind, var object = try JSONSerialization.jsonObject(with: json) as? [String: Any] { object.removeValue(forKey: "credentialKind"); json = try JSONSerialization.data(withJSONObject: object) }
    try json.write(to: folder.appendingPathComponent("header.json"))
    try VaultCrypto.seal(libraryEncode(library: library), key: key, context: indexContext(id: library.id)).write(to: folder.appendingPathComponent("index.sealed"))
    return V1Vault(id: library.id, recoveryKey: recovery, items: library.items, folder: folder)
}
func names(_ folder: URL) throws -> Set<String> { Set(try FileManager.default.contentsOfDirectory(atPath: folder.path)) }

@Test func passwordUnlockMigratesVersion1ToVersion2() async throws {
    let f = Fixture(); defer { f.cleanup() }
    try FileManager.default.createDirectory(at: f.directory.appendingPathComponent("data"), withIntermediateDirectories: true)
    let old = try makeV1Vault(f); let repo = f.repo; try await repo.prepare()
    let opened = try await repo.unlock(credential: password)
    #expect(opened.items == old.items)
    #expect(try await repo.formatVersion() == 2)
    let replacement = try #require(await repo.takeMigratedRecoveryKey()); #expect(await repo.takeMigratedRecoveryKey() == nil)
    let files = try names(old.folder)
    #expect(files == Set(["header.json", "index.age"] + old.items.map { blobNameV2(item: $0.id) }))
    #expect(try headerFormatVersion(data: Data(contentsOf: old.folder.appendingPathComponent("header.json"))) == 2)
    #expect(try Data(contentsOf: await repo.export(old.items[1].id)) == Data("kept".utf8))
    #expect(try Data(contentsOf: await repo.export(old.items[0].id)).count == 1_300_000)
    #expect(try names(f.directory.appendingPathComponent("data")) == [old.id.uuidString])
    await repo.lock()
    await #expect(throws: (any Error).self) { try await repo.unlock(credential: old.recoveryKey, recovery: true) }
    #expect(try await repo.unlock(credential: replacement, recovery: true).id == old.id)
    #expect(await repo.takeMigratedRecoveryKey() == nil)
    await repo.lock(); #expect(try await repo.unlock(credential: password).id == old.id); #expect(await repo.takeMigratedRecoveryKey() == nil)
}

@Test func recoveryAndBiometricUnlockKeepVersion1Working() async throws {
    let f = Fixture(); defer { f.cleanup() }
    try FileManager.default.createDirectory(at: f.directory.appendingPathComponent("data"), withIntermediateDirectories: true)
    let old = try makeV1Vault(f); let repo = f.repo; try await repo.prepare()
    #expect(try await repo.unlock(credential: old.recoveryKey, recovery: true).id == old.id)
    #expect(try await repo.formatVersion() == 1); #expect(await repo.takeMigratedRecoveryKey() == nil)
    try await repo.addNote(name: "Added on v1", text: "still v1", folder: nil)
    let (id, material) = try await repo.biometricMaterial()
    await repo.lock(); #expect(try await repo.unlock(id: id, biometricKey: material).items.count == 3)
    #expect(try await repo.formatVersion() == 1)
    try await repo.clearTemporary()
    #expect(try await repo.searchNotes(query: "still v1").ids.count == 1)
    #expect(try FileManager.default.contentsOfDirectory(at: f.directory.appendingPathComponent("tmp"), includingPropertiesForKeys: nil).isEmpty)
    await repo.lock(); _ = try await repo.unlock(credential: password)
    #expect(try await repo.formatVersion() == 2); #expect(await repo.takeMigratedRecoveryKey() != nil)
    let note = try #require(await repo.snapshot().items.first { $0.name == "Added on v1.txt" })
    #expect(try await repo.readNote(note.id) == "still v1")
    // Face ID's version 1 key no longer opens it; the version 2 export does.
    await repo.lock(); await #expect(throws: (any Error).self) { try await repo.unlock(id: id, biometricKey: material) }
    _ = try await repo.unlock(credential: password); let (_, custody) = try await repo.biometricMaterial()
    await repo.lock(); #expect(try await repo.unlock(id: id, biometricKey: custody).items.count == 3)
}

@Test func legacyHeaderPinAndPatternMigrate() async throws {
    let f = Fixture(); defer { f.cleanup() }
    try FileManager.default.createDirectory(at: f.directory.appendingPathComponent("data"), withIntermediateDirectories: true)
    let legacy = try makeV1Vault(f, name: "Legacy", legacyKind: true)
    let pin = try makeV1Vault(f, name: "PIN", passphrase: "00123456", kind: .pin)
    let pattern = "0,1,2,3,4,9,14,19,24"; let drawn = try makeV1Vault(f, name: "Pattern", passphrase: pattern, kind: .pattern)
    let repo = f.repo; try await repo.prepare()
    #expect(try await repo.unlock(credential: password).id == legacy.id); #expect(try await repo.formatVersion() == 2)
    #expect(try await repo.unlock(credential: "00123456", kind: .pin).id == pin.id); #expect(try await repo.formatVersion() == 2)
    #expect(try await repo.unlock(credential: pattern, kind: .pattern).id == drawn.id); #expect(try await repo.formatVersion() == 2)
    await repo.lock()
    await #expect(throws: (any Error).self) { try await repo.unlock(credential: pattern, kind: .passphrase) }
    #expect(try await repo.unlock(credential: "00123456", kind: .pin).id == pin.id)
    await #expect(throws: VaultError.DuplicateVault) { try await repo.create(name: "Clash", passphrase: pattern, kind: .pattern) }
}

@Test func version1BackupsRestoreAndMigrate() async throws {
    let f = Fixture(), g = Fixture(), h = Fixture(); defer { f.cleanup(); g.cleanup(); h.cleanup() }
    try FileManager.default.createDirectory(at: f.directory.appendingPathComponent("data"), withIntermediateDirectories: true)
    let old = try makeV1Vault(f); let repo = f.repo; try await repo.prepare()
    _ = try await repo.unlock(credential: old.recoveryKey, recovery: true)
    let backup = try await repo.backup()   // still version 1: written by the version 1 path
    #expect(try FileHandle(forReadingFrom: backup).read(upToCount: 8) == Data("7DVAULT1".utf8))
    let other = g.repo; try await other.prepare()
    #expect(try await other.restore(backup, credential: password, recovery: false).id == old.id)
    #expect(try await other.formatVersion() == 2); #expect(await other.takeMigratedRecoveryKey() != nil)
    let third = h.repo; try await third.prepare()
    #expect(try await third.restore(backup, credential: old.recoveryKey, recovery: true).id == old.id)
    #expect(try await third.formatVersion() == 1)
    // A migrated vault backs up as version 2, which restores cleanly elsewhere.
    let v2 = try await other.backup()
    #expect(try FileHandle(forReadingFrom: v2).read(upToCount: 8) == Data("7DVAULT2".utf8))
    let i = Fixture(); defer { i.cleanup() }; let fourth = i.repo; try await fourth.prepare()
    #expect(try await fourth.restore(v2, credential: password, recovery: false).items.count == 2)
}

@Test func interruptedMigrationRecoversOnPrepare() async throws {
    let f = Fixture(); defer { f.cleanup() }
    let data = f.directory.appendingPathComponent("data")
    try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
    let old = try makeV1Vault(f)
    // Crashed after retiring the original but before publishing: the original comes back.
    try FileManager.default.moveItem(at: old.folder, to: data.appendingPathComponent(".old-" + old.id.uuidString))
    try FileManager.default.createDirectory(at: data.appendingPathComponent(".migrate-" + old.id.uuidString), withIntermediateDirectories: true)
    let repo = f.repo; try await repo.prepare()
    #expect(try names(data) == [old.id.uuidString])
    #expect(try await repo.unlock(credential: password).id == old.id); #expect(try await repo.formatVersion() == 2)
    // Crashed after publishing: the leftover original is discarded and the new vault kept.
    await repo.lock(); try FileManager.default.createDirectory(at: data.appendingPathComponent(".old-" + old.id.uuidString), withIntermediateDirectories: true)
    try await repo.prepare()
    #expect(try names(data) == [old.id.uuidString])
    #expect(try await repo.unlock(credential: password).id == old.id)
}

@Test func failedMigrationLeavesVersion1Open() async throws {
    let f = Fixture(); defer { f.cleanup() }
    try FileManager.default.createDirectory(at: f.directory.appendingPathComponent("data"), withIntermediateDirectories: true)
    let old = try makeV1Vault(f)
    // A damaged original can't be re-encrypted: the vault stays version 1, untouched and open.
    let blob = old.folder.appendingPathComponent(blobName(item: old.items[0].id))
    var bytes = try Data(contentsOf: blob); bytes[bytes.count / 2] ^= 1; try bytes.write(to: blob)
    let before = try names(old.folder)
    let repo = f.repo; try await repo.prepare()
    #expect(try await repo.unlock(credential: password).id == old.id)
    #expect(try await repo.formatVersion() == 1); #expect(await repo.takeMigratedRecoveryKey() == nil)
    #expect(try names(old.folder) == before)
    #expect(try Data(contentsOf: await repo.export(old.items[1].id)) == Data("kept".utf8))
    #expect(try names(f.directory.appendingPathComponent("data")) == [old.id.uuidString])
}

@Test func newVaultsAreVersion2AndBiometricCustodyRoundTrips() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "New", passphrase: password)
    #expect(try await repo.formatVersion() == 2)
    let folder = f.directory.appendingPathComponent("data/" + made.library.id.uuidString)
    #expect(try names(folder) == ["header.json", "index.age"])
    let (id, custody) = try await repo.biometricMaterial()
    #expect(String(decoding: custody, as: UTF8.self).hasPrefix("AGE-SECRET-KEY-1"))
    await repo.lock(); #expect(try await repo.unlock(id: id, biometricKey: custody).id == made.library.id)
    await repo.lock(); await #expect(throws: (any Error).self) { try await repo.unlock(id: id, biometricKey: Data(repeating: 1, count: 32)) }
}
#endif
