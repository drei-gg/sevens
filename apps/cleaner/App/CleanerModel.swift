import Foundation
import SwiftUI
import SevenKit
import CleanerCore
import CleanerPlatform

@Observable @MainActor final class CleanerModel {
    let store: Store
    let repository: ReviewStore
    let measurements: MeasurementCache
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
    var comparisonFraction: Double?
    var savedMeasurements = 0
    var error: String?
    var busy = false
    var canCancel = false
    var ready = false
    var candidate: CompressionCandidate?
    var candidateSource: String?
    var candidateItem: CleanupItem?
    var createdCopyID: String?
    var compressionBatch: CompressionBatch?
    var videoReview: VideoReview?
    private var sessionCopyKeys: Set<String> = []
    var exportURL: URL?
    private var job: Task<Void, Never>?
    private var started = false
    #if DEBUG
    var tourReady = false
    var resumeFixtureFinished = false
    var batchAuditStatus = "Preparing batch fixture…"
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
        measurements = MeasurementCache(url: root.appendingPathComponent("ScanCache/measurements.sqlite"))
    }
    func start() async {
        guard !started else { return }; started = true
        do { state = try await repository.read(); ready = true } catch { self.error = error.cleanerMessage }
        do { let cache = measurements; savedMeasurements = try await Task.detached { try cache.count() }.value }
        catch { self.error = error.cleanerMessage }
        await store.start()
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") && ProcessInfo.processInfo.arguments.contains("-cleaner.seed") {
            media = [CleanupItem(id: "fixture-a", name: "Example landscape", kind: .photo, revision: "1", bytes: 9_000_000, width: 4000, height: 3000, favorite: true, fingerprint: "fixture"),CleanupItem(id: "fixture-b", name: "Example copy", kind: .photo, revision: "1", bytes: 9_000_000, width: 4000,height: 3000,fingerprint: "fixture"),CleanupItem(id: "fixture-video", name: "Example video", kind: .video, revision: "1", bytes: 120_000_000,duration: 42)]
            if ProcessInfo.processInfo.arguments.contains("-cleaner.dates") {
                var calendar = Calendar(identifier: .gregorian); calendar.timeZone = .current
                let today = calendar.startOfDay(for: Date())
                let parts = calendar.dateComponents([.year, .month], from: today)
                let otherMonth = calendar.date(from: DateComponents(year: parts.year, month: parts.month == 1 ? 2 : 1, day: 1))!
                media = [
                    CleanupItem(id: "date-today", name: "Today fixture", kind: .photo, revision: "1", date: today),
                    CleanupItem(id: "date-past", name: "Past anniversary", kind: .photo, revision: "1", date: calendar.date(byAdding: .year, value: -4, to: today)),
                    CleanupItem(id: "date-month", name: "Other month", kind: .photo, revision: "1", date: otherMonth),
                    CleanupItem(id: "date-unknown", name: "Unknown date", kind: .photo, revision: "1"),
                    CleanupItem(id: "fixture-video", name: "Example video", kind: .video, revision: "1"),
                ]
                selected = ["fixture-video"]
                let files = [
                    CleanupItem(id: "file-today", name: "Today.pdf", kind: .file, revision: "1", bytes: 20_000_000, date: today, fingerprint: "same"),
                    CleanupItem(id: "file-past", name: "Old.PDF", kind: .file, revision: "1", bytes: 20_000_000, date: calendar.date(byAdding: .year, value: -4, to: today), fingerprint: "same"),
                    CleanupItem(id: "file-image", name: "Photo.jpg", kind: .file, revision: "1", bytes: 200_000, date: today),
                    CleanupItem(id: "file-unknown", name: "Unknown.pdf", kind: .file, revision: "1"),
                    CleanupItem(id: "file-empty", name: "LICENSE", kind: .file, revision: "1", bytes: 0, date: today),
                ]
                folderScan = FolderScan(items: files, groups: Matching.exact(files), skipped: 0)
            }
            groups = Matching.exact(media)
        }
        if CleanerFixtures.active { await CleanerFixtures.seed(self); tourReady = true }
        if ProcessInfo.processInfo.arguments.contains("-cleaner.ui") {
            let args = ProcessInfo.processInfo.arguments
            if args.contains("-cleaner.video-fixture") || args.contains("-cleaner.video-audit") || args.contains("-cleaner.video-cleanup") {
                do {
                    if args.contains("-cleaner.video-audit") { try await VideoAudit.run(self); batchAuditStatus = "Video audit passed" }
                    else { try await VideoAudit.fixture(self, cleanup: args.contains("-cleaner.video-cleanup")); batchAuditStatus = "Video fixture ready" }
                } catch { batchAuditStatus = "Video audit failed: " + error.cleanerMessage; self.error = error.cleanerMessage }
            }
            if args.contains("-cleaner.batch-fixture") || args.contains("-cleaner.batch-audit") || args.contains("-cleaner.batch-cleanup") {
                do {
                    if args.contains("-cleaner.batch-audit") { try await CompressionBatchAudit.run(self); batchAuditStatus = "Batch audit passed" }
                    else { try await CompressionBatchAudit.fixture(self, cleanup: args.contains("-cleaner.batch-cleanup")); batchAuditStatus = "Batch fixture ready" }
                } catch { batchAuditStatus = "Batch audit failed: " + error.cleanerMessage; self.error = error.cleanerMessage }
            }
            if args.contains("-cleaner.resume-fixture") || args.contains("-cleaner.resume-cleanup") {
                do { try await CleanerFixtures.resumeFixture(self, cleanup: args.contains("-cleaner.resume-cleanup")); resumeFixtureFinished = true }
                catch { self.error = error.cleanerMessage }
            }
        }
        #endif
    }
    func run(_ title: String, paid: Bool = true, cancellable: Bool = true, operation: @escaping @MainActor () async throws -> Void) {
        guard ready, !busy else { return }
        guard !paid || store.paidFeaturesAvailable else { error = "Start the free trial or unlock to run new scans. Your saved review decisions and exports remain available."; return }
        busy = true; canCancel = cancellable; progress = title
        job = Task {
            defer { busy = false; canCancel = false; progress = ""; comparisonFraction = nil; job = nil }
            do { try await operation() }
            catch is CancellationError { progress = "Cancelled" }
            catch { self.error = error.cleanerMessage }
            do { let cache = measurements; savedMeasurements = try await Task.detached { try cache.count() }.value }
            catch { self.error = error.cleanerMessage }
        }
    }
    func cancel() { guard canCancel else { return }; job?.cancel(); progress = "Stopping after the current resource…" }
    func scanPhotos() { run("Reading accessible media…") {
        let inventory = try await self.photos.inventory()
        let cache = self.measurements
        self.media = try await Task.detached { try cache.restore(inventory.items) }.value
        self.savedMeasurements = self.media.filter { scanHasMeasurement(item: $0) }.count
        self.limitedPhotos = inventory.limited; self.groups = []; self.selected = []
    } }
    func analyze() { run("Reading original resource bytes…") {
        let inventory = try await self.photos.inventory()
        let cache = self.measurements
        let current = try await Task.detached { try cache.restore(inventory.items) }.value
        self.media = current; self.limitedPhotos = inventory.limited; self.groups = []; self.selected = []
        let resumed = current.filter { scanHasMeasurement(item: $0) }.count
        self.savedMeasurements = resumed; self.comparisonFraction = 0
        let result = try await self.photos.analyze(current, allowCloud: self.allowCloud,
            checkpoint: { item in try cache.checkpoint(item) }) { done, total in
            await MainActor.run {
                self.progress = "Checked \(done) of \(total) · \(resumed) already measured"
                self.comparisonFraction = total == 0 ? 1 : Double(done) / Double(total)
            }
        }
        self.media = result.items; self.groups = result.groups
        self.state = try await self.repository.record("Compared \(result.items.count) media items; \(result.unavailable) unavailable or changed. Byte counts describe library resources, not local space freed.")
    } }
    func clearScanProgress() { run("Clearing saved measurements…", paid: false, cancellable: false) {
        let cache = self.measurements
        try await Task.detached { try cache.clear() }.value
        self.savedMeasurements = 0
        self.media = self.media.map { item in var result = item; result.fingerprint = nil; result.bytes = nil; return result }
        self.groups = []; self.selected = []
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
        } catch { self.error = error.cleanerMessage }
    }
    func smartSelect() { selected = Matching.proposedRemoval(groups: groups, items: media, decisions: state.decisions) }
    func deleteReviewed(_ ids: Set<String>) { run("Applying reviewed deletion…", cancellable: false) {
        try await self.photos.delete(ids: ids, expected: self.media, state: self.state)
        self.state = try await self.repository.record("Requested Photos deletion for \(ids.count) reviewed items. iCloud deletion syncs; Recently Deleted can retain their storage.")
        self.media.removeAll { ids.contains($0.id) }; self.groups = []; self.selected.subtract(ids)
    } }
    private func prepareCandidate(_ item: CleanupItem) async throws {
        var persisted = try await repository.read()
        persisted.processedSources = Array(Set(persisted.processedSources).union(sessionCopyKeys))
        _ = try compressionReviewPlan(ids: [item.id], items: [item], state: persisted)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerCandidate-" + UUID().uuidString)
        do {
            let original = try await photos.originalStill(id: item.id, directory: directory, allowCloud: allowCloud, revision: item.revision)
            let task = Task.detached(priority: .userInitiated) { try ImageCompression.makeCandidate(sourceURL: original, directory: directory) }
            let output = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
            try Task.checkCancellation()
            candidate = output; candidateSource = item.id; candidateItem = item; createdCopyID = nil
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    }
    func compress(_ item: CleanupItem) { run("Preparing an original and checking a compressed candidate…") {
        guard self.compressionBatch == nil, self.candidate == nil else { throw CleanerError.blocked("Finish the current review first.") }
        try await self.prepareCandidate(item)
    } }
    func startCompressionBatch(_ ids: Set<String>) { run("Checking the selected photos…") {
        guard self.candidate == nil, self.compressionBatch == nil else { throw CleanerError.blocked("Finish the current review first.") }
        var persisted = try await self.repository.read()
        persisted.processedSources = Array(Set(persisted.processedSources).union(self.sessionCopyKeys))
        let order = self.media.filter { ids.contains($0.id) }.map(\.id)
        guard order.count == ids.count else { throw CleanerError.blocked("A selected photo is no longer available. Read the library again.") }
        let items = try compressionReviewPlan(ids: order, items: self.media, state: persisted)
        self.error = nil; self.compressionBatch = CompressionBatch(items: items)
    } }
    func prepareBatchPhoto() { run("Preparing the next photo…") {
        guard self.candidate == nil else { throw CleanerError.blocked("Finish the current preview first.") }
        guard let item = self.compressionBatch?.current else { return }
        self.error = nil; try await self.prepareCandidate(item)
    } }
    private func clearCandidate() {
        if let candidate { try? FileManager.default.removeItem(at: candidate.outputURL.deletingLastPathComponent()) }
        candidate = nil; candidateSource = nil; candidateItem = nil; createdCopyID = nil
    }
    func discardCandidate() { guard !busy else { return }; clearCandidate(); error = nil }
    func skipBatchPhoto() {
        guard !busy, var queue = compressionBatch, let item = queue.current else { return }
        if createdCopyID != nil { queue.unverified.append(item.id) } else { queue.skipped.append(item.id) }
        queue.index += 1; clearCandidate(); error = nil; compressionBatch = queue
    }
    func stopCompressionBatch() { guard !busy else { return }; clearCandidate(); compressionBatch = nil; error = nil }
    func updateCandidateQuality(_ quality: Double, size: ImageSizeProfile? = nil) { run("Updating and checking the preview…") {
        guard self.createdCopyID == nil else { throw CleanerError.blocked("A copy already exists. Finish verifying it before changing this preview.") }
        guard let previous = self.candidate else { return }
        self.error = nil
        let selectedSize = size ?? previous.size
        let task = Task.detached(priority: .userInitiated) {
            try ImageCompression.makeCandidate(sourceURL: previous.sourceURL,
                directory: previous.outputURL.deletingLastPathComponent(), quality: quality, size: selectedSize)
        }
        let next = try await withTaskCancellationHandler { try await task.value } onCancel: { task.cancel() }
        guard !Task.isCancelled, self.candidate?.outputURL == previous.outputURL else {
            try? FileManager.default.removeItem(at: next.outputURL); return
        }
        self.candidate = next
        try? FileManager.default.removeItem(at: previous.outputURL)
    } }
    func saveCandidate() { run("Verifying and recording a copy; keeping the original…", cancellable: false) {
        guard let candidate = self.candidate, let item = self.candidateItem else { throw CleanerError.blocked("The source snapshot is unavailable. Prepare a new preview.") }
        self.error = nil
        let savedID: String
        if let existing = self.createdCopyID {
            try await self.photos.verifyCopy(candidate, outputID: existing); savedID = existing
        } else {
            savedID = try await self.photos.saveCopy(candidate, sourceID: item.id, revision: item.revision) { id in
                await MainActor.run {
                    self.createdCopyID = id
                    self.sessionCopyKeys.insert(compressionSourceKey(item: item))
                    if !id.isEmpty { self.sessionCopyKeys.insert("output:" + id) }
                }
            }
        }
        do { self.state = try await self.repository.recordCopy(source: item, outputID: savedID) }
        catch is CancellationError { throw CancellationError() }
        catch { throw CleanerError.blocked("The copy was verified, but its review record could not be saved. Retry checks and records that same copy. The original remains. " + error.cleanerMessage) }
        if var queue = self.compressionBatch, queue.current?.id == item.id {
            queue.saved.append(item.id); queue.index += 1; self.compressionBatch = queue
        }
        self.clearCandidate()
    } }
    func scanContacts(region: String?) { run("Reading contact records and verifying archives…") {
        let snapshot = try await self.contacts.scan(region: region)
        self.contactSnapshot = snapshot; self.contactMatches = ContactMatching.candidates(snapshot.records)
    } }

    func openVideo(_ item: CleanupItem) { run("Reading and checking the original video…") {
        guard self.videoReview == nil, self.candidate == nil, self.compressionBatch == nil else { throw CleanerError.blocked("Finish the current review first.") }
        var persisted = try await self.repository.read()
        persisted.processedSources = Array(Set(persisted.processedSources).union(self.sessionCopyKeys))
        try checkVideoCopySource(item: item, state: persisted)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerVideo-" + UUID().uuidString)
        do {
            let source = try await self.photos.originalVideo(id: item.id, directory: directory, allowCloud: self.allowCloud, revision: item.revision)
            let inspection = try await VideoCompression.inspect(source)
            _ = try videoCopyPlan(source: inspection.facts, startMs: 0, endMs: inspection.facts.durationMs)
            self.videoReview = VideoReview(item: item, source: source, inspection: inspection)
            self.error = nil
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    } }
    func prepareVideo() { run("Preparing and checking the selected video range…") {
        guard let review = self.videoReview, review.createdID == nil, let plan = review.plan else { throw CleanerError.blocked("Enter a valid start and end inside the video.") }
        self.error = nil
        let next = try await VideoCompression.makeCandidate(sourceURL: review.source, directory: review.source.deletingLastPathComponent(), start: plan.startMs, end: plan.endMs)
        if let old = review.candidate { try? FileManager.default.removeItem(at: old.outputURL) }
        review.candidate = next
    } }
    func closeVideo() {
        guard !busy else { return }
        if let review = videoReview { try? FileManager.default.removeItem(at: review.source.deletingLastPathComponent()) }
        videoReview = nil; error = nil
    }
    func saveVideo() { run("Verifying and recording the video copy…", cancellable: false) {
        guard let review = self.videoReview, let preview = review.candidate, review.plan == preview.plan, preview.issues.isEmpty else { throw CleanerError.blocked("Prepare and inspect the current range before saving.") }
        self.error = nil
        let saved: String
        if let existing = review.createdID {
            try await self.photos.verifySavedFile(preview.outputURL, outputID: existing); saved = existing
        } else {
            saved = try await self.photos.saveVideoCopy(preview, sourceID: review.item.id, revision: review.item.revision) { id in
                await MainActor.run {
                    review.createdID = id
                    self.sessionCopyKeys.insert(compressionSourceKey(item: review.item)); self.sessionCopyKeys.insert("output:" + id)
                    #if DEBUG
                    if ProcessInfo.processInfo.arguments.contains("-cleaner.video-fixture") || ProcessInfo.processInfo.arguments.contains("-cleaner.video-audit") { VideoAudit.remember(id) }
                    #endif
                }
            }
        }
        do { self.state = try await self.repository.recordCopy(source: review.item, outputID: saved) }
        catch { throw CleanerError.blocked("The video copy exists, but its review record could not be saved. Retry verifies and records that same copy. " + error.cleanerMessage) }
        review.savedID = saved
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
