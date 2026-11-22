import Foundation
import Testing
@testable import AlarmStore

struct ArchiveFileTests {
    struct Refused: Error {}
    func store(_ file: URL?, writer: @escaping ArchiveFile<[String]>.Writer = AtomicFile.write) -> ArchiveFile<[String]> {
        ArchiveFile(file: file, seed: [], maxBytes: 64, tooLarge: Refused(),
                    decode: { try JSONDecoder().decode([String].self, from: $0) },
                    encode: { list in guard list.count <= 3 else { throw Refused() }; return try JSONEncoder().encode(list) },
                    writer: writer)
    }
    func temporary() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("alarm-store-\(UUID().uuidString)/archive.json") }

    @Test func changesAreWrittenThenPublished() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        _ = try await store(file).change { $0.append("wake") }
        #expect(try await store(file).read() == ["wake"])
    }
    @Test func failedWriteDoesNotPublish() async throws {
        let repo = store(temporary(), writer: { _, _ in throw Refused() })
        await #expect(throws: Refused.self) { try await repo.change { $0.append("wake") } }
        #expect(try await repo.read().isEmpty)
    }
    @Test func refusedEncodeWritesNothing() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let repo = store(file)
        await #expect(throws: Refused.self) { try await repo.change { $0 = ["a", "b", "c", "d"] } }
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(try await repo.read().isEmpty)
    }
    @Test func oversizedEncodedChangeDoesNotPublishOrReplace() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let repo = store(file)
        _ = try await repo.change { $0 = ["wake"] }
        let before = try Data(contentsOf: file)
        await #expect(throws: Refused.self) { try await repo.change { $0 = [String(repeating: "a", count: 100)] } }
        #expect(try await repo.read() == ["wake"])
        #expect(try Data(contentsOf: file) == before)
    }
    @Test func unreadableAndOversizedFilesAreKept() async throws {
        let file = temporary(); defer { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        let broken = Data("broken".utf8); try broken.write(to: file)
        await #expect(throws: (any Error).self) { try await store(file).change { $0 = [] } }
        #expect(try Data(contentsOf: file) == broken)
        let big = Data(repeating: 0x20, count: 65); try big.write(to: file)
        await #expect(throws: Refused.self) { try await store(file).change { $0 = [] } }
        #expect(try Data(contentsOf: file) == big)
    }
}
