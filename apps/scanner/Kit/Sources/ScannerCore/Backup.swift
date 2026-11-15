import Foundation
internal import ScannerFiles

// The `.scannerbackup` file. The core builds and checks the header and index, validates every
// record and the exact file length, and gives the CRC-32; the store streams page bytes in and out.

extension DocumentStore {
    /// Portable, streaming archive. Includes Recently Deleted; source files are never changed.
    /// An unreadable document blocks backup rather than silently producing an incomplete copy.
    public func exportBackup(to destination: URL) throws {
        var entries: [BackupEntry] = []; var files: [URL] = []
        for deleted in [false, true] {
            for document in try list(deleted: deleted) {
                let urls = try pageURLs(document, deleted: deleted)
                entries.append(BackupEntry(document: document, deleted: deleted, sizes: try urls.map { Int64(try ScanFiles.size($0)) }))
                files += urls
            }
        }
        let header = try backupHeader(entries: entries)
        let pending = destination.deletingLastPathComponent().appendingPathComponent(UUID().uuidString + ".pending")
        defer { try? FileManager.default.removeItem(at: pending) }
        let output = try ScanFiles.create(pending)
        do {
            try output.write(contentsOf: header)
            var fileIndex = 0
            for entry in entries {
                for size in entry.sizes {
                    let input = try FileHandle(forReadingFrom: files[fileIndex]); defer { try? input.close() }
                    let checksum = try stream(input, to: output, count: Int(size))
                    try output.write(contentsOf: backupChecksumBytes(checksum: checksum)); fileIndex += 1
                }
            }
            try output.synchronize(); try output.close()
            try FileManager.default.moveItem(at: pending, to: destination)
        } catch { try? output.close(); throw error }
    }

    /// Validate every record and checksum in staging before adding restored copies.
    /// New IDs intentionally preserve all existing documents, even when importing an older backup.
    @discardableResult public func restoreBackup(from source: URL) throws -> Int {
        let length = UInt64(try ScanFiles.size(source))
        let input = try FileHandle(forReadingFrom: source); defer { try? input.close() }
        let indexLength = try backupIndexLength(fileLength: length, preamble: exact(input, count: backupMagic().count + 4))
        let index = try readBackupIndex(fileLength: length, header: exact(input, count: Int(indexLength) + 4))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let staging = root.appendingPathComponent(".restore-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: staging) }
        var additions: [(URL, URL)] = []
        for entry in index.entries {
            let document = restoredCopy(document: entry.document)
            let name = documentFolderName(documentId: document.id)
            let folder = staging.appendingPathComponent(name)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false)
            for (page, size) in zip(document.pages, entry.sizes) {
                let output = try ScanFiles.create(folder.appendingPathComponent(pageFileName(pageId: page.id)))
                let checksum: UInt32
                do { checksum = try stream(input, to: output, count: Int(size)); try output.close() }
                catch { try? output.close(); throw error }
                guard try exact(input, count: 4) == backupChecksumBytes(checksum: checksum) else { throw ScanError.InvalidDocument }
            }
            try ScanFiles.write(manifestEncode(document: document), to: folder.appendingPathComponent("document.json"))
            let base = entry.deleted ? trash : root
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
            additions.append((folder, base.appendingPathComponent(name)))
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

    /// Exactly `count` bytes; a file that ends early is not a backup.
    private func exact(_ handle: FileHandle, count: Int) throws -> Data {
        do { return try ScanFiles.read(handle, count: count) } catch ScanFileError.shortRead { throw ScanError.InvalidDocument }
    }

    /// Copies one page and returns the core's CRC-32 of its bytes.
    private func stream(_ input: FileHandle, to output: FileHandle, count: Int) throws -> UInt32 {
        var checksum: UInt32 = 0
        do { try ScanFiles.copy(input, to: output, count: count) { checksum = crc32Continue(checksum: checksum, data: $0) } }
        catch ScanFileError.shortRead { throw ScanError.InvalidDocument }
        return checksum
    }
}
