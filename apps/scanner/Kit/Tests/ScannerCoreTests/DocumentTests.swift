import Testing
import Foundation
@testable import ScannerCore
@Test func libraryPersistsReordersSearchesAndDeletes() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    var doc = try await store.create(title: "Receipt", images: [Data([1]), Data([2])], text: ["Coffee", "Total 120"])
    #expect(doc.matches("coffee"))
    doc.title = "Renamed"; doc.pages.reverse()
    try await store.save(doc)
    let reopened = DocumentStore(root: root)
    #expect(try await reopened.list() == [doc])
    #expect(try await reopened.images(doc) == [Data([2]), Data([1])])
    try await reopened.delete(doc)
    #expect(try await reopened.list().isEmpty)
}
@Test func invalidSaveKeepsOriginal() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let original = try await store.create(title: "Keep", images: [Data([1])], text: [""])
    var invalid = original; invalid.pages = []
    await #expect(throws: ScanError.self) { try await store.save(invalid) }
    #expect(try await store.list() == [original])
    invalid = original; invalid.pages = [ScanPage()]
    let missing = invalid
    await #expect(throws: ScanError.self) { try await store.save(missing) }
    #expect(try await store.list() == [original])
}

@Test func trashRestoreAndPermanentDeletePreservePages() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let doc = try await store.create(title: "Keep me", images: [Data([1, 2, 3])], text: ["Original"])
    try await store.delete(doc)
    #expect(try await store.list().isEmpty)
    #expect(try await store.list(deleted: true) == [doc])
    // Recently Deleted's thumbnails read the page from the trash folder.
    #expect(try Data(contentsOf: try await store.pageURLs(doc, deleted: true)[0]) == Data([1, 2, 3]))
    let reopened = DocumentStore(root: root)
    try await reopened.restore(doc)
    #expect(try await reopened.images(doc) == [Data([1, 2, 3])])
    #expect(try await reopened.list(deleted: true).isEmpty)
    try await reopened.delete(doc)
    try await reopened.permanentlyDelete(doc)
    #expect(try await reopened.list(deleted: true).isEmpty)
}

@Test func delayedOCRPreservesRenameOrderAndExistingText() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    var doc = try await store.create(title: "Before", images: [Data([1]), Data([2])], text: ["", "Manual correction"])
    let originalPages = doc.pages
    doc.title = "After"; doc.pages.reverse(); try await store.save(doc)
    let updated = try await store.fillRecognizedText(documentID: doc.id, byPage: [originalPages[0].id: "OCR first", originalPages[1].id: "OCR second"])
    #expect(updated.title == "After")
    #expect(updated.pages.map(\.id) == doc.pages.map(\.id))
    #expect(updated.pages.map(\.text) == ["Manual correction", "OCR first"])
    try await store.delete(updated)
    await #expect(throws: (any Error).self) { try await store.fillRecognizedText(documentID: doc.id, byPage: [:]) }
    #expect(try await store.list().isEmpty)
}

@Test func removingAPageDeletesItsImageOnlyAfterTheManifestCommits() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let doc = try await store.create(title: "Two pages", images: [Data([1]), Data([2])], text: ["", ""])
    let folder = root.appendingPathComponent(doc.id.uuidString)
    let kept = try await store.setPageOrder(documentID: doc.id, pageIDs: [doc.pages[1].id])
    let files = try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
    #expect(files == ["document.json", doc.pages[1].id.uuidString + ".jpg"].sorted())
    #expect(try await store.images(kept) == [Data([2])])
}
@Test func cleanUpRemovesStagingAndOldOrphansButNotUnreadableDocuments() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let doc = try await store.create(title: "Keep", images: [Data([1])], text: [""])
    let fm = FileManager.default
    try fm.createDirectory(at: root.appendingPathComponent(".pending-" + UUID().uuidString), withIntermediateDirectories: true)
    try fm.createDirectory(at: root.appendingPathComponent(".restore-" + UUID().uuidString), withIntermediateDirectories: true)
    let orphan = root.appendingPathComponent(doc.id.uuidString).appendingPathComponent(UUID().uuidString + ".jpg")
    try Data([9]).write(to: orphan)
    let broken = root.appendingPathComponent(UUID().uuidString)
    try fm.createDirectory(at: broken, withIntermediateDirectories: true)
    try Data("not json".utf8).write(to: broken.appendingPathComponent("document.json"))
    let stray = broken.appendingPathComponent(UUID().uuidString + ".jpg"); try Data([7]).write(to: stray)
    #expect(try await store.cleanUp() == 3)
    #expect(!fm.fileExists(atPath: orphan.path))
    #expect(fm.fileExists(atPath: stray.path))
    #expect(try await store.images(doc) == [Data([1])])
    #expect(try fm.contentsOfDirectory(atPath: root.path).filter { $0.hasPrefix(".pending-") || $0.hasPrefix(".restore-") }.isEmpty)
}
