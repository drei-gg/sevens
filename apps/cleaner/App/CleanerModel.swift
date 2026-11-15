import Foundation
import SwiftUI
import SevenKit
import CleanerCore
import CleanerPlatform

@Observable @MainActor final class CleanerModel {
    let store: Store
    let repository: ReviewStore
    let photos = PhotosService()
    let contacts = ContactsService()
    let files = FilesService()
    var state = ReviewState()
    var media: [CleanupItem] = []
    var groups: [MatchGroup] = []
    var folderScan: FolderScan?
    var contactSnapshot: ContactSnapshot?
    var contactMatches: [ContactMatch] = []
    var limitedPhotos = false
    var selected: Set<String> = []
    var allowCloud = false
    var progress = ""
    var error: String?
    var busy = false
    var canCancel = false
    var ready = false
    var candidate: CompressionCandidate?
    var candidateSource: String?
    var exportURL: URL?
    private var job: Task<Void, Never>?
    private var started = false
    #if DEBUG
    var tourReady = false
    #endif
    init() {
        let config = SevenConfig(bundle: .main) ?? SevenConfig(trialProductID: "cleaner.trial7", unlockProductID: "cleaner.unlock")
        var folder = "Cleaner"
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") {
            folder = "CleanerUITests"
            let defaults = UserDefaults(suiteName: "Cleaner.UITests")!
            let source = LocalSource(defaults: defaults); source.apply(.unlocked)
            store = Store(config: config, source: source, defaults: defaults)
            store.markBeforeYouBuyShown(); store.markTrialEndedShown()
        } else { store = Store(config: config) }
        #else
        store = Store(config: config)
        #endif
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent(folder)
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && ProcessInfo.processInfo.arguments.contains("-cleaner.reset") { try? FileManager.default.removeItem(at: root) }
        #endif
        repository = ReviewStore(url: root.appendingPathComponent("review.json"))
    }
    func start() async {
        guard !started else { return }; started = true
        do { state = try await repository.read(); ready = true } catch { self.error = error.localizedDescription }
        await store.start()
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && ProcessInfo.processInfo.arguments.contains("-cleaner.seed") {
            media = [CleanupItem(id: "fixture-a", name: "Example landscape", kind: .photo, revision: "1", bytes: 9_000_000, width: 4000, height: 3000, favorite: true, fingerprint: "fixture"),CleanupItem(id: "fixture-b", name: "Example copy", kind: .photo, revision: "1", bytes: 9_000_000, width: 4000,height: 3000,fingerprint: "fixture"),CleanupItem(id: "fixture-video", name: "Example video", kind: .video, revision: "1", bytes: 120_000_000,duration: 42)]
            groups = Matching.exact(media)
        }
        if CleanerFixtures.active { await CleanerFixtures.seed(self); tourReady = true }
        #endif
    }
    func run(_ title: String, paid: Bool = true, cancellable: Bool = true, operation: @escaping @MainActor () async throws -> Void) {
        guard ready, !busy else { return }
        guard !paid || store.paidFeaturesAvailable else { error = "Start the free trial or unlock to run new scans. Your saved review decisions and exports remain available."; return }
        busy = true; canCancel = cancellable; progress = title
        job = Task {
            defer { busy = false; canCancel = false; progress = ""; job = nil }
            do { try await operation() }
            catch is CancellationError { progress = "Cancelled" }
            catch { self.error = error.localizedDescription }
        }
    }
    func cancel() { guard canCancel else { return }; job?.cancel(); progress = "Stopping after the current resource…" }
    func scanPhotos() { run("Reading accessible media…") {
        let inventory = try await self.photos.inventory()
        self.media = inventory.items; self.limitedPhotos = inventory.limited; self.groups = []; self.selected = []
    } }
    func analyze() { run("Reading original resource bytes…") {
        let result = try await self.photos.analyze(self.media, allowCloud: self.allowCloud) { done, total in
            await MainActor.run { self.progress = "Measured \(done) of \(total)" }
        }
        self.media = result.items; self.groups = result.groups
        self.state = try await self.repository.record("Compared \(result.items.count) media items; \(result.unavailable) unavailable or changed. Byte counts describe library resources, not local space freed.")
    } }
    func findSimilar() { run("Comparing nearby photos with Vision…") {
        let result = try await self.photos.similar(self.media, allowCloud: self.allowCloud)
        self.groups = self.groups.filter { $0.kind != .similar } + result
    } }
    func decide(_ ids: Set<String>, _ decision: ReviewDecision) async {
        guard ready else { return }
        do {
            let revisions = Dictionary(uniqueKeysWithValues: media.filter { ids.contains($0.id) }.map { ($0.id,$0.revision) })
            state = try await repository.decide(Dictionary(uniqueKeysWithValues: ids.map { ($0,decision) }), revisions: revisions)
        } catch { self.error = error.localizedDescription }
    }
    func smartSelect() { selected = Matching.proposedRemoval(groups: groups, items: media, decisions: state.decisions) }
    func deleteReviewed(_ ids: Set<String>) { run("Applying reviewed deletion…", cancellable: false) {
        try await self.photos.delete(ids: ids, expected: self.media, state: self.state)
        self.state = try await self.repository.record("Requested Photos deletion for \(ids.count) reviewed items. iCloud deletion syncs; Recently Deleted can retain their storage.")
        self.media.removeAll { ids.contains($0.id) }; self.groups = []; self.selected.subtract(ids)
    } }
    func compress(_ item: CleanupItem) { run("Preparing an original and checking a compressed candidate…") {
        let sourceKey = item.id + "|" + item.revision
        guard !self.state.processedSources.contains(sourceKey), !self.state.processedSources.contains("output:" + item.id) else { throw CleanerError.blocked("A compressed copy was already saved for this source revision.") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerCandidate-" + UUID().uuidString)
        do {
            let original = try await self.photos.originalStill(id: item.id, directory: directory, allowCloud: self.allowCloud)
            let task = Task.detached(priority: .userInitiated) { try ImageCompression.makeCandidate(sourceURL: original, directory: directory) }
            let output = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
            try Task.checkCancellation()
            self.candidate = output; self.candidateSource = item.id
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    } }
    func discardCandidate() {
        if let candidate { try? FileManager.default.removeItem(at: candidate.outputURL.deletingLastPathComponent()) }
        candidate = nil; candidateSource = nil
    }
    func saveCandidate() { run("Saving a copy; retaining the original…", cancellable: false) {
        guard let candidate = self.candidate, let id = self.candidateSource, let item = self.media.first(where: { $0.id == id }) else { return }
        let savedID = try await self.photos.saveCopy(candidate, sourceID: id, revision: item.revision)
        self.state = try await self.repository.record("Verified saved Photos copy.", processedSource: "output:" + savedID)
        self.state = try await self.repository.record("Saved a compressed copy of \(item.name). Original retained.", processedSource: item.id + "|" + item.revision)
        self.discardCandidate()
    } }
    func scanContacts(region: String?) { run("Reading contact records and verifying archives…") {
        let snapshot = try await self.contacts.scan(region: region)
        self.contactSnapshot = snapshot; self.contactMatches = ContactMatching.candidates(snapshot.records)
    } }
    func exportContacts() { run("Writing and checking the contact archive…", paid: false) {
        guard let snapshot = self.contactSnapshot else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Contacts-" + UUID().uuidString).appendingPathExtension("cleanercontacts")
        try await self.contacts.export(snapshot, to: url); self.exportURL = url
        self.state = try await self.repository.record("Exported \(snapshot.records.count) accessible contact records. Notes and account relationships are not included; originals retained.")
    } }
    func scanFolder(_ url: URL) { run("Reading the selected folder…") {
        self.folderScan = try await self.files.scan(url, hash: true)
    } }
}
enum CleanerGate {
    static let unlock = UnlockContent(appName: "Cleaner", features: ["Review photos, videos and selected folders", "Compare duplicate contact candidates", "Make and inspect compressed copies"])
    static let before = BeforeYouBuyContent(title: "Your iPhone already has cleanup tools.", paragraphs: [
        .init("Photos finds duplicates. Contacts can merge duplicates, and Settings shows storage use. If those cover your needs, keep your money."),
        .init("Cleaner brings media review, selected folders and contact comparison together. Try it for 7 days, then {price}, once. Nothing renews.", withoutPrice: "Cleaner brings media review, selected folders and contact comparison together. Try it for 7 days, then a one-time purchase. Nothing renews."),
        .init("Processing stays on this device. Cloud media is fetched only when you allow it. Compressed copies keep their originals.")], justScanLabel: "Look around first")
    static let expired = TrialEndedContent(stillFree: ["Read saved decisions and history", "Export an existing contact snapshot"], unlockKeeps: ["Run new scans and cleanup actions"])
}
