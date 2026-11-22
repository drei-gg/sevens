import Foundation
internal import ScannerFiles

/// The library on disk: one folder per document (`document.json` plus one JPEG per page), and
/// Recently Deleted in `.trash`. The store does the file I/O; the core decides every rule (what is
/// valid, the manifest bytes, the order, which folders count, which page images are left over).
/// Manifests commit atomically after immutable page files. A failed creation removes only its own
/// staging directory.
/// A capture whose save failed part-way: the first `written` pages are stored (in `documents`);
/// the rest were not written and are still the caller's to keep.
public struct PartialCapture: Error, LocalizedError {
    public let written: Int
    public let documents: [ScanDocument]
    public let underlying: any Error
    public var errorDescription: String? { underlying.localizedDescription }
}

public actor DocumentStore {
    public let root: URL
    private let limits = scanLimits()
    public init(root: URL) { self.root = root }

    public func list(deleted: Bool = false) throws -> [ScanDocument] {
        let listing = try readLibrary(deleted: deleted)
        guard listing.unreadableCount == 0 else { throw ScanError.InvalidDocument }
        return listing.documents
    }

    public func readLibrary(deleted: Bool = false) throws -> LibraryListing {
        let base = deleted ? trash : root
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var documents: [ScanDocument] = []; var failures: Int64 = 0
        for folder in try FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)
        where isDocumentFolder(name: folder.lastPathComponent) {
            do { documents.append(try readManifest(in: folder)) } catch { failures += 1 }
        }
        return libraryListing(documents: documents, unreadableCount: failures)
    }

    public func create(title: String, images: [Data], text: [String]) throws -> ScanDocument {
        let doc = try newDocumentFromScan(title: title, imageSizes: images.map { UInt64($0.count) }, text: text,
                                          created: Date().timeIntervalSinceReferenceDate)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let staging = root.appendingPathComponent(".pending-" + documentFolderName(documentId: doc.id))
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        do {
            for (index, page) in doc.pages.enumerated() {
                try ScanFiles.write(images[index], to: staging.appendingPathComponent(pageFileName(pageId: page.id)))
            }
            try ScanFiles.write(manifestEncode(document: doc), to: staging.appendingPathComponent("document.json"))
            try FileManager.default.moveItem(at: staging, to: folder(doc.id))
            return doc
        } catch { try? FileManager.default.removeItem(at: staging); throw error }
    }

    public func save(_ document: ScanDocument) throws {
        try document.validate()
        for page in document.pages where !FileManager.default.fileExists(atPath: imageURL(document.id, page.id).path) {
            throw ScanError.MissingPage
        }
        try ScanFiles.write(manifestEncode(document: document), to: folder(document.id).appendingPathComponent("document.json"))
        removeUnlistedPages(in: folder(document.id), keeping: document)
    }

    /// A removed page's image goes when the manifest that dropped it is committed, never before.
    private func removeUnlistedPages(in folder: URL, keeping document: ScanDocument) {
        guard let files = try? FileManager.default.contentsOfDirectory(atPath: folder.path) else { return }
        for name in unlistedPageFiles(document: document, fileNames: files) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
    }

    /// Launch housekeeping: staging folders left by an interrupted create or restore, and page images
    /// that an earlier build left behind after removing a page. Unreadable documents are never touched.
    @discardableResult public func cleanUp() throws -> Int {
        var removed = 0
        guard FileManager.default.fileExists(atPath: root.path) else { return 0 }
        for item in try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)
        where isStagingFolder(name: item.lastPathComponent) {
            try? FileManager.default.removeItem(at: item); removed += 1
        }
        for base in [root, trash] where FileManager.default.fileExists(atPath: base.path) {
            for folder in try FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)
            where isDocumentFolder(name: folder.lastPathComponent) {
                guard let data = try? Data(contentsOf: folder.appendingPathComponent("document.json")),
                      let doc = try? manifestRead(data: data, folderName: folder.lastPathComponent) else { continue }
                let before = (try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? 0
                removeUnlistedPages(in: folder, keeping: doc)
                removed += before - ((try? FileManager.default.contentsOfDirectory(atPath: folder.path).count) ?? before)
            }
        }
        return removed
    }

    public func images(_ document: ScanDocument) throws -> [Data] {
        try document.pages.map { page in
            let url = imageURL(document.id, page.id)
            guard try ScanFiles.size(url) <= limits.maxPageBytes else { throw ScanError.TooLarge }
            return try Data(contentsOf: url)
        }
    }

    /// Page image files in document order. `deleted` reads a document in Recently Deleted.
    public func pageURLs(_ document: ScanDocument, deleted: Bool = false) throws -> [URL] {
        try document.validate()
        let base = (deleted ? trash : root).appendingPathComponent(documentFolderName(documentId: document.id), isDirectory: true)
        return try document.pages.map { page in
            let url = base.appendingPathComponent(pageFileName(pageId: page.id))
            guard try ScanFiles.size(url) <= limits.maxPageBytes else { throw ScanError.TooLarge }
            return url
        }
    }

    /// New settings for one page; its recognized text is cleared, since it was read before the change.
    public func updatePage(documentID: UUID, pageID: UUID, settings: PageSettings) throws -> ScanDocument {
        try settings.validate()
        let current = try documentWithPageSettings(document: load(documentID), pageId: pageID, settings: settings)
        try save(current)
        return current
    }

    public func updateAnnotations(documentID: UUID, expected: ScanPage, annotations: [PageAnnotation], ink: [PageInk]? = nil) throws -> ScanDocument {
        let current = try documentWithPageMarks(document: load(documentID), expected: expected, annotations: annotations, ink: ink ?? expected.ink)
        try save(current)
        return current
    }

    public func updatePageText(documentID: UUID, expected: ScanPage, text: String) throws -> ScanDocument {
        let current = try documentWithPageText(document: load(documentID), expected: expected, text: text)
        try save(current)
        return current
    }

    /// A camera capture, by the core's plan: the pages that fit (by page count and image bytes) join
    /// `appendTo`, and the rest become new documents, each within both limits. Planned against the
    /// document as stored now, inside the store, so nothing in between can change what fits.
    public func addCapture(_ images: [Data], appendTo: UUID?, title: String) throws -> (plan: CapturePlan, documents: [ScanDocument]) {
        var existingPages: UInt64?, existingBytes: UInt64 = 0
        if let appendTo {
            let current = try load(appendTo)
            existingPages = UInt64(current.pages.count)
            existingBytes = UInt64(try pageURLs(current).reduce(0) { $0 + (try ScanFiles.size($1)) })
        }
        let plan = try capturePlan(existingPageCount: existingPages, existingBytes: existingBytes, imageSizes: images.map { UInt64($0.count) })
        var written = 0, saved: [ScanDocument] = []
        do {
            if let appendTo, plan.append > 0 {
                saved.append(try append(documentID: appendTo, images: Array(images[0..<Int(plan.append)])))
                written = Int(plan.append)
            }
            for count in plan.newDocuments.map({ Int($0) }) {
                saved.append(try create(title: title, images: Array(images[written..<written + count]), text: Array(repeating: "", count: count)))
                written += count
            }
        } catch { throw PartialCapture(written: written, documents: saved, underlying: error) }
        return (plan, saved)
    }

    public func append(documentID: UUID, images: [Data]) throws -> ScanDocument {
        var current = try load(documentID)
        let existing = try pageURLs(current).reduce(0) { $0 + (try ScanFiles.size($1)) }
        try checkAppendImages(existingPageCount: UInt64(current.pages.count), imageSizes: images.map { UInt64($0.count) },
                              existingBytes: UInt64(existing))
        let pages = images.map { _ in newScanPage(text: "") }
        do {
            for (page, data) in zip(pages, images) { try ScanFiles.write(data, to: imageURL(current.id, page.id)) }
            current.pages += pages
            try save(current)
            return current
        } catch {
            for page in pages { try? FileManager.default.removeItem(at: imageURL(current.id, page.id)) }
            throw error
        }
    }

    /// The listed pages in that order; unlisted pages are removed. `expectedPageIDs` refuses a
    /// reorder made on a stale copy.
    public func setPageOrder(documentID: UUID, pageIDs: [UUID], expectedPageIDs: [UUID]? = nil) throws -> ScanDocument {
        let current = try documentWithPageOrder(document: load(documentID), pageIds: pageIDs, expectedPageIds: expectedPageIDs)
        try save(current); return current
    }

    public func updateMetadata(documentID: UUID, title: String, note: String, favorite: Bool) throws -> ScanDocument {
        let current = documentWithMetadata(document: try load(documentID), title: title, note: note, favorite: favorite)
        try save(current); return current
    }

    /// Moving the whole folder preserves every page and manifest together; no silent overwrite on restore.
    public func delete(_ document: ScanDocument) throws {
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: folder(document.id), to: trashFolder(document.id))
    }
    public func restore(_ document: ScanDocument) throws {
        try FileManager.default.moveItem(at: trashFolder(document.id), to: folder(document.id))
    }
    public func permanentlyDelete(_ document: ScanDocument) throws {
        try FileManager.default.removeItem(at: trashFolder(document.id))
    }

    /// OCR completion must not overwrite a rename, reorder or edit performed while recognition ran.
    public func fillRecognizedText(documentID: UUID, byPage: [UUID: String], expectedSettings: [UUID: PageSettings]? = nil,
                                   overwrite: Bool = false, expectedPages: [ScanPage]? = nil) throws -> ScanDocument {
        let current = try documentDecode(data: Data(contentsOf: folder(documentID).appendingPathComponent("document.json")))
        let filled = expectedPages.map { documentApplyRecognition(document: current, expectedPages: $0, byPage: byPage, overwrite: overwrite) }
            ?? documentFillRecognizedText(document: current, byPage: byPage, expectedSettings: expectedSettings, overwrite: overwrite)
        try save(filled)
        return filled
    }

    // MARK: files

    public func load(_ id: UUID) throws -> ScanDocument {
        try manifestRead(data: Data(contentsOf: folder(id).appendingPathComponent("document.json")),
                         folderName: documentFolderName(documentId: id))
    }
    /// A library folder's manifest, refused unread when it is larger on disk than any manifest can be.
    func readManifest(in folder: URL) throws -> ScanDocument {
        let url = folder.appendingPathComponent("document.json")
        guard try ScanFiles.size(url) <= limits.maxManifestBytes else { throw ScanError.TooLarge }
        return try manifestRead(data: Data(contentsOf: url), folderName: folder.lastPathComponent)
    }
    var trash: URL { root.appendingPathComponent(".trash", isDirectory: true) }
    func folder(_ id: UUID) -> URL { root.appendingPathComponent(documentFolderName(documentId: id), isDirectory: true) }
    private func trashFolder(_ id: UUID) -> URL { trash.appendingPathComponent(documentFolderName(documentId: id), isDirectory: true) }
    func imageURL(_ doc: UUID, _ page: UUID) -> URL { folder(doc).appendingPathComponent(pageFileName(pageId: page)) }
}
