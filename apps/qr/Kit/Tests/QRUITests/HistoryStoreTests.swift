import Foundation
import Testing
import QRCore
@testable import QRUI

/// Counts writer calls from any thread.
final class HistoryWriteCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var n = 0
    var count: Int { lock.withLock { n } }
    var writer: HistoryStore.Writer {
        { [self] data, url in
            lock.withLock { n += 1 }
            try HistoryStore.atomicWriter(data, url)
        }
    }
}

enum HistoryTestSupport {
    static func tempDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("history-tests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    static func item(_ payload: String, at seconds: TimeInterval = 0, typeID: String = "text",
                     level: Level = .normal, note: String? = nil, kind: HistoryItem.Kind = .scanned) -> HistoryItem {
        HistoryItem(kind: kind, payload: payload, symbology: .qr, typeID: typeID, level: level,
                    signalIDs: [], date: Date(timeIntervalSince1970: 1_800_000_000 + seconds), note: note)
    }

    static func fileURL(_ dir: URL) -> URL { dir.appendingPathComponent(HistoryFile.fileName) }
}

@Suite struct HistoryStoreTests {
    typealias S = HistoryTestSupport

    @Test func roundTripThroughTheFile() async throws {
        let dir = S.tempDirectory()
        let store = HistoryStore(directory: dir)
        await store.add(S.item("older", at: 0))
        await store.add(S.item("newer", at: 100, typeID: "link", level: .careful, note: "hi"))
        await store.flush()
        #expect(FileManager.default.fileExists(atPath: S.fileURL(dir).path))

        let again = HistoryStore(directory: dir)
        let items = await again.items
        #expect(items.map(\.payload) == ["newer", "older"])
        #expect(items.first?.note == "hi")
        #expect(items.first?.level == .careful)
        #expect(await again.isReadOnly == false)
    }

    @Test func missingFileIsEmptyAndWritable() async {
        let store = HistoryStore(directory: S.tempDirectory())
        #expect(await store.items.isEmpty)
        #expect(await store.isReadOnly == false)
        #expect(await store.readOnlyReason == nil)
    }

    @Test func itemsStayNewestFirst() async {
        let store = HistoryStore(directory: nil)
        await store.add(S.item("b", at: 50))
        await store.add(S.item("c", at: 90))
        await store.add(S.item("a", at: 10))
        #expect(await store.items.map(\.payload) == ["c", "b", "a"])
    }

