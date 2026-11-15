#if canImport(CryptoKit)
import Foundation
import VaultCore

/// Transport framing only. The index and each payload are independently authenticated ciphertext.
/// No filesystem path from a backup is trusted; only fixed names and canonical UUID blobs are accepted.
enum BackupContainer {
    static let magic = Data("7DVAULT1".utf8)
    static func validName(_ name: String) -> Bool {
        if name == "header.json" || name == "index.sealed" { return true }
        guard name.hasSuffix(".aea"), let id = UUID(uuidString: String(name.dropLast(4))) else { return false }
        return name == id.uuidString + ".aea"
    }
    static func write(directory: URL, names: [String], to output: URL) throws {
        guard names.count <= 1_000_000, Set(names).count == names.count else { throw VaultError.damaged }
        guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw VaultError.damaged }
        try ProtectedFiles.protect(output)
        let writer = try FileHandle(forWritingTo: output); defer { try? writer.close() }
        try writer.write(contentsOf: magic); try number(UInt64(names.count), to: writer)
        for name in names {
            guard validName(name) else { throw VaultError.damaged }
            let url = directory.appendingPathComponent(name)
            let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
            guard values.isRegularFile == true, values.isSymbolicLink != true, let size = values.fileSize else { throw VaultError.missingFile }
            let data = Data(name.utf8); try number(UInt64(data.count), to: writer); try writer.write(contentsOf: data); try number(UInt64(size), to: writer)
            let reader = try FileHandle(forReadingFrom: url); defer { try? reader.close() }
            while let chunk = try reader.read(upToCount: 1_048_576), !chunk.isEmpty { try writer.write(contentsOf: chunk) }
        }
        try writer.synchronize()
    }
    static func read(_ input: URL, into directory: URL) throws -> Set<String> {
        let reader = try FileHandle(forReadingFrom: input); defer { try? reader.close() }
        let total = try reader.seekToEnd(); try reader.seek(toOffset: 0)
        guard try exact(8, from: reader) == magic else { throw VaultError.unsupported }
        let count = try number(from: reader); guard count >= 2, count <= 1_000_000 else { throw VaultError.damaged }
        var names = Set<String>()
        for _ in 0..<count {
            let length = try number(from: reader); guard length > 0, length <= 80 else { throw VaultError.damaged }
            guard let name = String(data: try exact(Int(length), from: reader), encoding: .utf8), validName(name), names.insert(name).inserted else { throw VaultError.damaged }
            let size = try number(from: reader); let offset = try reader.offset()
            guard offset <= total, size <= total - offset else { throw VaultError.damaged }
            if name == "header.json" { guard size <= 16_384 else { throw VaultError.damaged } }
            if name == "index.sealed" { guard size <= 32_000_000 else { throw VaultError.damaged } }
            let url = directory.appendingPathComponent(name)
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw VaultError.damaged }
            try ProtectedFiles.protect(url)
            let writer = try FileHandle(forWritingTo: url); defer { try? writer.close() }
            var remaining = size
            while remaining > 0 {
                let data = try exact(Int(min(remaining, 1_048_576)), from: reader)
                try writer.write(contentsOf: data); remaining -= UInt64(data.count)
            }
            try writer.synchronize()
        }
        guard try reader.offset() == total, names.contains("header.json"), names.contains("index.sealed") else { throw VaultError.damaged }
        return names
    }
    private static func number(_ value: UInt64, to handle: FileHandle) throws {
        var number = value.bigEndian
        try withUnsafeBytes(of: &number) { try handle.write(contentsOf: Data($0)) }
    }
    private static func number(from handle: FileHandle) throws -> UInt64 {
        try exact(8, from: handle).reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
    }
    private static func exact(_ count: Int, from handle: FileHandle) throws -> Data {
        var data = Data()
        while data.count < count {
            guard let chunk = try handle.read(upToCount: count - data.count), !chunk.isEmpty else { throw VaultError.damaged }
            data.append(chunk)
        }
        return data
    }
}
#endif
