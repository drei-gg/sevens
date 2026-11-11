import SwiftUI
import SevenKit
import ScannerCore

@Observable @MainActor final class ScannerModel {
    let store: Store
    let repository: DocumentStore
    var documents: [ScanDocument] = []
    var deletedDocuments: [ScanDocument] = []
    var unreadableCount = 0
    var progress = ""
    var busy = false
    var error: String?
    var started = false
    init() {
        let config = SevenConfig(bundle: .main) ?? SevenConfig(trialProductID: "scanner.trial7", unlockProductID: "scanner.unlock")
        var folder = "Scanner"
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-scanner.ui") {
            folder = "ScannerUITests"
            let defaults = UserDefaults(suiteName: "Scanner.UITests")!
            let source = LocalSource(defaults: defaults); source.apply(.unlocked)
            store = Store(config: config, source: source, defaults: defaults)
            store.markBeforeYouBuyShown(); store.markTrialEndedShown()
        } else { store = Store(config: config) }
        #else
        store = Store(config: config)
        #endif
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(folder)
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-scanner.reset") { try? FileManager.default.removeItem(at: root) }
        #endif
        repository = DocumentStore(root: root)
    }
    func start() async {
        guard !started else { return }; started = true; DocumentProcessing.cleanOldExports()
        _ = try? await repository.cleanUp()
        await reload(); await store.start()
    }
    func reload() async {
        do {
            let active = try await repository.readLibrary(); let deleted = try await repository.readLibrary(deleted: true)
            documents = active.documents; deletedDocuments = deleted.documents
            unreadableCount = active.unreadableCount + deleted.unreadableCount
        } catch { self.error = error.localizedDescription }
    }
    func create(_ images: [UIImage], appendTo: UUID? = nil) async {
        guard !busy else { return }
        // Adding pages is new scanning, so it needs access like a new document. Editing existing pages stays free.
        guard store.paidFeaturesAvailable else { error = String(localized: "Start a trial or unlock to scan or import pages. Your saved documents remain available."); return }
        busy = true; progress = String(localized: "Preparing pages…"); defer { busy = false; progress = "" }
        do {
            guard !images.isEmpty, images.count <= 50 else { throw ScanError.tooLarge }
            // Normalizing up to 50 camera pages is heavy; keep it off the main actor so the UI stays responsive.
            let pages = CapturedPages(images: images)
            let data = try await Task.detached(priority: .userInitiated) { () throws -> [Data] in
                var data: [Data] = []; var total = 0
                for image in pages.images {
                    let bytes = try autoreleasepool { try DocumentProcessing.normalized(image) }
                    total += bytes.count; guard total <= 100_000_000 else { throw ScanError.tooLarge }; data.append(bytes)
                }
                return data
            }.value
            try await persist(data, appendTo: appendTo)
        } catch { self.error = error.localizedDescription }
    }
    func importFiles(_ urls: [URL], appendTo: UUID? = nil) async {
        guard !busy else { return }
        guard store.paidFeaturesAvailable else { error = String(localized: "Start a trial or unlock to scan or import pages. Your saved documents remain available."); return }
        busy = true; progress = String(localized: "Importing pages…"); defer { busy = false; progress = "" }
        do {
            let data = try await Task.detached { try DocumentProcessing.importFiles(urls) }.value
            try await persist(data, appendTo: appendTo)
        } catch { self.error = error.localizedDescription }
    }
    private func persist(_ data: [Data], appendTo: UUID?) async throws {
        progress = String(localized: "Saving document…")
        let saved: ScanDocument
        if let appendTo { saved = try await repository.append(documentID: appendTo, images: data) }
        else { saved = try await repository.create(title: String(localized: "Scan") + " " + Date().formatted(date: .abbreviated, time: .shortened), images: data, text: Array(repeating: "", count: data.count)) }
        await reload(); progress = String(localized: "Scan saved. Recognizing text…")
        await recognize(saved, onlyEmpty: true)
    }
    func recognize(_ document: ScanDocument, onlyEmpty: Bool = false) async {
        do {
            var selection = document
            if onlyEmpty { selection.pages = selection.pages.filter { $0.text.isEmpty } }
            guard !selection.pages.isEmpty else { return }
            let urls = try await repository.pageURLs(selection); let pages = selection.pages
            let text = try await Task.detached { try DocumentProcessing.recognize(urls: urls, pages: pages) }.value
            _ = try await repository.fillRecognizedText(documentID: document.id, byPage: text,
                expectedSettings: Dictionary(uniqueKeysWithValues: pages.map { ($0.id, $0.settings) }), overwrite: !onlyEmpty)
            await reload()
        } catch {
            if documents.contains(where: { $0.id == document.id }) { self.error = String(localized: "Your scan is saved. Text recognition could not finish. You can retry from the document.") }
        }
    }
    func save(_ document: ScanDocument) async -> Bool {
        do { try await repository.save(document); await reload(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func delete(_ document: ScanDocument) async -> Bool {
        do { try await repository.delete(document); await reload(); return true }
        catch { self.error = error.localizedDescription; return false }
    }
    func restore(_ document: ScanDocument) async {
        do { try await repository.restore(document); await reload() }
        catch { self.error = error.localizedDescription }
    }
    func permanentlyDelete(_ document: ScanDocument) async {
        do { try await repository.permanentlyDelete(document); await reload() }
        catch { self.error = error.localizedDescription }
    }
    #if DEBUG
    func fixture() async {
        let images = (1...2).map { page in
            UIGraphicsImageRenderer(size: CGSize(width: 600, height: 800)).image { context in
                UIColor.white.setFill(); context.fill(CGRect(x: 0, y: 0, width: 600, height: 800))
                ("SEVENS SCANNER\nReceipt page \(page)\nCoffee 120\nTotal 120" as NSString).draw(in: CGRect(x: 50, y: 60, width: 500, height: 600), withAttributes: [.font: UIFont.systemFont(ofSize: 32), .foregroundColor: UIColor.black])
            }
        }
        await create(images)
    }
    #endif
}

/// Camera output is immutable once captured; it is only read while normalizing.
struct CapturedPages: @unchecked Sendable { let images: [UIImage] }

enum ScannerGate {
    static let unlock = UnlockContent(appName: "Document Scanner", features: ["Scan and import documents", "A private searchable library", "Page management and PDF export"])
    static let before = BeforeYouBuyContent(title: "Your iPhone already scans documents.", paragraphs: [
        .init("Notes and Files can scan paper into PDFs. If that covers your needs, keep your money."),
        .init("This adds a dedicated searchable library, recognized text and page management. Try everything for 7 days, then {price}, once. Nothing renews.", withoutPrice: "This adds a dedicated searchable library, recognized text and page management. Try everything for 7 days, then a one-time purchase. Nothing renews."),
        .init("Your saved documents remain readable, editable and exportable after the trial.")], justScanLabel: "Look around first")
    static let expired = TrialEndedContent(stillFree: ["Read, edit, delete and export saved documents"], unlockKeeps: ["Scan and import new documents"])
}
