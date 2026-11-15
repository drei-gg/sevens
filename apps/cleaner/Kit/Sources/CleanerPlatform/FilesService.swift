#if canImport(CryptoKit)
import Foundation
import CryptoKit
import CleanerCore

public struct FolderScan: Sendable { public var items: [CleanupItem]; public var groups: [MatchGroup]; public var skipped: Int
    public init(items: [CleanupItem], groups: [MatchGroup], skipped: Int) { self.items = items; self.groups = groups; self.skipped = skipped }
}
public actor FilesService {
    public init() {}
    public func scan(_ folder: URL, hash: Bool) throws -> FolderScan {
        let access = folder.startAccessingSecurityScopedResource(); defer { if access { folder.stopAccessingSecurityScopedResource() } }
        let keys: [URLResourceKey] = [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey, .contentModificationDateKey, .isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey]
        guard let enumerator = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles, .skipsPackageDescendants]) else { throw CleanerError.blocked("This folder cannot be read.") }
        var items: [CleanupItem] = []; var skipped = 0
        for case let url as URL in enumerator {
            try Task.checkCancellation()
            do {
                let value = try url.resourceValues(forKeys: Set(keys))
                guard value.isRegularFile == true, value.isSymbolicLink != true else { continue }
                var item = CleanupItem(id: url.absoluteString, name: String(url.path.dropFirst(folder.path.count + 1)), kind: .file,
                    revision: fileRevision(modifiedSince1970: value.contentModificationDate?.timeIntervalSince1970, fileSize: value.fileSize.map(Int64.init)), bytes: value.fileSize.map(Int64.init), date: value.contentModificationDate)
                if hash && !(value.isUbiquitousItem == true && value.ubiquitousItemDownloadingStatus != .current) {
                    var coordinationError: NSError?; var readError: Error?; var digest: String?
                    NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { coordinated in
                        do { digest = try FileDigest.sha256(coordinated) } catch { readError = error }
                    }
                    if let error = coordinationError ?? (readError as NSError?) { throw error }
                    let after = try url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
                    if after.contentModificationDate == value.contentModificationDate && after.fileSize == value.fileSize { item.fingerprint = digest }
                    else { skipped += 1 }
                }
                items.append(item)
            } catch is CancellationError { throw CancellationError() }
            catch { skipped += 1 }
        }
        items = sortLargestFirst(items: items)
        return FolderScan(items: items, groups: Matching.exact(items), skipped: skipped)
    }
}
#endif
