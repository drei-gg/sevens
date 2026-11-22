#if canImport(CryptoKit)
import Foundation
import Testing
import VaultCore
@testable import VaultStorage

@Test func noteSearchReadsVerifiedBodiesWithoutWritingPlaintextAndTracksEdits() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    _ = try await repo.create(name: "Search", passphrase: password)
    try await repo.addNote(name: "Unrelated title", text: "Café STRAẞE private phrase", folder: nil)
    let note = try #require(await repo.snapshot().items.first)
    try await repo.clearTemporary()
    let result = try await repo.searchNotes(query: "cafe strasse")
    #expect(result.ids == [note.id] && result.searched == 1 && result.unavailable == 0)
    #expect(try FileManager.default.contentsOfDirectory(at: f.directory.appendingPathComponent("tmp"), includingPropertiesForKeys: nil).isEmpty)
    #expect(try await repo.searchNotes(query: "private", favorites: true).ids.isEmpty)
    try await repo.updateItem(note.id, favorite: true, trashed: true)
    #expect(try await repo.searchNotes(query: "private").ids.isEmpty)
    #expect(try await repo.searchNotes(query: "private", trash: true, favorites: true).ids == [note.id])
    try await repo.updateItem(note.id, trashed: false)
    try await repo.editNote(note.id, text: "replacement body")
    #expect(try await repo.searchNotes(query: "private").ids.isEmpty)
    #expect(try await repo.searchNotes(query: "replacement").ids.count == 1)
    await repo.lock()
    await #expect(throws: VaultError.Locked) { try await repo.searchNotes(query: "replacement") }
    _ = try await repo.unlock(credential: password)
    #expect(try await repo.searchNotes(query: "replacement").ids.count == 1)
}

@Test func searchReportsCorruptionAndDoesNotMutateEncryptedFiles() async throws {
    let f = Fixture(); defer { f.cleanup() }; let repo = f.repo; try await repo.prepare()
    let made = try await repo.create(name: "Search", passphrase: password)
    try await repo.addNote(name: "One", text: "secret match", folder: nil)
    let item = try #require(await repo.snapshot().items.first)
    let directory = f.directory.appendingPathComponent("data/" + made.library.id.uuidString)
    let blob = directory.appendingPathComponent(item.id.uuidString + ".age")
    var data = try Data(contentsOf: blob); data[data.count / 2] ^= 1; try data.write(to: blob)
    let paths = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    let before = try paths.map { try Data(contentsOf: $0) }
    let result = try await repo.searchNotes(query: "secret")
    #expect(result.ids.isEmpty && result.unavailable == 1 && result.searched == 0)
    #expect(try paths.map { try Data(contentsOf: $0) } == before)
    #expect(try FileManager.default.contentsOfDirectory(at: f.directory.appendingPathComponent("tmp"), includingPropertiesForKeys: nil).isEmpty)
}
#endif
