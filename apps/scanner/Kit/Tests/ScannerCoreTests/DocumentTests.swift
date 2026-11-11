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

@Test func exportSelectionPreservesOrderAndUsesPortableName() throws {
    let pages = [ScanPage(), ScanPage(), ScanPage()]
    let doc = ScanDocument(title: "../Receipts: November/2026", pages: pages)
    let plan = try PDFExportPlan(document: doc, selected: [pages[2].id, pages[0].id])
    #expect(plan.pages.map(\.id) == [pages[0].id, pages[2].id])
    #expect(!plan.filename.contains("/"))
    #expect(!plan.filename.contains(":"))
    #expect(plan.filename.hasSuffix(" - selected pages.pdf"))
    #expect(throws: ScanError.self) { try PDFExportPlan(document: doc, selected: []) }
    #expect(throws: ScanError.self) { try PDFExportPlan(document: doc, selected: [UUID()]) }
}
