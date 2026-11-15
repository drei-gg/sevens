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
    let originalCiphertext = try Data(contentsOf: path.appendingPathComponent(item.id.uuidString + ".aea"))
    #expect(!originalCiphertext.starts(with: bytes.prefix(64)))
    let index = try Data(contentsOf: path.appendingPathComponent("index.sealed"))
    #expect(index.range(of: Data("private-original".utf8)) == nil)
    try await repo.changePassphrase("another completely separate password")
    #expect(try Data(contentsOf: path.appendingPathComponent(item.id.uuidString + ".aea")) == originalCiphertext)
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
    await #expect(throws: VaultError.duplicateVault) { try await other.restore(backup, credential: password, recovery: false) }
    #expect(try await other.snapshot().items.first?.deleted == nil)
}

@Test func tamperingNeverPublishesAndBackupRefusesCorruption() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Important", passphrase: password)
    let item = try await repo.importFile(f.source(Data(repeating: 73, count: 30_000)), name: "evidence.bin", type: "public.data")
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/" + item.id.uuidString + ".aea")
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
    await #expect(throws: VaultError.duplicateVault) { try await repo.create(name: "Collision", passphrase: password) }
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
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/index.sealed")
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
    let url = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/" + item.id.uuidString + ".aea")
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
    let aURL = folder.appendingPathComponent(a.id.uuidString + ".aea"), bURL = folder.appendingPathComponent(b.id.uuidString + ".aea")
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
    await #expect(throws: VaultError.duplicateVault) { try await repo.create(name: "Collision", passphrase: "00123456", kind: .pin) }
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
    await #expect(throws: VaultError.locked) { try await repo.automaticBackup(to: f.directory.appendingPathComponent("locked.vault7")) }
}
@Test func originalHeaderWithoutCredentialTypeStillUnlocks() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Legacy", passphrase: password)
    let headerURL = f.directory.appendingPathComponent("data/" + made.library.id.uuidString + "/header.json")
    var json = try #require(JSONSerialization.jsonObject(with: Data(contentsOf: headerURL)) as? [String: Any])
    json.removeValue(forKey: "credentialKind")
    try JSONSerialization.data(withJSONObject: json).write(to: headerURL)
    await repo.lock()
    #expect(try await repo.unlock(credential: password).id == made.library.id)
    #expect(try await repo.unlock(credential: made.recoveryKey, recovery: true).id == made.library.id)
}
#endif
