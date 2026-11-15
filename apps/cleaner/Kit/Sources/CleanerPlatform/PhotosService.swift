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
    public func analyze(_ input: [CleanupItem], allowCloud: Bool, progress: @Sendable @escaping (Int, Int) async -> Void) async throws -> PhotoAnalysis {
        var items = input; var unavailable = 0
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("CleanerScan-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for i in items.indices {
            try Task.checkCancellation()
            guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [items[i].id], options: nil).firstObject,
                  Self.item(asset).revision == items[i].revision else { unavailable += 1; continue }
            var parts: [String] = []; var bytes: Int64 = 0
            do {
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
            } catch is CancellationError { throw CancellationError() }
            catch { unavailable += 1; items[i].fingerprint = nil; items[i].bytes = nil }
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
    public func originalStill(id: String, directory: URL, allowCloud: Bool) async throws -> URL {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              asset.mediaType == .image, !asset.mediaSubtypes.contains(.photoLive) else { throw CleanerError.blocked("Choose a still photo. Live Photo conversion needs a separate preservation profile.") }
        let resources = PHAssetResource.assetResources(for: asset)
        guard resources.count == 1, let resource = resources.first, resource.type == .photo else { throw CleanerError.blocked("Edited, RAW and multipart assets need a different profile. Originals are unchanged.") }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension((resource.originalFilename as NSString).pathExtension)
        do { try await Self.write(resource, to: url, allowCloud: allowCloud); return url }
        catch { try? FileManager.default.removeItem(at: url); throw error }
    }
    public func delete(ids: Set<String>, expected: [CleanupItem], state: ReviewState) async throws {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids), options: nil)
        var current: [CleanupItem] = []; fetch.enumerateObjects { asset, _, _ in current.append(Self.item(asset)) }
        try MutationGuard.validateRemoval(ids: ids, items: current, state: state)
        try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.deleteAssets(fetch) }
    }
    public func saveCopy(_ candidate: CompressionCandidate, sourceID: String, revision: String) async throws -> String {
        try checkCopyOffer(assessment: candidate.assessment)
        guard let source = PHAsset.fetchAssets(withLocalIdentifiers: [sourceID], options: nil).firstObject,
              Self.item(source).revision == revision else { throw CleanerError.blocked("The source changed or is no longer accessible. Prepare a new candidate.") }
        let albums = PHAssetCollection.fetchAssetCollectionsContaining(source, with: .album, options: nil)
        let identifier = CreatedIdentifier()
        try await PHPhotoLibrary.shared().performChanges {
            let request = PHAssetCreationRequest.forAsset()
            request.addResource(with: .photo, fileURL: candidate.outputURL, options: nil)
            request.creationDate = source.creationDate; request.location = source.location; request.isFavorite = source.isFavorite
            if let placeholder = request.placeholderForCreatedAsset {
                identifier.set(placeholder.localIdentifier)
                albums.enumerateObjects { album, _, _ in
                    if album.canPerform(.addContent) { PHAssetCollectionChangeRequest(for: album)?.addAssets([placeholder] as NSArray) }
                }
            }
        }
        guard let id = identifier.get(), let saved = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject,
              let resource = PHAssetResource.assetResources(for: saved).first else {
            throw CleanerError.blocked("Photos accepted the copy but it could not be read back. Both originals and any created copy are retained; inspect Photos before retrying.")
        }
        let checkURL = candidate.outputURL.deletingLastPathComponent().appendingPathComponent("readback-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: checkURL) }
        do {
            try await Self.write(resource, to: checkURL, allowCloud: false)
            guard try FileDigest.sha256(checkURL) == FileDigest.sha256(candidate.outputURL) else { throw CleanerError.blocked("Saved bytes differ.") }
        } catch {
            throw CleanerError.blocked("The copy was created, but saved-byte verification could not complete. The original remains. Inspect Photos before retrying.")
        }
        return id
    }
    public func setFavorite(id: String, value: Bool) async throws {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject else { throw CleanerError.blocked("Photo is no longer accessible.") }
        try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest(for: asset).isFavorite = value }
    }
    public func addToAlbum(ids: Set<String>, title: String) async throws {
        let fetch = PHAsset.fetchAssets(withLocalIdentifiers: Array(ids), options: nil)
        try validateAlbumRequest(requested: UInt32(clamping: ids.count), accessible: UInt32(clamping: fetch.count), title: title)
        try await PHPhotoLibrary.shared().performChanges {
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
#endif
