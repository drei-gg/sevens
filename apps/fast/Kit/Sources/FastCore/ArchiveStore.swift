import Foundation

/// Serial, write-through transactions. Publish only after the atomic write succeeds.
public actor ArchiveStore {
    public typealias Writer = @Sendable (Data, URL) throws -> Void
    private let file: URL?
    private let writer: Writer
    private var archive: FastArchive
    private var loaded = false
    private var blocked: (any Error)?

    public init(file: URL?, seed: FastArchive = FastArchive(), writer: @escaping Writer = ArchiveStore.atomicWrite) {
        self.file = file; self.archive = seed; self.writer = writer
        if file == nil { loaded = true }
    }
    public func read(now: Date = Date()) throws -> FastArchive {
        if let blocked { throw blocked }
        if !loaded {
            do {
                if let file, FileManager.default.fileExists(atPath: file.path) {
                    let size = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size <= ArchiveCodec.maxBytes else { throw TrackerError.tooLarge }
                    archive = try ArchiveCodec.decode(Data(contentsOf: file), now: now)
                }
                loaded = true
            } catch {
                blocked = error; throw error
            }
        }
        return archive
    }
    /// Retry a protected-data read after unlocking, without replacing an unreadable file.
    public func retryRead(now: Date = Date()) throws -> FastArchive {
        if blocked != nil { blocked = nil; loaded = false }
        return try read(now: now)
    }
    public func transact(now: Date = Date(), _ change: @Sendable (inout FastArchive) throws -> Void) throws -> FastArchive {
        var next = try read(now: now)
        try change(&next); try next.validate(now: now)
        // A replay of an already committed Watch command is already durable.
        // Do not turn its successful receipt into a failure by writing the same bytes again.
        guard next != archive else { return archive }
        next.companionRevision = UUID()
        let bytes = try ArchiveCodec.encode(next)
        guard bytes.count <= ArchiveCodec.maxBytes else { throw TrackerError.tooLarge }
        if let file { try writer(bytes, file) }
        archive = next
        return next
    }
    public func importData(_ data: Data, now: Date = Date()) throws -> FastArchive {
        let incoming = try ArchiveCodec.decode(data, now: now)
        return try transact(now: now) { current in
            current = try ArchiveCodec.merge(incoming, into: current, now: now).archive
        }
    }
    public func originalData() throws -> Data {
        if let file, FileManager.default.fileExists(atPath: file.path) { return try Data(contentsOf: file) }
        return try ArchiveCodec.encode(archive)
    }
    public static let atomicWrite: Writer = { data, url in
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
    }
}
