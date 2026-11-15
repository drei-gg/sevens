import Foundation

/// The archive's file, which stays native on every platform: read once, then each change is
/// encoded by the caller (the core validates, encodes and refuses an oversized file), written
/// atomically, and published to readers only after the write succeeded. A file that can't be
/// read is never overwritten: every change reads first, so a failed read stops it.
public actor ArchiveFile<Archive: Sendable> {
    public typealias Writer = @Sendable (Data, URL) throws -> Void
    private let file: URL?
    private let maxBytes: Int
    private let tooLarge: any Error & Sendable
    private let decode: @Sendable (Data) throws -> Archive
    private let encode: @Sendable (Archive) throws -> Data
    private let writer: Writer
    private var cache: Archive
    private var loaded: Bool

    /// `file: nil` keeps the archive in memory only (previews and tests), starting from `seed`.
    public init(file: URL?, seed: Archive, maxBytes: Int, tooLarge: any Error & Sendable,
                decode: @escaping @Sendable (Data) throws -> Archive, encode: @escaping @Sendable (Archive) throws -> Data,
                writer: @escaping Writer = AtomicFile.write) {
        self.file = file; cache = seed; loaded = file == nil
        self.maxBytes = maxBytes; self.tooLarge = tooLarge; self.decode = decode; self.encode = encode; self.writer = writer
    }

    public func read() throws -> Archive {
        if !loaded {
            if let file, FileManager.default.fileExists(atPath: file.path) {
                guard (try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= maxBytes else { throw tooLarge }
                cache = try decode(Data(contentsOf: file))
            }
            loaded = true
        }
        return cache
    }

    public func change(_ action: @Sendable (inout Archive) throws -> Void) throws -> Archive {
        var next = try read(); try action(&next)
        let bytes = try encode(next)
        if let file { try writer(bytes, file) }
        cache = next; return cache
    }
}

public enum AtomicFile {
    /// Writes to a temporary file and renames it over the old one, so a crash leaves either file
    /// whole. On iOS the file is readable after the first unlock, so alarms can open it.
    public static let write: @Sendable (Data, URL) throws -> Void = { data, url in
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url, options: .atomic)
        #endif
    }
}
