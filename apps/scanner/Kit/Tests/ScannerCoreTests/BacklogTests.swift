import Testing
import Foundation
@testable import ScannerCore

@Test func correctedTextPersistsAndLateRecognitionCannotReplaceIt() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let original = try await store.create(title: "Receipt", images: [Data([1, 2, 3])], text: ["misread"])
    _ = try await store.updatePageText(documentID: original.id, expected: original.pages[0], text: "Correct total 42.00")
    let merged = try await store.fillRecognizedText(documentID: original.id, byPage: [original.pages[0].id: "late result"],
        overwrite: true, expectedPages: original.pages)
    #expect(merged.pages[0].text == "Correct total 42.00")
    #expect(try await store.images(merged) == [Data([1, 2, 3])])
    let reopened = DocumentStore(root: root)
    #expect(try await reopened.readLibrary().documents.first?.pages[0].text == "Correct total 42.00")
}

@Test func editsKeepOriginalAndRejectStaleRecognition() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let doc = try await store.create(title: "Old", images: [Data([1, 2])], text: ["Old text"])
    var settings = PageSettings(); settings.quarterTurns = 1; settings.brightness = 0.15; settings.grayscale = true
    _ = try await store.updatePage(documentID: doc.id, pageID: doc.pages[0].id, settings: settings)
    let merged = try await store.fillRecognizedText(documentID: doc.id, byPage: [doc.pages[0].id: "Stale OCR"], expectedSettings: [doc.pages[0].id: PageSettings()], overwrite: true)
    #expect(merged.pages[0].text.isEmpty)
    #expect(try await store.images(merged) == [Data([1, 2])])
    let named = try await store.updateMetadata(documentID: doc.id, title: "Edited", note: "Tax deduction", favorite: true)
    #expect(named.matches("deduction")); #expect(named.pages[0].settings == settings)
    settings.contrast = .infinity
    #expect(throws: ScanError.self) { try settings.validate() }
}
@Test func backupRoundTripAndCorruptionPreserveExistingLibrary() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: target) }
    let store = DocumentStore(root: root); let restored = DocumentStore(root: target)
    var doc = try await store.create(title: "Receipt", images: [Data(repeating: 17, count: 1_100_000), Data([2, 3])], text: ["Coffee", "Total"])
    var settings = PageSettings(); settings.grayscale = true
    doc = try await store.updatePage(documentID: doc.id, pageID: doc.pages[0].id, settings: settings)
    doc = try await store.updateMetadata(documentID: doc.id, title: doc.title, note: "Business", favorite: true)
    let deleted = try await store.create(title: "Trashed", images: [Data([9])], text: ["Gone"]); try await store.delete(deleted)
    let archive = root.appendingPathComponent("test.scannerbackup"); try await store.exportBackup(to: archive)
    #expect(try await restored.restoreBackup(from: archive) == 2)
    let copies = try await restored.list(); let trash = try await restored.list(deleted: true)
    #expect(copies.count == 1 && trash.count == 1)
    #expect(copies[0].id != doc.id && copies[0].note == "Business" && copies[0].favorite == true)
    #expect(copies[0].pages[0].settings == settings)
    #expect(try await restored.images(copies[0]) == store.images(doc))
    var bytes = try Data(contentsOf: archive); bytes[bytes.count - 5] ^= 0xff
    let corrupt = root.appendingPathComponent("corrupt.scannerbackup"); try bytes.write(to: corrupt)
    await #expect(throws: (any Error).self) { try await restored.restoreBackup(from: corrupt) }
    #expect(try await restored.list() == copies)
    #expect(try await restored.list(deleted: true) == trash)
    try bytes.dropLast(20).write(to: corrupt)
    await #expect(throws: (any Error).self) { try await restored.restoreBackup(from: corrupt) }
    #expect(try await restored.list() == copies)
}
@Test func fiftyPageBoundaryAndAppendAreAtomic() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let doc = try await store.create(title: "Long", images: Array(repeating: Data([1]), count: 49), text: Array(repeating: "", count: 49))
    let full = try await store.append(documentID: doc.id, images: [Data([2])])
    #expect(full.pages.count == 50)
    await #expect(throws: ScanError.self) { try await store.setPageOrder(documentID: doc.id, pageIDs: doc.pages.map(\.id), expectedPageIDs: doc.pages.map(\.id)) }
    #expect(try await store.list() == [full])
    await #expect(throws: ScanError.self) { try await store.append(documentID: doc.id, images: [Data([3])]) }
    #expect(try await store.list() == [full])
    let ordered = try await store.setPageOrder(documentID: doc.id, pageIDs: full.pages.reversed().map(\.id))
    #expect(try await store.images(ordered).first == Data([2]))
}
@Test func corruptDocumentDoesNotHideHealthyDocumentsOrSilentlyEnterBackup() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root) }
    let store = DocumentStore(root: root)
    let healthy = try await store.create(title: "Keep", images: [Data([1])], text: [""])
    let broken = root.appendingPathComponent(UUID().uuidString); try FileManager.default.createDirectory(at: broken, withIntermediateDirectories: false)
    try Data("broken".utf8).write(to: broken.appendingPathComponent("document.json"))
    let result = try await store.readLibrary()
    #expect(result.documents == [healthy]); #expect(result.unreadableCount == 1)
    await #expect(throws: (any Error).self) { try await store.exportBackup(to: root.appendingPathComponent("backup")) }
    #expect(FileManager.default.fileExists(atPath: broken.appendingPathComponent("document.json").path))
}

@Test func annotationsReopenBackupAndRejectStaleSaveWithoutChangingOriginals() async throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    let target = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: root); try? FileManager.default.removeItem(at: target) }
    let store = DocumentStore(root: root)
    let original = try await store.create(title: "Marked", images: [Data([1,2,3])], text: ["Corrected text"])
    let mark = PageAnnotation(bounds: PageCrop(left: 0.2, top: 0.1, right: 0.8, bottom: 0.3), style: .highlight)
    let ink = try inkFromDisplay(points: [InkPoint(x: 0.2, y: 0.55), InkPoint(x: 0.8, y: 0.55)], width: 8, displayWidth: 400, displayHeight: 600, settings: PageSettings(), color: .blue)
    let saved = try await store.updateAnnotations(documentID: original.id, expected: original.pages[0], annotations: [mark], ink: [ink])
    #expect(saved.version == 4 && saved.pages[0].text == "Corrected text")
    #expect(try await DocumentStore(root: root).list().first?.pages[0].annotations == [mark])
    await #expect(throws: ScanError.self) { try await store.updateAnnotations(documentID: original.id, expected: original.pages[0], annotations: []) }
    let archive = root.appendingPathComponent("marks.scannerbackup"); try await store.exportBackup(to: archive)
    let targetStore = DocumentStore(root: target)
    #expect(try await targetStore.restoreBackup(from: archive) == 1)
    let restored = try await targetStore.list()[0]
    #expect(restored.version == 4 && restored.pages[0].annotations == [mark])
    #expect(restored.pages[0].ink == [ink])
    #expect(try await targetStore.images(restored) == [Data([1,2,3])])
    var settings = PageSettings(); settings.quarterTurns = 1; settings.crop = PageCrop(left: 0.1, top: 0, right: 0.9, bottom: 1)
    let cropped = try await store.updatePage(documentID: saved.id, pageID: saved.pages[0].id, settings: settings)
    #expect(cropped.version == 4 && cropped.pages[0].annotations == [mark])
    #expect(cropped.pages[0].ink == [ink])
    #expect(try await store.images(cropped) == [Data([1,2,3])])
}
