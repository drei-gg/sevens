#if canImport(CryptoKit)
import Foundation
import VaultCore

/// Transport framing only. The index and each payload are independently authenticated ciphertext.
/// No filesystem path from a backup is trusted; only fixed names and canonical UUID blobs are accepted.
/// The framing, its name rule and size limits are the core's (`.vault7`); the files stream here,
/// so the reader applies the core decoder's checks in its order without loading a backup whole.
enum BackupContainer {
    static func validName(_ name: String) -> Bool { backupNameValid(name: name) }
    static func write(directory: URL, names: [String], to output: URL) throws {
        let prefix = try backupContainerPrefix(names: names)
        guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw VaultError.Damaged }
        try ProtectedFiles.protect(output)
        let writer = try FileHandle(forWritingTo: output); defer { try? writer.close() }
        try writer.write(contentsOf: prefix)
        for name in names {
            guard validName(name) else { throw VaultError.Damaged }
            let url = directory.appendingPathComponent(name)
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize else { throw VaultError.MissingFile }
            try writer.write(contentsOf: backupEntryPrefix(name: name, size: UInt64(size)))
            let reader = try FileHandle(forReadingFrom: url); defer { try? reader.close() }
            while let chunk = try reader.read(upToCount: chunkBytes), !chunk.isEmpty { try writer.write(contentsOf: chunk) }
        }
        try writer.synchronize()
    }
    static func read(_ input: URL, into directory: URL) throws -> Set<String> {
        let reader = try FileHandle(forReadingFrom: input); defer { try? reader.close() }
        let total = try reader.seekToEnd(); try reader.seek(toOffset: 0)
        guard try exact(8, from: reader) == magic else { throw VaultError.Unsupported }
        let count = try number(from: reader); guard count >= 2, count <= 1_000_000 else { throw VaultError.Damaged }
        var names = Set<String>()
        for _ in 0..<count {
            let length = try number(from: reader); guard length > 0, length <= 80 else { throw VaultError.Damaged }
            guard let name = String(data: try exact(Int(length), from: reader), encoding: .utf8), validName(name), names.insert(name).inserted else { throw VaultError.Damaged }
            let size = try number(from: reader); let offset = try reader.offset()
            guard offset <= total, size <= total - offset else { throw VaultError.Damaged }
            if name == "header.json" { guard size <= VaultCrypto.format.headerMaxBytes else { throw VaultError.Damaged } }
            if name == "index.sealed" { guard size <= VaultCrypto.format.sealedIndexMaxBytes else { throw VaultError.Damaged } }
            let url = directory.appendingPathComponent(name)
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw VaultError.Damaged }
            try ProtectedFiles.protect(url)
            let writer = try FileHandle(forWritingTo: url); defer { try? writer.close() }
            var remaining = size
            while remaining > 0 {
                let data = try exact(Int(min(remaining, UInt64(chunkBytes))), from: reader)
                try writer.write(contentsOf: data); remaining -= UInt64(data.count)
            }
            try writer.synchronize()
        }
        guard try reader.offset() == total, names.contains("header.json"), names.contains("index.sealed") else { throw VaultError.Damaged }
        return names
    }
    /// The magic: the first eight bytes of the core's framing.
    private static var magic: Data { Data(((try? backupContainerPrefix(names: [])) ?? Data()).prefix(8)) }
    private static var chunkBytes: Int { Int(VaultCrypto.format.streamChunkBytes) }
    private static func number(from handle: FileHandle) throws -> UInt64 {
        try exact(8, from: handle).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }
    private static func exact(_ count: Int, from handle: FileHandle) throws -> Data {
        var data = Data()
        while data.count < count {
            guard let chunk = try handle.read(upToCount: count - data.count), !chunk.isEmpty else { throw VaultError.Damaged }
            data.append(chunk)
        }
        return data
    }
}
#endif
