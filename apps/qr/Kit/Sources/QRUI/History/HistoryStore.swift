import Foundation
import QRCore

// SPEC §10 / D13. History is one JSON file in the app's own container:
// `Application Support/History/history.v1.json`. Extensions never write it.
//
// Public API (everything is `await`ed from outside the actor):
//
//     init(directory: URL?, debounce:, writer:, seed:)   nil directory = in memory only (previews, tests)
//     static func defaultDirectory() -> URL
//     var items: [HistoryItem]                    newest first
//     func state() -> State                       items + read-only flag + reason, in one hop
//     var isReadOnly: Bool, readOnlyReason: String?
//     func add(_ item:) -> Bool                   false only when read-only
//     func update(note:for:) -> Bool              empty/blank note clears it; false if no such id
//     func delete(ids: Set<UUID>) -> Int          how many were removed
//     func clear()                                writes an empty file (read-only: no-op)
//     func search(_ query:) -> [HistoryItem]      payload, note, type ID and type display name
//     func exportJSON() throws -> Data            the file's own shape
//     func exportCSV() -> String                  RFC 4180
//     func changes() -> AsyncStream<Void>         one element per change (load, add, edit, delete, clear)
//     func flush()                                write now if anything is pending (backgrounding, tests)
//
// Unknown or unreadable file (`HistoryFile.Decoded.unknownSchema` / `.unreadable`): the store is
// read-only. `items` is empty, `readOnlyReason` says why, and the file is NEVER written or
// deleted in that state, not even by `clear()`; every mutation is a no-op.
//
// Writes: `HistoryFile.encode`, atomic, `.completeFileProtection` on iOS, coalesced over
// `debounce` (0.5 s): the first change starts a timer, later changes ride along. The file is
// not excluded from iCloud backup (the plan says nothing; the default stays).
//
// Not gated: the locked state (trial over) gates only *saving new scans* (see `AppModel.record`).
// Reading, searching, exporting, deleting and editing notes always work.

