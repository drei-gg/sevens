import Foundation
import CryptoKit

public struct BackupStatus: Codable, Equatable, Sendable {
    public let target: UUID
    public var destination: String
    public var copiedAt: Date?
    public var copiedRevision: String?
    public var pendingRevision: String?
    public var failure: String?
    public var nextAttempt: Date?
}
private struct BackupRecord: Codable {
    var status: BackupStatus
    var bookmark: Data
    var pendingFile: String?
    var failures = 0
    var delivered: [String] = []
}
public enum BackupFailure: Error, LocalizedError {
    case destination, invalidArtifact, changedCopy
    public var errorDescription: String? {
        switch self {
        case .destination: "Choose the backup folder again. Access may have changed."
        case .invalidArtifact: "The prepared backup could not be read. Open the vault to prepare it again."
        case .changedCopy: "The copied backup did not match. The previous backup has been kept."
        }
    }
}
public protocol BackupFolderWriter: Sendable {
    func write(source: URL, directory: URL, filename: String) throws
    func remove(directory: URL, filename: String) throws
}
public struct CoordinatedBackupWriter: BackupFolderWriter {
    public init() {}
    public func write(source: URL, directory: URL, filename: String) throws {
        var coordinationError: NSError?
        var operationError: (any Error)?
        let output = directory.appendingPathComponent(filename)
        NSFileCoordinator().coordinate(writingItemAt: output, options: [], error: &coordinationError) { coordinated in
            do {
                try Task.checkCancellation()
                if FileManager.default.fileExists(atPath: coordinated.path) {
                    guard try Self.digest(source) == Self.digest(coordinated) else { throw BackupFailure.changedCopy }
                    return
                }
                let partial = coordinated.deletingLastPathComponent().appendingPathComponent(".backup-" + UUID().uuidString)
                defer { try? FileManager.default.removeItem(at: partial) }
                guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { throw BackupFailure.destination }
                let input = try FileHandle(forReadingFrom: source), output = try FileHandle(forWritingTo: partial)
                defer { try? input.close(); try? output.close() }
                while let data = try input.read(upToCount: 1_048_576), !data.isEmpty {
                    try Task.checkCancellation(); try output.write(contentsOf: data)
                }
                try output.synchronize(); try output.close()
                guard try Self.digest(source) == Self.digest(partial) else { throw BackupFailure.changedCopy }
                try Task.checkCancellation()
                try FileManager.default.moveItem(at: partial, to: coordinated)
            } catch { operationError = error }
        }
        if let error = coordinationError ?? operationError as NSError? { throw error }
    }
    public func remove(directory: URL, filename: String) throws {
        var coordinationError: NSError?, operationError: (any Error)?
        NSFileCoordinator().coordinate(writingItemAt: directory.appendingPathComponent(filename), options: .forDeleting, error: &coordinationError) { url in
            do { if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) } }
            catch { operationError = error }
        }
        if let error = coordinationError ?? operationError as NSError? { throw error }
    }
    private static func digest(_ url: URL) throws -> Data {
        let input = try FileHandle(forReadingFrom: url); defer { try? input.close() }; var hash = SHA256()
        while let data = try input.read(upToCount: 1_048_576), !data.isEmpty { try Task.checkCancellation(); hash.update(data: data) }
        return Data(hash.finalize())
    }
}

