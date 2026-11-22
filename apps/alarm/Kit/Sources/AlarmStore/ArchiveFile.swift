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
                // URL resource values can be cached across a replacement. Bound the actual read,
                // including a file that grows after opening, rather than trusting cached size.
                guard maxBytes >= 0 else { throw tooLarge }
                let input = try FileHandle(forReadingFrom: file)
                defer { try? input.close() }
                var bytes = Data()
                while true {
                    let chunk = try input.read(upToCount: min(65_535, maxBytes - bytes.count) + 1) ?? Data()
                    if chunk.isEmpty { break }
                    bytes.append(chunk)
                    guard bytes.count <= maxBytes else { throw tooLarge }
                }
                cache = try decode(bytes)
            }
            loaded = true
        }
        return cache
    }

    public func change(_ action: @Sendable (inout Archive) throws -> Void) throws -> Archive {
        var next = try read(); try action(&next)
        let bytes = try encode(next)
        guard bytes.count <= maxBytes else { throw tooLarge }
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