public actor HistoryStore {
    public struct State: Sendable, Equatable {
        public var items: [HistoryItem]
        public var isReadOnly: Bool
        public var readOnlyReason: String?
    }

    /// A longest-useful note; longer input is cut.
    public static let maxNoteLength = 2_000

    public typealias Writer = @Sendable (_ data: Data, _ url: URL) throws -> Void

    private let directory: URL?
    private let debounce: Duration
    private let writer: Writer
    private var list: [HistoryItem] = []
    private var loaded = false
    private var readOnly = false
    private var reason: String?
    private var dirty = false
    private var timer: Task<Void, Never>?
    private var observers: [UUID: AsyncStream<Void>.Continuation] = [:]
    /// Successful disk writes so far (tests).
    public private(set) var writeCount = 0
    /// The last write failed; it is retried on the next change or `flush()`.
    public private(set) var lastWriteFailed = false

    /// - Parameter seed: items an in-memory store (nil `directory`) starts with; ignored otherwise.
    public init(directory: URL?, debounce: Duration = .milliseconds(500), writer: Writer? = nil,
                seed: [HistoryItem] = []) {
        self.directory = directory
        self.debounce = debounce
        self.writer = writer ?? HistoryStore.atomicWriter
        if directory == nil {
            list = HistoryStore.newestFirst(seed)
            loaded = true
        }
    }

    public static func defaultDirectory() -> URL {
        let base = (try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                 appropriateFor: nil, create: true))
            ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("History", isDirectory: true)
    }

    public static let atomicWriter: Writer = { data, url in
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS) || os(visionOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: [.atomic])
        #endif
    }

    // MARK: Reading

    public var items: [HistoryItem] {
        loadIfNeeded()
        return list
    }

    public var isReadOnly: Bool {
        loadIfNeeded()
        return readOnly
    }

    public var readOnlyReason: String? {
        loadIfNeeded()
        return reason
    }

    public func state() -> State {
        loadIfNeeded()
        return State(items: list, isReadOnly: readOnly, readOnlyReason: reason)
    }

    public func search(_ query: String) -> [HistoryItem] {
        loadIfNeeded()
        let hits = HistoryFile.search(list, query: query)
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if needle.isEmpty { return hits }
        // The type's display name ("Web link") also counts, as the plan says "type name".
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        let hitIDs = Set(hits.map(\.id))
        return HistoryFile.search(list, query: "").filter { item in
            hitIDs.contains(item.id) || VerdictText.typeName(for: item.typeID).range(of: needle, options: options) != nil
        }
    }

    public func exportJSON() throws -> Data {
        loadIfNeeded()
        return try HistoryExport.json(list)
    }

    public func exportCSV() -> String {
        loadIfNeeded()
        return HistoryExport.csv(list)
    }

    /// One element per change. Each call makes an independent stream.
    public func changes() -> AsyncStream<Void> {
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self, bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        observers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.removeObserver(id) }
        }
        return stream
    }

    private func removeObserver(_ id: UUID) { observers[id] = nil }

    // MARK: Changing

    @discardableResult
    public func add(_ item: HistoryItem) -> Bool {
        loadIfNeeded()
        guard !readOnly else { return false }
        var item = item
        item.note = HistoryStore.cleaned(item.note)
        // Newest first; among equal dates the newer insertion goes first.
        let index = list.firstIndex { $0.date <= item.date } ?? list.endIndex
        list.insert(item, at: index)
        changed()
        return true
    }

    @discardableResult
    public func update(note: String?, for id: UUID) -> Bool {
        loadIfNeeded()
        guard !readOnly, let index = list.firstIndex(where: { $0.id == id }) else { return false }
        list[index].note = HistoryStore.cleaned(note)
        changed()
        return true
    }

    @discardableResult
    public func delete(ids: Set<UUID>) -> Int {
        loadIfNeeded()
        guard !readOnly, !ids.isEmpty else { return 0 }
        let before = list.count
        list.removeAll { ids.contains($0.id) }
        let removed = before - list.count
        if removed > 0 { changed() }
        return removed
    }

    public func clear() {
        loadIfNeeded()
        guard !readOnly else { return }
        list = []
        changed()
    }

    /// Writes now if a change is waiting for the timer.
    public func flush() {
        loadIfNeeded()
        timer?.cancel()
        timer = nil
        writeIfNeeded()
    }

    // MARK: Internals

    private var fileURL: URL? { directory?.appendingPathComponent(HistoryFile.fileName) }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        guard let url = fileURL, let data = try? Data(contentsOf: url) else { return }   // no file yet: empty, writable
        switch HistoryFile.decode(data) {
        case .items(let items):
            list = HistoryStore.newestFirst(items)
        case .unknownSchema, .unreadable:
            readOnly = true
            reason = String(localized: "history.readOnly.reason",
                            defaultValue: "This version can't read the saved history, so new scans aren't being saved.", bundle: .module)
        }
    }

    private func changed() {
        dirty = true
        for continuation in observers.values { continuation.yield() }
        guard directory != nil, timer == nil else { return }
        let wait = debounce
        timer = Task { [weak self] in
            do { try await Task.sleep(for: wait) } catch { return }
            await self?.timerFired()
        }
    }

    private func timerFired() {
        timer = nil
        writeIfNeeded()
    }

    private func writeIfNeeded() {
        guard dirty, !readOnly, let url = fileURL else { return }
        do {
            try writer(try HistoryFile.encode(list), url)
            dirty = false
            lastWriteFailed = false
            writeCount += 1
        } catch {
            lastWriteFailed = true      // stays dirty; the next change or flush() tries again
        }
    }

    private static func newestFirst(_ items: [HistoryItem]) -> [HistoryItem] {
        HistoryFile.search(items, query: "")
    }

    private static func cleaned(_ note: String?) -> String? {
        guard let note else { return nil }
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(maxNoteLength))
    }
}