/// Durable delivery of already prepared artifacts. This layer never handles encryption keys.
/// A provider accepting a file is not confirmation that a remote cloud upload finished.
public actor AutomaticBackup {
    public let root: URL
    private let writer: any BackupFolderWriter
    public init(root: URL, writer: any BackupFolderWriter = CoordinatedBackupWriter()) { self.root = root; self.writer = writer }
    private var stateURL: URL { root.appendingPathComponent("destinations.json") }
    private func records() throws -> [BackupRecord] {
        guard FileManager.default.fileExists(atPath: stateURL.path) else { return [] }
        return try JSONDecoder().decode([BackupRecord].self, from: Data(contentsOf: stateURL))
    }
    private func save(_ records: [BackupRecord]) throws {
        try Self.protectDirectory(root)
        let data = try JSONEncoder().encode(records)
        #if os(iOS)
        try data.write(to: stateURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: stateURL, options: .atomic)
        #endif
    }
    public static func protectDirectory(_ url: URL) throws {
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
        #endif
        var resource = url, values = URLResourceValues(); values.isExcludedFromBackup = true; try resource.setResourceValues(values)
    }
    /// Call once at launch before producers start. Interrupted, uncommitted artifacts are disposable.
    public func prepare() throws {
        let all = try records(); try Self.protectDirectory(root)
        let retained = Set(all.compactMap(\.pendingFile))
        for url in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) {
            if url.lastPathComponent == "preparing" || (UUID(uuidString: String(url.lastPathComponent.prefix(36))) != nil && !retained.contains(url.lastPathComponent)) {
                try FileManager.default.removeItem(at: url)
            }
        }
    }
    public func configure(target: UUID, directory: URL) throws {
        let access = directory.startAccessingSecurityScopedResource(); defer { if access { directory.stopAccessingSecurityScopedResource() } }
        guard (try directory.resourceValues(forKeys: [.isDirectoryKey])).isDirectory == true else { throw BackupFailure.destination }
        #if os(macOS)
        let bookmark = try directory.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
        #else
        let bookmark = try directory.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
        #endif
        var all = try records(); let old = all.first { $0.status.target == target }; all.removeAll { $0.status.target == target }
        all.append(BackupRecord(status: BackupStatus(target: target, destination: directory.lastPathComponent), bookmark: bookmark))
        try save(all)
        if let file = old?.pendingFile { try? FileManager.default.removeItem(at: artifact(file)) }
    }
    public func disable(target: UUID) throws {
        var all = try records(); let old = all.first { $0.status.target == target }; all.removeAll { $0.status.target == target }; try save(all)
        if let file = old?.pendingFile { try? FileManager.default.removeItem(at: artifact(file)) }
    }
    public func status(target: UUID) throws -> BackupStatus? { try records().first { $0.status.target == target }?.status }
    public func hasPending() throws -> Bool { try records().contains { $0.pendingFile != nil } }
    public func needsPreparation(target: UUID, revision: String) throws -> Bool {
        guard let record = try records().first(where: { $0.status.target == target }) else { return false }
        return record.status.pendingRevision != revision && record.status.copiedRevision != revision
    }
    public func enqueue(source: URL, target: UUID, revision: String, fileExtension: String) throws {
        guard revision.count == 64, revision.allSatisfy(\.isHexDigit), !fileExtension.isEmpty, fileExtension.utf8.allSatisfy({ (97...122).contains($0) || (48...57).contains($0) }) else { throw BackupFailure.invalidArtifact }
        var all = try records(); guard let index = all.firstIndex(where: { $0.status.target == target }) else { return }
        try Self.protectDirectory(root)
        let name = UUID().uuidString + "." + fileExtension, output = root.appendingPathComponent(name)
        try FileManager.default.copyItem(at: source, to: output)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: output.path)
        #endif
        let old = all[index].pendingFile
        all[index].pendingFile = name; all[index].status.pendingRevision = revision
        all[index].status.failure = nil; all[index].status.nextAttempt = nil; all[index].failures = 0
        do { try save(all) } catch { try? FileManager.default.removeItem(at: output); throw error }
        if let old { try? FileManager.default.removeItem(at: artifact(old)) }
    }
    public func deliverPending(now: Date = Date(), force: Bool = false) throws {
        var all = try records()
        for index in all.indices {
            guard let file = all[index].pendingFile, let revision = all[index].status.pendingRevision,
                  force || (all[index].status.nextAttempt ?? .distantPast) <= now else { continue }
            try Task.checkCancellation()
            do {
                var stale = false
                #if os(macOS)
                let directory = try URL(resolvingBookmarkData: all[index].bookmark, options: [.withSecurityScope, .withoutUI], relativeTo: nil, bookmarkDataIsStale: &stale)
                #else
                let directory = try URL(resolvingBookmarkData: all[index].bookmark, options: .withoutUI, relativeTo: nil, bookmarkDataIsStale: &stale)
                #endif
                guard !stale else { throw BackupFailure.destination }
                let access = directory.startAccessingSecurityScopedResource(); defer { if access { directory.stopAccessingSecurityScopedResource() } }
                let filename = all[index].status.target.uuidString + "-" + revision + "." + URL(fileURLWithPath: file).pathExtension
                try writer.write(source: artifact(file), directory: directory, filename: filename)
                // Retain three successfully copied versions from this queue. Never delete unrelated files.
                let previous = all[index].delivered.filter { $0 != filename }
                all[index].delivered = Array(([filename] + previous).prefix(3))
                all[index].status.copiedRevision = revision; all[index].status.copiedAt = now
                all[index].status.pendingRevision = nil; all[index].status.failure = nil; all[index].status.nextAttempt = nil
                all[index].pendingFile = nil; all[index].failures = 0
                try save(all)
                try? FileManager.default.removeItem(at: artifact(file))
                for old in previous.dropFirst(2) { try? writer.remove(directory: directory, filename: old) }
            } catch is CancellationError { throw CancellationError() }
            catch {
                // Keep the artifact and previous receipt on every failed delivery.
                // Reload in case persisting the success receipt itself failed.
                all = try records()
                all[index].failures += 1; all[index].status.failure = error.localizedDescription
                all[index].status.nextAttempt = now.addingTimeInterval(min(3600, 30 * pow(2, Double(min(all[index].failures, 7)))))
                try save(all)
            }
        }
    }
    private func artifact(_ name: String) throws -> URL {
        guard name == URL(fileURLWithPath: name).lastPathComponent, UUID(uuidString: String(name.prefix(36))) != nil else { throw BackupFailure.invalidArtifact }
        return root.appendingPathComponent(name)
    }
}
