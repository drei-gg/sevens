#if canImport(Photos) && canImport(UIKit)
import Foundation
@preconcurrency import Photos
import CryptoKit
import Vision
import UIKit
import CleanerCore

public struct PhotoInventory: Sendable { public var items: [CleanupItem]; public var limited: Bool }
public struct PhotoAnalysis: Sendable { public var items: [CleanupItem]; public var groups: [MatchGroup]; public var unavailable: Int }
public actor PhotosService {
    public init() {}
    public func inventory() async throws -> PhotoInventory {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard status == .authorized || status == .limited else { throw CleanerError.blocked("Photos access was not granted. You can change it in Settings.") }
        let options = PHFetchOptions(); options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        let result = PHAsset.fetchAssets(with: options)
        var items: [CleanupItem] = []
        result.enumerateObjects { asset, _, _ in
            guard asset.mediaType == .image || asset.mediaType == .video else { return }
            items.append(Self.item(asset))
        }
        return PhotoInventory(items: items, limited: status == .limited)
    }
    private static func item(_ asset: PHAsset) -> CleanupItem {
        let resources = PHAssetResource.assetResources(for: asset)
        let tags = photoTags(traits: PhotoTraits(screenshot: asset.mediaSubtypes.contains(.photoScreenshot), live: asset.mediaSubtypes.contains(.photoLive),
                                                 screenRecording: asset.mediaSubtypes.contains(.videoScreenRecording), highFrameRate: asset.mediaSubtypes.contains(.videoHighFrameRate),
                                                 hdr: asset.mediaSubtypes.contains(.photoHDR), burst: asset.representsBurst))
        return CleanupItem(id: asset.localIdentifier, name: resources.first?.originalFilename ?? "Photo", kind: asset.mediaType == .video ? .video : .photo,
            revision: photoRevision(modifiedSince1970: asset.modificationDate?.timeIntervalSince1970, pixelWidth: Int32(clamping: asset.pixelWidth),
                                    pixelHeight: Int32(clamping: asset.pixelHeight), resourceCount: Int32(clamping: resources.count)),
            bytes: nil, date: asset.creationDate, width: Int32(clamping: asset.pixelWidth), height: Int32(clamping: asset.pixelHeight),
            duration: asset.duration, favorite: asset.isFavorite, tags: tags, fingerprint: nil)
    }
    public func analyze(_ input: [CleanupItem], allowCloud: Bool, checkpoint: @Sendable (CleanupItem) async throws -> Void = { _ in }, progress: @Sendable @escaping (Int, Int) async -> Void) async throws -> PhotoAnalysis {
        var items = input; var unavailable = 0
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerScan-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for i in items.indices {
            try Task.checkCancellation()
            var parts: [String] = []; var bytes: Int64 = 0
            do {
                guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [items[i].id], options: nil).firstObject,
                      Self.item(asset).revision == items[i].revision else { throw CleanerError.blocked("Asset changed.") }
                if !scanHasMeasurement(item: items[i]) {
                let resources = PHAssetResource.assetResources(for: asset)
                guard !resources.isEmpty else { throw CleanerError.blocked("No resources.") }
                for resource in resources {
                    let url = directory.appendingPathComponent(UUID().uuidString)
                    try await Self.write(resource, to: url, allowCloud: allowCloud)
                    defer { try? FileManager.default.removeItem(at: url) }
                    bytes += Int64(try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0)
                    parts.append(photoResourcePart(resourceType: Int64(resource.type.rawValue), uniformTypeIdentifier: resource.uniformTypeIdentifier, sha256: try FileDigest.sha256(url)))
                }
                // Re-check against external edits after resource reads.
                guard let fresh = PHAsset.fetchAssets(withLocalIdentifiers: [asset.localIdentifier], options: nil).firstObject,
                      Self.item(fresh).revision == items[i].revision else { throw CleanerError.blocked("Asset changed.") }
                items[i].fingerprint = photoFingerprint(parts: parts); items[i].bytes = bytes
                }
            } catch is CancellationError { throw CancellationError() }
            catch { unavailable += 1; items[i].fingerprint = nil; items[i].bytes = nil }
            // Storage failure must not be mistaken for an unavailable photo or completed work.
            try await checkpoint(items[i])
            await progress(i + 1, items.count)
        }
        return PhotoAnalysis(items: items, groups: Matching.exact(items), unavailable: unavailable)
    }
    private static func write(_ resource: PHAssetResource, to url: URL, allowCloud: Bool) async throws {
        let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = allowCloud
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            PHAssetResourceManager.default().writeData(for: resource, toFile: url, options: options) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
        try Task.checkCancellation()
    }
    public func originalStill(id: String, directory: URL, allowCloud: Bool, revision: String? = nil) async throws -> URL {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              asset.mediaType == .image, !asset.mediaSubtypes.contains(.photoLive) else { throw CleanerError.blocked("Choose a still photo. Live Photo conversion needs a separate preservation profile.") }
        if let revision, Self.item(asset).revision != revision { throw CleanerError.blocked("The source changed. Read the library again before preparing a preview.") }
        let resources = PHAssetResource.assetResources(for: asset)
        guard resources.count == 1, let resource = resources.first, resource.type == .photo else { throw CleanerError.blocked("Edited, RAW and multipart assets need a different profile. Originals are unchanged.") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension((resource.originalFilename as NSString).pathExtension)
        do {
            try await Self.write(resource, to: url, allowCloud: allowCloud)
            if let revision {
                guard let current = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
                      Self.item(current).revision == revision else { throw CleanerError.blocked("The source changed while reading. Read the library again.") }
            }
            return url
        }
        catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    public func originalVideo(id: String, directory: URL, allowCloud: Bool, revision: String) async throws -> URL {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              asset.mediaType == .video, Self.item(asset).revision == revision else { throw CleanerError.blocked("The video changed or is no longer accessible. Read the library again.") }
        guard !asset.mediaSubtypes.contains(.videoHighFrameRate), !asset.mediaSubtypes.contains(.videoTimelapse), !asset.mediaSubtypes.contains(.videoCinematic) else { throw CleanerError.blocked("Slow-motion, time-lapse and cinematic video need a different preservation profile.") }
        let resources = PHAssetResource.assetResources(for: asset)
        guard resources.count == 1, let resource = resources.first, resource.type == .video else { throw CleanerError.blocked("Edited or multipart video needs a different preservation profile.") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("original.mov")
        do {
            try await Self.writeVideo(resource, to: url, allowCloud: allowCloud)
            let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
            guard bytes <= 512 * 1024 * 1024 else { throw CleanerError.blocked("This profile accepts videos up to 512 MB.") }
            guard let current = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
                  Self.item(current).revision == revision else { throw CleanerError.blocked("The source changed while reading. Read the library again.") }
            return url
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    private static func writeVideo(_ resource: PHAssetResource, to url: URL, allowCloud: Bool) async throws {
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CleanerError.Unreadable }
        let sink = BoundedVideoResource(try FileHandle(forWritingTo: url))
        let options = PHAssetResourceRequestOptions(); options.isNetworkAccessAllowed = allowCloud
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let id = PHAssetResourceManager.default().requestData(for: resource, options: options,
                    dataReceivedHandler: { sink.append($0) }, completionHandler: { error in
                        if let failure = sink.finish(error) { continuation.resume(throwing: failure) }
                        else { continuation.resume() }
                    })
                sink.setID(id)
            }
        } onCancel: { sink.cancel() }
        try Task.checkCancellation()
    }
    public func delete(ids: Set<String>, expected: [CleanupItem], state: ReviewState) async throws {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids), options: nil)
        var current: [CleanupItem] = []; fetch.enumerateObjects { asset, _, _ in current.append(Self.item(asset)) }
        try MutationGuard.validateRemoval(ids: ids, items: current, state: state)
        try await PHPhotoLibrary.shared().performChanges { @Sendable in PHAssetChangeRequest.deleteAssets(fetch) }
    }
    public func saveCopy(_ candidate: CompressionCandidate, sourceID: String, revision: String, created: (@Sendable (String) async -> Void)? = nil) async throws -> String {
        try checkCopyOffer(assessment: candidate.assessment)
        return try await saveMediaCopy(candidate.outputURL, type: .photo, sourceID: sourceID, revision: revision, created: created)
    }
    public func saveVideoCopy(_ candidate: VideoCandidate, sourceID: String, revision: String, created: (@Sendable (String) async -> Void)? = nil) async throws -> String {
        guard candidate.issues.isEmpty else { throw CleanerError.blocked("Inspect a verified video preview before saving.") }
        return try await saveMediaCopy(candidate.outputURL, type: .video, sourceID: sourceID, revision: revision, created: created)
    }
    private func saveMediaCopy(_ outputURL: URL, type: PHAssetResourceType, sourceID: String, revision: String, created: (@Sendable (String) async -> Void)?) async throws -> String {
        guard let source = PHAsset.fetchAssets(withLocalIdentifiers: [sourceID], options: nil).firstObject,
              Self.item(source).revision == revision else { throw CleanerError.blocked("The source changed or is no longer accessible. Prepare a new candidate.") }
        let albums = PHAssetCollection.fetchAssetCollectionsContaining(source, with: .album, options: nil)
        let identifier = CreatedIdentifier()
        try await PHPhotoLibrary.shared().performChanges { @Sendable in
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: type, fileURL: outputURL, options: nil)
            request.creationDate = source.creationDate; request.location = source.location; request.isFavorite = source.isFavorite
            if let placeholder = request.placeholderForCreatedAsset {
                identifier.set(placeholder.localIdentifier)
                albums.enumerateObjects { album, _, _ in
                    if album.canPerform(.addContent) { PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray) }
                }
            }
        }
        let id = identifier.get() ?? ""
        await created?(id)
        try await verifySavedFile(outputURL, outputID: id)
        return id
    }
    /// A verification retry reads the existing copy rather than creating a second asset.
    public func verifyCopy(_ candidate: CompressionCandidate, outputID: String) async throws { try await verifySavedFile(candidate.outputURL, outputID: outputID) }
    public func verifySavedFile(_ outputURL: URL, outputID: String) async throws {
        guard !outputID.isEmpty, let saved = PHAsset.fetchAssets(withLocalIdentifiers: [outputID], options: nil).firstObject,
              let resource = PHAssetResource.assetResources(for: saved).first else {
            throw CleanerError.blocked("Photos accepted the copy but it could not be read back. Originals and any created copy remain. Inspect Photos; retry never creates another copy.")
        }
        let checkURL = outputURL.deletingLastPathComponent().appendingPathComponent("readback-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: checkURL) }
        do {
            try await Self.write(resource, to: checkURL, allowCloud: false)
            guard try FileDigest.sha256(checkURL) == FileDigest.sha256(outputURL) else { throw CleanerError.blocked("Saved bytes differ.") }
        } catch {
            throw CleanerError.blocked("A copy exists, but its saved bytes could not be verified. Retry checks that same copy; it does not create another. The original remains.")
        }
    }
    public func setFavorite(id: String, value: Bool) async throws {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { throw CleanerError.blocked("Photo is no longer accessible.") }
        try await PHPhotoLibrary.shared().performChanges { @Sendable in PHAssetChangeRequest(for: asset).isFavorite = value }
    }
    public func addToAlbum(ids: Set<String>, title: String) async throws {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids), options: nil)
        try validateAlbumRequest(requested: UInt32(clamping: ids.count), accessible: UInt32(clamping: fetch.count), title: title)
        try await PHPhotoLibrary.shared().performChanges { @Sendable in
            let request = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: title)
            request.addAssets(fetch)
        }
    }
    /// Vision supplies feature prints and their distances; the core keeps the capture-time window and
    /// words the suggestions. Nearby pairs are review suggestions only; threshold and search window
    /// are provisional, never used for automatic selection.
    public func similar(_ input: [CleanupItem], allowCloud: Bool) async throws -> [MatchGroup] {
        let window = SimilarityWindow(); var prints: [String: VNFeaturePrintObservation] = [:]; var groups: [MatchGroup] = []
        for item in similarityOrder(items: input) {
            try Task.checkCancellation()
            guard let date = item.date, let asset = PHAsset.fetchAssets(withLocalIdentifiers: [item.id], options: nil).firstObject else { continue }
            let recent = window.prune(date: date)
            prints = prints.filter { recent.contains($0.key) }
            let options = PHImageRequestOptions(); options.isSynchronous = true; options.isNetworkAccessAllowed = allowCloud; options.deliveryMode = .highQualityFormat
            var print: VNFeaturePrintObservation?
            PHImageManager.default().requestImage(for: asset, targetSize: CGSize(width: 512, height: 512), contentMode: .aspectFit, options: options) { image, _ in
                guard let cg = image?.cgImage else { return }
                let request = VNGenerateImageFeaturePrintRequest(); request.revision = VNGenerateImageFeaturePrintRequestRevision2
                do { try VNImageRequestHandler(cgImage: cg).perform([request]); print = request.results?.first as? VNFeaturePrintObservation } catch { }
            }
            guard let print else { continue }
            for id in recent {
                guard let feature = prints[id] else { continue }
                var distance: Float = 0
                try feature.computeDistance(&distance, to: print)
                if let group = similarGroup(earlierId: id, laterId: item.id, distance: distance) { groups.append(group) }
            }
            window.admit(id: item.id, date: date); prints[item.id] = print
        }
        return groups
    }
}
/// PhotoKit's change closure writes once; access is synchronized rather than hiding a captured mutable variable.
private final class CreatedIdentifier: @unchecked Sendable {
    private let lock = NSLock()
    private var value: String?
    func set(_ id: String) { lock.withLock { value = id } }
    func get() -> String? { lock.withLock { value } }
}
/// PhotoKit callbacks may arrive on different queues. Bound bytes before writing, and cancel the
/// native resource request before returning a failed/cancelled read to its owning review.
private final class BoundedVideoResource: @unchecked Sendable {
    private let lock = NSLock()
    private let handle: FileHandle
    private var id: PHAssetResourceDataRequestID?
    private var bytes = 0
    private var failure: Error?
    private var finished = false
    init(_ handle: FileHandle) { self.handle = handle }
    func setID(_ value: PHAssetResourceDataRequestID) {
        let cancel = lock.withLock { id = value; return failure != nil && !finished }
        if cancel { PHAssetResourceManager.default().cancelDataRequest(value) }
    }
    func append(_ data: Data) {
        let cancellation: PHAssetResourceDataRequestID? = lock.withLock {
            guard !finished, failure == nil else { return nil }
            do {
                guard data.count <= 512 * 1024 * 1024 - bytes else { throw CleanerError.blocked("This profile accepts videos up to 512 MB. The original stays.") }
                try handle.write(contentsOf: data); bytes += data.count
            } catch { failure = error }
            return failure == nil ? nil : id
        }
        if let cancellation { PHAssetResourceManager.default().cancelDataRequest(cancellation) }
    }
    func cancel() {
        let cancellation = lock.withLock { if !finished { failure = CancellationError() }; return finished ? nil : id }
        if let cancellation { PHAssetResourceManager.default().cancelDataRequest(cancellation) }
    }
    func finish(_ error: Error?) -> Error? {
        lock.withLock {
            finished = true
            do { try handle.close() } catch { if failure == nil { failure = error } }
            return failure ?? error
        }
    }
}
#endif
