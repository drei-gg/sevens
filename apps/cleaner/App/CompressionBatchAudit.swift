#if DEBUG
import Foundation
import UIKit
@preconcurrency import Photos
import CleanerCore
import CleanerPlatform

/// Explicit native test fixtures. Every Photos identifier is tracked for bounded cleanup.
@MainActor enum CompressionBatchAudit {
    static var folder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("CleanerBatchAudit") }
    static var manifest: URL { folder.appendingPathComponent("owned.json") }
    static func fixture(_ model: CleanerModel, cleanup: Bool) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard status == .authorized || status == .limited else { throw CleanerError.blocked("Fixture permission unavailable.") }
        var ids = (try? JSONDecoder().decode([String].self, from: Data(contentsOf: manifest))) ?? []
        ids += model.state.processedSources.filter { $0.hasPrefix("output:") }.map { String($0.dropFirst(7)) }
        if !ids.isEmpty {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            try await PHPhotoLibrary.shared().performChanges { @Sendable in PHAssetChangeRequest.deleteAssets(assets) }
        }
        try? FileManager.default.removeItem(at: folder)
        if cleanup { return }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 900), format: format).image { context in
            for y in 0..<900 {
                UIColor(red: CGFloat(40 + y / 8)/255, green: CGFloat(100 + y / 10)/255, blue: 160/255, alpha: 1).setFill()
                context.fill(CGRect(x: 0, y: y, width: 1200, height: 1))
            }
        }
        guard let data = image.jpegData(compressionQuality: 1) else { throw CleanerError.Unreadable }
        let url = folder.appendingPathComponent("Queue original.jpg"); try data.write(to: url)
        let identifier = BatchFixtureIdentifier()
        try await PHPhotoLibrary.shared().performChanges { @Sendable in
            let request = PHAssetCreationRequest.forAsset(); request.addResource(with: .photo, fileURL: url, options: nil)
            identifier.set(request.placeholderForCreatedAsset?.localIdentifier)
        }
        guard let id = identifier.get() else { throw CleanerError.Unreadable }
        try JSONEncoder().encode([id]).write(to: manifest, options: .atomic)
        let inventory = try await model.photos.inventory()
        guard let source = inventory.items.first(where: { $0.id == id }) else { throw CleanerError.Unreadable }
        var missing = source; missing.id = "unavailable-batch-fixture"; missing.name = "Unavailable photo.jpg"
        model.media = [source, missing]; model.selected = [source.id, missing.id]
        model.state = try await model.repository.decide([source.id: .keep], revisions: [source.id: source.revision])
    }
    static func run(_ model: CleanerModel) async throws {
        try await fixture(model, cleanup: false)
        let source = model.media[0], selected = model.selected
        let original = try Data(contentsOf: folder.appendingPathComponent("Queue original.jpg"))
        model.startCompressionBatch(selected); try await settle(model)
        guard model.compressionBatch?.items.count == 2 else { throw CleanerError.Unreadable }
        model.prepareBatchPhoto(); try await settle(model)
        guard let candidate = model.candidate, candidate.assessment.canOfferCopy else { throw CleanerError.blocked("Fixture candidate did not pass: \(model.error ?? model.candidate?.assessment.issues.joined(separator: "; ") ?? "missing")") }
        let ledger = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CleanerUITests/review.json")
        let valid = try Data(contentsOf: ledger)
        try Data("broken ledger".utf8).write(to: ledger)
        model.media[0].revision = "changed inventory"
        model.saveCandidate(); try await settle(model)
        guard let output = model.createdCopyID, model.error != nil, model.compressionBatch?.index == 0,
              model.candidate?.outputURL == candidate.outputURL else { throw CleanerError.blocked("Failed save lost the receipt or advanced the queue.") }
        try JSONEncoder().encode([source.id, output]).write(to: manifest, options: .atomic)
        let count = try await model.photos.inventory().items.count
        try valid.write(to: ledger)
        model.saveCandidate(); try await settle(model)
        let after = try await model.photos.inventory()
        let state = try await model.repository.read()
        guard model.compressionBatch?.index == 1, model.error == nil, after.items.count == count,
              state.history.count == 1, state.processedSources.contains(compressionSourceKey(item: source)),
              state.processedSources.contains("output:" + output), state.decisions[source.id] == .keep,
              !FileManager.default.fileExists(atPath: candidate.outputURL.deletingLastPathComponent().path) else { throw CleanerError.blocked("Verification retry did not keep exactly one copy and record.") }
        let check = try await model.photos.originalStill(id: source.id, directory: folder.appendingPathComponent("check"), allowCloud: false, revision: source.revision)
        guard try Data(contentsOf: check) == original else { throw CleanerError.blocked("Original bytes changed.") }
        model.prepareBatchPhoto(); try await settle(model)
        guard model.error != nil, model.candidate == nil else { throw CleanerError.Unreadable }
        model.skipBatchPhoto()
        guard let queue = model.compressionBatch, queue.complete, queue.saved == [source.id], queue.skipped.count == 1,
              queue.unverified.isEmpty, model.selected == selected else { throw CleanerError.Unreadable }
        model.stopCompressionBatch(); model.media[0] = source
        model.startCompressionBatch(selected); try await settle(model)
        guard model.error != nil, model.compressionBatch == nil else { throw CleanerError.Unreadable }
        model.error = nil
        try Data("PASS: native PhotoKit copy; record failure; same-copy retry; source snapshot; original byte readback; two-item queue; skip unavailable; cleanup; processed-source refusal.\n".utf8).write(to: folder.appendingPathComponent("result.txt"))
    }
    private static func settle(_ model: CleanerModel) async throws {
        for _ in 0..<600 { if !model.busy { return }; try await Task.sleep(for: .milliseconds(100)) }
        throw CleanerError.blocked("Batch audit timed out.")
    }
}
private final class BatchFixtureIdentifier: @unchecked Sendable {
    private let lock = NSLock(); private var id: String?
    func set(_ value: String?) { lock.lock(); defer { lock.unlock() }; id = value }
    func get() -> String? { lock.lock(); defer { lock.unlock() }; return id }
}
#endif
