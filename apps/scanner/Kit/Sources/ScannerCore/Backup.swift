import Foundation

private struct BackupEntry: Codable {
    var document: ScanDocument
    var deleted: Bool
    var sizes: [Int]
}
private struct BackupIndex: Codable {
    var version = 1
    var entries: [BackupEntry]
}
private enum BackupIO {
    static let magic = Data("SEVSCAN1\n".utf8)
    static let maximumBytes = 1_000_000_000
    static func integer(_ value: UInt32) -> Data {
        Data((0..<4).map { UInt8(truncatingIfNeeded: value >> ($0 * 8)) })
    }
    static func exact(_ handle: FileHandle, count: Int) throws -> Data {
        guard count >= 0, let data = try handle.read(upToCount: count), data.count == count else { throw ScanError.invalidDocument }
        return data
    }
    static func number(_ handle: FileHandle) throws -> UInt32 {
        try exact(handle, count: 4).enumerated().reduce(UInt32(0)) { $0 | UInt32($1.element) << ($1.offset * 8) }
    }
    static let table: [UInt32] = (0..<256).map { n in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 == 1 ? 0xedb88320 ^ (c >> 1) : c >> 1 }
        return c
    }
    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
        return crc ^ 0xffffffff
    }
    static func copy(_ source: FileHandle, to destination: FileHandle, count: Int) throws -> UInt32 {
        var remaining = count; var crc: UInt32 = 0xffffffff
        while remaining > 0 {
            let data = try exact(source, count: min(1_048_576, remaining))
            for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xff)] ^ (crc >> 8) }
            try destination.write(contentsOf: data); remaining -= data.count
        }
        return crc ^ 0xffffffff
    }
    static func create(_ url: URL) throws -> FileHandle {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw ScanError.invalidDocument }
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: url.path)
        #endif
        return try FileHandle(forWritingTo: url)
    }
}

extension DocumentStore {
    /// Portable, streaming archive. Includes Recently Deleted; source files are never changed.
    /// An unreadable document blocks backup rather than silently producing an incomplete copy.
    public func exportBackup(to destination: URL) throws {
        let active = try list(); let deleted = try list(deleted: true)
        guard active.count + deleted.count <= 1_000 else { throw ScanError.tooLarge }
        var entries: [BackupEntry] = []; var files: [URL] = []; var total = 0
        for (documents, isDeleted) in [(active, false), (deleted, true)] {
            for document in documents {
                let base = isDeleted ? root.appendingPathComponent(".trash") : root
                var sizes: [Int] = []
                for page in document.pages {
                    let url = base.appendingPathComponent(document.id.uuidString).appendingPathComponent(page.id.uuidString + ".jpg")
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0, size <= 20_000_000 else { throw ScanError.tooLarge }
                    sizes.append(size); files.append(url); total += size + 4
                    guard total <= BackupIO.maximumBytes else { throw ScanError.tooLarge }
                }
                entries.append(BackupEntry(document: document, deleted: isDeleted, sizes: sizes))
            }
        }
        let index = try JSONEncoder().encode(BackupIndex(entries: entries))
        guard index.count <= 16_000_000, total + index.count + 17 <= BackupIO.maximumBytes else { throw ScanError.tooLarge }
        let pending = destination.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".pending")
        defer { try? FileManager.default.removeItem(at: pending) }
        let output = try BackupIO.create(pending)
        do {
            try output.write(contentsOf: BackupIO.magic)
            try output.write(contentsOf: BackupIO.integer(UInt32(index.count)))
            try output.write(contentsOf: index)
            try output.write(contentsOf: BackupIO.integer(BackupIO.checksum(index)))
            var fileIndex = 0
            for entry in entries {
                for size in entry.sizes {
                    let input = try FileHandle(forReadingFrom: files[fileIndex]); defer { try? input.close() }
                    let checksum = try BackupIO.copy(input, to: output, count: size)
                    try output.write(contentsOf: BackupIO.integer(checksum)); fileIndex += 1
                }
            }
            try output.synchronize(); try output.close()
            try FileManager.default.moveItem(at: pending, to: destination)
        } catch { try? output.close(); throw error }
    }

    /// Validate every record and checksum in staging before adding restored copies.
    /// New IDs intentionally preserve all existing documents, even when importing an older backup.
    @discardableResult public func restoreBackup(from source: URL) throws -> Int {
        let length = try source.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        guard length <= BackupIO.maximumBytes else { throw ScanError.tooLarge }
        let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
        guard try BackupIO.exact(input, count: BackupIO.magic.count) == BackupIO.magic else { throw ScanError.invalidDocument }
        let headerLength = Int(try BackupIO.number(input))
        guard headerLength <= 16_000_000 else { throw ScanError.tooLarge }
        let header = try BackupIO.exact(input, count: headerLength)
        guard try BackupIO.number(input) == BackupIO.checksum(header) else { throw ScanError.invalidDocument }
        let index = try JSONDecoder().decode(BackupIndex.self, from: header)
        guard index.version == 1, index.entries.count <= 1_000,
              Set(index.entries.map { $0.document.id }).count == index.entries.count else { throw ScanError.invalidDocument }
        var expected = BackupIO.magic.count + 8 + headerLength
        for entry in index.entries {
            try entry.document.validate()
            guard try JSONEncoder().encode(entry.document).count <= 6_000_000 else { throw ScanError.tooLarge }
            guard entry.sizes.count == entry.document.pages.count,
                  entry.sizes.allSatisfy({ $0 > 0 && $0 <= 20_000_000 }),
                  entry.sizes.reduce(0, +) <= 100_000_000 else { throw ScanError.invalidDocument }
            expected += entry.sizes.reduce(0, +) + entry.sizes.count * 4
            guard expected <= BackupIO.maximumBytes else { throw ScanError.tooLarge }
        }
        guard expected == length else { throw ScanError.invalidDocument }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let staging = root.appendingPathComponent(".restore-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: staging) }
        var additions: [(URL, URL)] = []
        for entry in index.entries {
            var document = entry.document; document.id = UUID()
            let folder = staging.appendingPathComponent(document.id.uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false)
            for (page, size) in zip(document.pages, entry.sizes) {
                let output = try BackupIO.create(folder.appendingPathComponent(page.id.uuidString + ".jpg"))
                let checksum: UInt32
                do { checksum = try BackupIO.copy(input, to: output, count: size); try output.close() }
                catch { try? output.close(); throw error }
                guard try BackupIO.number(input) == checksum else { throw ScanError.invalidDocument }
            }
            let manifest = try BackupIO.create(folder.appendingPathComponent("document.json"))
            do { try manifest.write(contentsOf: JSONEncoder().encode(document)); try manifest.close() }
            catch { try? manifest.close(); throw error }
            let base = entry.deleted ? root.appendingPathComponent(".trash") : root
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            additions.append((folder, base.appendingPathComponent(document.id.uuidString)))
        }
        var committed: [URL] = []
        do {
            for (from, to) in additions { try FileManager.default.moveItem(at: from, to: to); committed.append(to) }
        } catch {
            for url in committed { try? FileManager.default.removeItem(at: url) }
            throw error
        }
        return additions.count
    }
}
