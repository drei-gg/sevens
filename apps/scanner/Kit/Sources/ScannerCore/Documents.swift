import Foundation

public struct ScanPage: Codable, Identifiable, Equatable, Sendable {
    public var id: UUID
    public var text: String
    public var adjustments: PageSettings?
    public var settings: PageSettings { adjustments ?? PageSettings() }
    public init(id: UUID = UUID(), text: String = "") { self.id = id; self.text = text }
}
public struct ScanDocument: Codable, Identifiable, Equatable, Sendable {
    public var version = 1
    public var id: UUID
    public var title: String
    public var created: Date
    public var pages: [ScanPage]
    public var note: String?
    public var favorite: Bool?
    public init(id: UUID = UUID(), title: String, pages: [ScanPage], created: Date = Date()) {
        self.id = id; self.title = title; self.pages = pages; self.created = created
    }
    public func validate() throws {
        guard version == 1, !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              title.count <= 200, !pages.isEmpty, pages.count <= 50,
              Set(pages.map(\.id)).count == pages.count,
              pages.allSatisfy({ $0.text.count <= 100_000 }), (note?.count ?? 0) <= 10_000, created.timeIntervalSince1970.isFinite else { throw ScanError.invalidDocument }
        for page in pages { try page.settings.validate() }
    }
    public func matches(_ query: String) -> Bool {
        query.isEmpty || ([title, note ?? ""] + pages.map(\.text)).joined(separator: "\n").localizedStandardContains(query)
    }
}
public enum ScanError: Error, Sendable { case invalidDocument, tooLarge, missingPage }

