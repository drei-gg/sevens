import Foundation
import Testing
@testable import QRFiles

/// The saved lists file (SPEC §4.3). Which lists win is the core's (`tests/lists.rs`).
@Suite struct ListsStoreTests {
    @Test func listsStoreWritesReadsAndRemoves() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("lists-store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ListsStore(directory: dir)
        #expect(store.read() == nil)
        try store.write(Data("one".utf8))
        #expect(store.read() == Data("one".utf8))
        try store.write(Data("two".utf8))
        #expect(store.read() == Data("two".utf8))
        store.remove()
        #expect(store.read() == nil)
        store.remove()   // nothing saved: no problem
    }

    @Test func listsStoreIgnoresAnOversizeFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("lists-store-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ListsStore(directory: dir)
        try store.write(Data(count: ListsStore.maxBytes + 1))
        #expect(store.read() == nil)
    }
}
