import Foundation

// The file primitives DocumentStore and the backup run on: atomic writes and new files with iOS
// data protection, exact reads, sizes on disk, and streaming copies. Plain Swift with no Scanner
// rules, so it builds anywhere; every rule (limits, formats, checksums) comes from the Rust core.

public enum ScanFileError: Error, Equatable, Sendable {
    /// The file ended before the expected number of bytes.
    case shortRead
    case cannotCreate
}

public enum ScanFiles {
    /// Streaming copies move this much at a time.
    public static let chunkBytes = 1_048_576

    /// Replaces `url` atomically; complete file protection on iOS.
    public static func write(_ data: Data, to url: URL) throws {
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
    }

    /// A new, empty file open for writing; complete file protection on iOS.
    public static func create(_ url: URL) throws -> FileHandle {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw ScanFileError.cannotCreate }
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        #endif
        return try FileHandle(forWritingTo: url)
    }

    /// The file's size on disk in bytes (0 when the system does not say).
    public static func size(_ url: URL) throws -> Int {
        try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
    }

    /// Exactly `count` bytes from the handle's position.
    public static func read(_ handle: FileHandle, count: Int) throws -> Data {
        guard count >= 0 else { throw ScanFileError.shortRead }
        guard count > 0 else { return Data() }
        guard let data = try handle.read(upToCount: count), data.count == count else { throw ScanFileError.shortRead }
        return data
    }

    /// Streams `count` bytes from `source` to `destination` in chunks, handing each chunk to
    /// `chunk` (for a running checksum) before it is written.
    public static func copy(_ source: FileHandle, to destination: FileHandle, count: Int, chunk: (Data) -> Void) throws {
        var remaining = count
        while remaining > 0 {
            let data = try read(source, count: min(chunkBytes, remaining))
            chunk(data)
            try destination.write(contentsOf: data)
            remaining -= data.count
        }
    }
}