    @Test func manyAddsCoalesceIntoOneWrite() async throws {
        let dir = S.tempDirectory()
        let counter = HistoryWriteCounter()
        let store = HistoryStore(directory: dir, debounce: .milliseconds(100), writer: counter.writer)
        for i in 0..<25 { await store.add(S.item("item \(i)", at: Double(i))) }
        #expect(counter.count == 0)                       // still waiting on the timer
        // Wait for the debounced write; poll rather than sleep a fixed time, so a busy test
        // machine doesn't fail this. Then wait a little longer to prove no second write follows.
        let deadline = ContinuousClock.now + .seconds(10)
        while counter.count == 0, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(50)) }
        try await Task.sleep(for: .milliseconds(300))
        #expect(counter.count == 1)
        #expect(await store.writeCount == 1)
        #expect(await HistoryStore(directory: dir).items.count == 25)

        await store.flush()                               // nothing pending: no extra write
        #expect(counter.count == 1)
        await store.add(S.item("one more", at: 99))
        await store.flush()                               // flush writes right away
        #expect(counter.count == 2)
        try await Task.sleep(for: .milliseconds(300))     // and the cancelled timer adds nothing
        #expect(counter.count == 2)
    }

    @Test func defaultWriterCreatesTheFolderAndWritesAReadableFile() throws {
        let dir = S.tempDirectory().appendingPathComponent("nested", isDirectory: true)
        let url = S.fileURL(dir)
        let items = [S.item("x")]
        try HistoryStore.atomicWriter(try HistoryFile.encode(items), url)
        #expect(HistoryFile.decode(try Data(contentsOf: url)) == .items(items))
    }

    @Test func failedWriteIsRetried() async throws {
        let dir = S.tempDirectory()
        let flaky = FlakyWriter()
        let store = HistoryStore(directory: dir, debounce: .milliseconds(20), writer: flaky.writer)
        await store.add(S.item("x"))
        await store.flush()
        #expect(await store.lastWriteFailed)
        await store.flush()
        #expect(await store.lastWriteFailed == false)
        #expect(await HistoryStore(directory: dir).items.count == 1)
    }

    @Test func unknownSchemaIsReadOnlyAndTheFileIsNeverTouched() async throws {
        for text in ["{\"schema\":99,\"items\":[{\"future\":true}]}", "not json at all", "{\"schema\":1,\"items\":\"nope\"}"] {
            let dir = S.tempDirectory()
            let bytes = Data(text.utf8)
            try bytes.write(to: S.fileURL(dir))
            let counter = HistoryWriteCounter()
            let store = HistoryStore(directory: dir, debounce: .milliseconds(10), writer: counter.writer)

            #expect(await store.isReadOnly)
            #expect(await store.items.isEmpty)
            #expect(await store.readOnlyReason == "This version can't read the saved history.")
            #expect(await store.add(S.item("new")) == false)
            #expect(await store.update(note: "n", for: UUID()) == false)
            #expect(await store.delete(ids: [UUID()]) == 0)
            await store.clear()
            await store.flush()
            try await Task.sleep(for: .milliseconds(100))

            #expect(counter.count == 0)
            #expect(try Data(contentsOf: S.fileURL(dir)) == bytes)
            #expect(await store.items.isEmpty)
            #expect(await store.exportCSV().hasPrefix("date,kind,type"))
        }
    }

    @Test func deleteRemovesOnlyTheNamedItems() async {
        let store = HistoryStore(directory: nil)
        let a = S.item("a", at: 1), b = S.item("b", at: 2), c = S.item("c", at: 3)
        for i in [a, b, c] { await store.add(i) }
        #expect(await store.delete(ids: [a.id, c.id, UUID()]) == 2)
        #expect(await store.items.map(\.payload) == ["b"])
        #expect(await store.delete(ids: []) == 0)
    }

    @Test func clearEmptiesAndWritesAnEmptyFile() async throws {
        let dir = S.tempDirectory()
        let store = HistoryStore(directory: dir)
        await store.add(S.item("a"))
        await store.flush()
        await store.clear()
        await store.flush()
        #expect(await store.items.isEmpty)
        #expect(HistoryFile.decode(try Data(contentsOf: S.fileURL(dir))) == .items([]))
    }

    @Test func notes() async {
        let store = HistoryStore(directory: nil)
        let a = S.item("a")
        await store.add(a)
        #expect(await store.update(note: "  lunch  ", for: a.id))
        #expect(await store.items.first?.note == "lunch")
        #expect(await store.update(note: "   ", for: a.id))
        #expect(await store.items.first?.note == nil)
        #expect(await store.update(note: String(repeating: "x", count: 5_000), for: a.id))
        #expect(await store.items.first?.note?.count == HistoryStore.maxNoteLength)
        #expect(await store.update(note: "x", for: UUID()) == false)
    }

    @Test func searchMatchesPayloadNoteTypeIDAndTypeName() async {
        let store = HistoryStore(directory: nil)
        await store.add(S.item("https://Example.org/menu", at: 1, typeID: "link"))
        await store.add(S.item("hello world", at: 2, typeID: "text", note: "Birthday plan"))
        await store.add(S.item("WIFI:S:x;;", at: 3, typeID: "wifi"))
        #expect(await store.search("EXAMPLE").map(\.payload) == ["https://Example.org/menu"])
        #expect(await store.search("birthday").map(\.payload) == ["hello world"])
        #expect(await store.search("wifi").count >= 1)
        #expect(await store.search("wi-fi network").map(\.typeID) == ["wifi"])
        #expect(await store.search("web link").map(\.typeID) == ["link"])
        #expect(await store.search("nothing matches this").isEmpty)
        #expect(await store.search("  ").map(\.payload) == ["WIFI:S:x;;", "hello world", "https://Example.org/menu"])
    }

    @Test func exportJSONAndCSV() async throws {
        let store = HistoryStore(directory: nil)
        await store.add(S.item("=SUM(1,2)", at: 1, typeID: "text", level: .likelyScam, note: "say \"hi\""))
        await store.add(S.item("plain", at: 2))
        let json = try await store.exportJSON()
        guard case .items(let decoded) = HistoryFile.decode(json) else { Issue.record("not items"); return }
        #expect(decoded.map(\.payload) == ["plain", "=SUM(1,2)"])
        let csv = await store.exportCSV()
        #expect(csv.hasPrefix("date,kind,type,level,payload,note,signals,symbology,id\r\n"))
        #expect(csv.contains("'=SUM(1,2)"))
        #expect(csv.contains("\"say \"\"hi\"\"\""))
    }

    @Test func changesStreamReportsEdits() async {
        let store = HistoryStore(directory: nil)
        let stream = await store.changes()
        var iterator = stream.makeAsyncIterator()
        await store.add(S.item("a"))
        #expect(await iterator.next() != nil)
        await store.clear()
        #expect(await iterator.next() != nil)
    }

    @Test func seededInMemoryStoreNeverTouchesDisk() async {
        let store = HistoryStore(directory: nil, seed: [S.item("a", at: 1), S.item("b", at: 2)])
        #expect(await store.items.map(\.payload) == ["b", "a"])
        await store.flush()
        #expect(await store.writeCount == 0)
    }
}

/// Fails the first write, then works.
final class FlakyWriter: @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    struct Failure: Error {}
    var writer: HistoryStore.Writer {
        { [self] data, url in
            let call = lock.withLock { calls += 1; return calls }
            if call == 1 { throw Failure() }
            try HistoryStore.atomicWriter(data, url)
        }
    }
}
