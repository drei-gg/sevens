import Foundation
import QRCore

// SPEC §4.4. The addresses the person said they know: one small JSON file beside History
// (`known.v1.json`, same folder, same file protection), loaded at launch and owned by AppModel.
//
// The Check share extension does not read it: extensions can't see the app's container, and
// being stricter there is the conservative choice.
//
// An unknown or unreadable file is never overwritten: the store reads as empty and refuses
// changes. Writes are immediate (the file is tiny and changes by a tap).

public actor KnownAddressStore {
    public typealias Writer = HistoryStore.Writer

    private let directory: URL?
    private let writer: Writer
    private var current = KnownAddresses()
    private var loaded = false
    private var readOnly = false
    /// Successful disk writes so far (tests).
    public private(set) var writeCount = 0

    /// A nil `directory` keeps everything in memory (previews, tests).
    public init(directory: URL?, writer: Writer? = nil, seed: KnownAddresses = KnownAddresses()) {
        self.directory = directory
        self.writer = writer ?? HistoryStore.atomicWriter
        if directory == nil {
            current = seed
            loaded = true
        }
    }

    /// Beside History's file.
    public static func defaultDirectory() -> URL { HistoryStore.defaultDirectory() }

    public func addresses() -> KnownAddresses {
        loadIfNeeded()
        return current
    }

    public var isReadOnly: Bool {
        loadIfNeeded()
        return readOnly
    }

    /// False when the file can't be read by this version or the write failed (nothing changes then).
    @discardableResult
    public func add(_ domain: String, at date: Date = Date()) -> KnownAddresses? {
        mutate { $0.add(domain, at: date) }
    }

    @discardableResult
    public func remove(_ domain: String) -> KnownAddresses? {
        mutate { $0.remove(domain) }
    }

    @discardableResult
    public func removeAll() -> KnownAddresses? {
        mutate { $0.removeAll() }
    }

    /// The result after the change, or nil when it was refused.
    private func mutate(_ change: (inout KnownAddresses) -> Void) -> KnownAddresses? {
        loadIfNeeded()
        guard !readOnly else { return nil }
        var next = current
        change(&next)
        if let url = directory?.appendingPathComponent(KnownAddressesFile.fileName) {
            do {
                try writer(try KnownAddressesFile.encode(next), url)
                writeCount += 1
            } catch {
                return nil
            }
        }
        current = next
        return next
    }

    private func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        guard let url = directory?.appendingPathComponent(KnownAddressesFile.fileName),
              let data = try? Data(contentsOf: url) else { return }
        switch KnownAddressesFile.decode(data) {
        case .addresses(let known): current = known
        case .unknownSchema, .unreadable: readOnly = true
        }
    }
}