/// Manifests commit atomically after immutable page files. A failed creation removes only its own staging directory.
public actor DocumentStore {
    public let root: URL
    public init(root: URL) { self.root = root }
    public func list(deleted: Bool = false) throws -> [ScanDocument] {
        let base = deleted ? trash : root
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        let listing = try readLibrary(deleted: deleted)
        guard listing.unreadableCount == 0 else { throw ScanError.invalidDocument }
        return listing.documents
    }
    public func readLibrary(deleted: Bool = false) throws -> LibraryListing {
        let base = deleted ? trash : root
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var documents: [ScanDocument] = []; var failures = 0
        for folder in try FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil) where UUID(uuidString: folder.lastPathComponent) != nil {
            do {
                let url = folder.appendingPathComponent("document.json")
                guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 6_000_000 else { throw ScanError.tooLarge }
                let doc = try JSONDecoder().decode(ScanDocument.self, from: Data(contentsOf: url))
                try doc.validate()
                guard doc.id.uuidString == folder.lastPathComponent else { throw ScanError.invalidDocument }
                documents.append(doc)
            } catch { failures += 1 }
        }
        return LibraryListing(documents: LibraryOrder.newest.sorted(documents), unreadableCount: failures)
    }
    public func create(title: String, images: [Data], text: [String]) throws -> ScanDocument {
        guard !images.isEmpty, images.count <= 50, images.count == text.count,
              images.allSatisfy({ !$0.isEmpty && $0.count <= 20_000_000 }),
              images.reduce(0, { $0 + $1.count }) <= 100_000_000 else { throw ScanError.tooLarge }
        let doc = ScanDocument(title: title, pages: text.map { ScanPage(text: $0) })
        try doc.validate()
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let staging = root.appendingPathComponent(".pending-" + doc.id.uuidString)
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
        do {
            for (index, page) in doc.pages.enumerated() { try write(images[index], to: staging.appendingPathComponent(page.id.uuidString + ".jpg")) }
            try write(JSONEncoder().encode(doc), to: staging.appendingPathComponent("document.json"))
            try FileManager.default.moveItem(at: staging, to: folder(doc.id))
            return doc
        } catch { try? FileManager.default.removeItem(at: staging); throw error }
    }
    public func save(_ document: ScanDocument) throws {
        try document.validate()
        for page in document.pages where !FileManager.default.fileExists(atPath: imageURL(document.id, page.id).path) { throw ScanError.missingPage }
        try write(JSONEncoder().encode(document), to: folder(document.id).appendingPathComponent("document.json"))
    }
    public func images(_ document: ScanDocument) throws -> [Data] {
        try document.pages.map { page in
            let url = imageURL(document.id, page.id)
            guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 20_000_000 else { throw ScanError.tooLarge }
            return try Data(contentsOf: url)
        }
    }
    public func pageURLs(_ document: ScanDocument) throws -> [URL] {
        try document.validate()
        return try document.pages.map { page in
            let url = imageURL(document.id, page.id)
            guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 20_000_000 else { throw ScanError.tooLarge }
            return url
        }
    }
    public func updatePage(documentID: UUID, pageID: UUID, settings: PageSettings) throws -> ScanDocument {
        try settings.validate()
        var current = try load(documentID)
        guard let index = current.pages.firstIndex(where: { $0.id == pageID }) else { throw ScanError.missingPage }
        current.pages[index].adjustments = settings
        current.pages[index].text = ""
        try save(current)
        return current
    }
    public func append(documentID: UUID, images: [Data]) throws -> ScanDocument {
        var current = try load(documentID)
        guard !images.isEmpty, current.pages.count + images.count <= 50,
              images.allSatisfy({ !$0.isEmpty && $0.count <= 20_000_000 }) else { throw ScanError.tooLarge }
        let oldSize = try pageURLs(current).reduce(0) { $0 + ((try $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        guard images.reduce(oldSize, { $0 + $1.count }) <= 100_000_000 else { throw ScanError.tooLarge }
        let pages = images.map { _ in ScanPage() }
        do {
            for (page, data) in zip(pages, images) { try write(data, to: imageURL(current.id, page.id)) }
            current.pages += pages
            try save(current)
            return current
        } catch {
            for page in pages { try? FileManager.default.removeItem(at: imageURL(current.id, page.id)) }
            throw error
        }
    }
    public func setPageOrder(documentID: UUID, pageIDs: [UUID], expectedPageIDs: [UUID]? = nil) throws -> ScanDocument {
        var current = try load(documentID)
        guard expectedPageIDs == nil || Set(expectedPageIDs!) == Set(current.pages.map(\.id)),
              !pageIDs.isEmpty, Set(pageIDs).count == pageIDs.count,
              Set(pageIDs).isSubset(of: Set(current.pages.map(\.id))) else { throw ScanError.invalidDocument }
        let byID = Dictionary(uniqueKeysWithValues: current.pages.map { ($0.id, $0) })
        current.pages = pageIDs.compactMap { byID[$0] }
        try save(current); return current
    }
    public func updateMetadata(documentID: UUID, title: String, note: String, favorite: Bool) throws -> ScanDocument {
        var current = try load(documentID)
        current.title = title; current.note = note; current.favorite = favorite
        try save(current); return current
    }
    private func load(_ id: UUID) throws -> ScanDocument {
        let doc = try JSONDecoder().decode(ScanDocument.self, from: Data(contentsOf: folder(id).appendingPathComponent("document.json")))
        try doc.validate(); guard doc.id == id else { throw ScanError.invalidDocument }; return doc
    }
    /// Moving the whole folder preserves every page and manifest together; no silent overwrite on restore.
    public func delete(_ document: ScanDocument) throws {
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        try FileManager.default.moveItem(at: folder(document.id), to: trash.appendingPathComponent(document.id.uuidString))
    }
    public func restore(_ document: ScanDocument) throws {
        try FileManager.default.moveItem(at: trash.appendingPathComponent(document.id.uuidString), to: folder(document.id))
    }
    public func permanentlyDelete(_ document: ScanDocument) throws {
        try FileManager.default.removeItem(at: trash.appendingPathComponent(document.id.uuidString))
    }
    /// OCR completion must not overwrite a rename/reorder performed while recognition ran.
    public func fillRecognizedText(documentID: UUID, byPage: [UUID: String], expectedSettings: [UUID: PageSettings]? = nil, overwrite: Bool = false) throws -> ScanDocument {
        let url = folder(documentID).appendingPathComponent("document.json")
        var current = try JSONDecoder().decode(ScanDocument.self, from: Data(contentsOf: url))
        for index in current.pages.indices where current.pages[index].text.isEmpty || overwrite {
            if let text = byPage[current.pages[index].id], expectedSettings == nil || expectedSettings?[current.pages[index].id] == current.pages[index].settings { current.pages[index].text = text }
        }
        try save(current)
        return current
    }
    private var trash: URL { root.appendingPathComponent(".trash", isDirectory: true) }
    private func folder(_ id: UUID) -> URL { root.appendingPathComponent(id.uuidString, isDirectory: true) }
    private func imageURL(_ doc: UUID, _ page: UUID) -> URL { folder(doc).appendingPathComponent(page.uuidString + ".jpg") }
    private func write(_ data: Data, to url: URL) throws {
        if url.lastPathComponent == "document.json", data.count > 6_000_000 { throw ScanError.tooLarge }
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        #else
        try data.write(to: url, options: .atomic)
        #endif
    }
}
