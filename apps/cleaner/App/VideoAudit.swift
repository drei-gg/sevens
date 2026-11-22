#if DEBUG
import Foundation
@preconcurrency import Photos
import CleanerCore
import CleanerPlatform

@MainActor enum VideoAudit {
    static var folder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("CleanerVideoAudit") }
    static var manifest: URL { folder.appendingPathComponent("owned.json") }
    static var sourceURL: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("video-profile.mp4") }
    static func remember(_ id: String) {
        var ids = (try? JSONDecoder().decode([String].self, from: Data(contentsOf: manifest))) ?? []
        if !ids.contains(id) { ids.append(id) }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? JSONEncoder().encode(ids).write(to: manifest, options: .atomic)
    }
    static func fixture(_ model: CleanerModel, cleanup: Bool) async throws {
        let authorization = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard authorization == .authorized || authorization == .limited else { throw CleanerError.blocked("Video fixture permission unavailable.") }
        let ids = (try? JSONDecoder().decode([String].self, from: Data(contentsOf: manifest))) ?? []
        if !ids.isEmpty {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: ids, options: nil)
            try await PHPhotoLibrary.shared().performChanges { @Sendable in PHAssetChangeRequest.deleteAssets(assets) }
        }
        try? FileManager.default.removeItem(at: folder)
        if cleanup { return }
        guard FileManager.default.fileExists(atPath: sourceURL.path) else { throw CleanerError.blocked("Stage the video-profile.mp4 test fixture in this app's Documents directory first.") }
        let created = VideoFixtureIdentifier()
        let input = sourceURL
        try await PHPhotoLibrary.shared().performChanges { @Sendable in
            let request = PHAssetCreationRequest.forAsset(); request.addResource(with: .video, fileURL: input, options: nil)
            request.creationDate = Date(timeIntervalSince1970: 1577934245)
            created.set(request.placeholderForCreatedAsset?.localIdentifier)
        }
        guard let id = created.get() else { throw CleanerError.Unreadable }
        remember(id)
        let inventory = try await model.photos.inventory()
        guard let source = inventory.items.first(where: { $0.id == id }) else { throw CleanerError.Unreadable }
        model.media = [source]
        model.state = try await model.repository.decide([source.id: .keep], revisions: [source.id: source.revision])
    }
    static func run(_ model: CleanerModel) async throws {
        try await fixture(model, cleanup: false)
        let item = model.media[0], original = try Data(contentsOf: sourceURL)
        model.openVideo(item); try await settle(model)
        guard let review = model.videoReview else { throw CleanerError.blocked(model.error ?? "No video review") }
        review.start = "1.250"; review.end = "4.750"
        model.prepareVideo(); try await settle(model)
        guard let preview = review.candidate, preview.issues.isEmpty else { throw CleanerError.blocked(model.error ?? review.candidate?.issues.joined(separator: "; ") ?? "No candidate") }
        let ledger = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CleanerUITests/review.json")
        let good = try Data(contentsOf: ledger); try Data("broken ledger".utf8).write(to: ledger)
        model.saveVideo(); try await settle(model)
        guard let receipt = review.createdID, review.savedID == nil, model.error != nil else { throw CleanerError.blocked("Failed save lost its video receipt.") }
        remember(receipt)
        let count = try await model.photos.inventory().items.count
        try good.write(to: ledger)
        model.saveVideo(); try await settle(model)
        guard review.savedID == receipt, model.error == nil, try await model.photos.inventory().items.count == count,
              try Data(contentsOf: review.source) == original else { throw CleanerError.blocked("Video retry changed the original or created another copy.") }
        try await model.photos.verifySavedFile(preview.outputURL, outputID: receipt)
        let library = try await model.photos.inventory()
        guard library.items.contains(where: { $0.id == receipt && abs($0.duration - 3.5) <= 0.1 }),
              model.state.decisions[item.id] == .keep else { throw CleanerError.blocked("Saved video duration or original decision changed.") }
        model.closeVideo()
        guard !FileManager.default.fileExists(atPath: review.source.deletingLastPathComponent().path) else { throw CleanerError.blocked("Video temporary files remain after close.") }
    }
    private static func settle(_ model: CleanerModel) async throws {
        for _ in 0..<600 { if !model.busy { return }; try await Task.sleep(for: .milliseconds(50)) }
        throw CleanerError.blocked("Video operation timed out.")
    }
}
private final class VideoFixtureIdentifier: @unchecked Sendable {
    private let lock = NSLock(); private var value: String?
    func set(_ next: String?) { lock.lock(); defer { lock.unlock() }; value = next }
    func get() -> String? { lock.lock(); defer { lock.unlock() }; return value }
}
#endif
