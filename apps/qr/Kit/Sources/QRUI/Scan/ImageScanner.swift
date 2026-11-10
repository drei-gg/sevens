import CoreGraphics
import Foundation
import ImageIO
import PhotosUI
import QRCore
import SwiftUI
import UIKit
import Vision

/// Finds every barcode in a still image with Vision. Used by the photo picker, paste and the
/// share extensions.
///
/// - Images are downscaled to `maxPixelSize` (2048) on the long side first, to bound memory
///   (extensions have a hard limit).
/// - All Vision-supported symbologies. Identical codes (same payload and symbology) appear once.
/// - Results run top to bottom, left to right. Work happens off the main actor.
/// - Failures (unreadable data, Vision errors) yield an empty array: "no code found".
public struct ImageScanner: Sendable {
    public static let maxPixelSize = 2048
    /// Upper bound on codes returned from one image.
    public static let maxResults = 50

    public init() {}

    public func scan(_ image: CGImage) async -> [ScannedCode] {
        let box = UncheckedImage(image: image, orientation: .up)
        return await Self.run(.image(box))
    }

    public func scan(_ image: UIImage) async -> [ScannedCode] {
        guard let cg = image.cgImage else {
            if let data = image.pngData() { return await scan(data) }
            return []
        }
        let box = UncheckedImage(image: cg, orientation: CGImagePropertyOrientation(image.imageOrientation))
        return await Self.run(.image(box))
    }

    public func scan(_ data: Data) async -> [ScannedCode] {
        await Self.run(.data(data))
    }

    /// Loads the picked photo's bytes, then scans them. Throws only when the photo can't be loaded.
    public func scan(_ item: PhotosPickerItem) async throws -> [ScannedCode] {
        guard let data = try await item.loadTransferable(type: Data.self) else { return [] }
        return await scan(data)
    }

    // MARK: Pipeline

    private enum Source: Sendable {
        case image(UncheckedImage)
        case data(Data)
    }

    /// CGImage is immutable; this just tells the compiler we know.
    private struct UncheckedImage: @unchecked Sendable {
        let image: CGImage
        let orientation: CGImagePropertyOrientation
    }

    @concurrent
    private static func run(_ source: Source) async -> [ScannedCode] {
        let prepared: UncheckedImage?
        switch source {
        case .image(let box):
            prepared = UncheckedImage(image: downscaled(box.image), orientation: box.orientation)
        case .data(let data):
            prepared = decode(data)
        }
        guard let prepared else { return [] }
        let request = VNDetectBarcodesRequest()
        #if targetEnvironment(simulator)
        // The Simulator can't create the inference context Vision's current detector needs. The
        // first revision is non-ML and works there; its constant is deprecated (warnings are
        // errors), so the raw value is used. Simulator only; devices use the default revision.
        request.revision = 1
        #endif
        let handler = VNImageRequestHandler(cgImage: prepared.image, orientation: prepared.orientation, options: [:])
        do { try handler.perform([request]) } catch { return [] }
        let found = (request.results ?? []).map { Found(code: ScanDecoding.code($0), box: $0.boundingBox) }
        return Array(order(found).prefix(maxResults))
    }

    /// Reads image bytes with ImageIO, decoding no larger than `maxPixelSize` (no full-size bitmap).
    private static func decode(_ data: Data) -> UncheckedImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(src) > 0 else { return nil }
        var orientation = CGImagePropertyOrientation.up
        if let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
           let raw = props[kCGImagePropertyOrientation] as? UInt32,
           let o = CGImagePropertyOrientation(rawValue: raw) {
            orientation = o
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: false,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, options as CFDictionary) else { return nil }
        return UncheckedImage(image: cg, orientation: orientation)
    }

    /// Scales down (never up) so the long side is at most `maxPixelSize`.
    static func downscaled(_ image: CGImage) -> CGImage {
        let w = image.width, h = image.height
        let longSide = max(w, h)
        guard longSide > maxPixelSize, w > 0, h > 0 else { return image }
        let scale = Double(maxPixelSize) / Double(longSide)
        let nw = max(1, Int((Double(w) * scale).rounded()))
        let nh = max(1, Int((Double(h) * scale).rounded()))
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: nw, height: nh, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return image }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: nw, height: nh))
        return ctx.makeImage() ?? image
    }

    // MARK: Ordering

    struct Found: Sendable {
        var code: ScannedCode
        /// Vision's normalized box: origin bottom-left, y grows upward.
        var box: CGRect
    }

    /// Top to bottom, then left to right within a row; duplicates (same payload and symbology)
    /// dropped, keeping the first in reading order. Codes whose vertical centres are within half
    /// the smaller height of each other count as one row, so a slightly tilted line still reads
    /// left to right.
    static func order(_ found: [Found]) -> [ScannedCode] {
        let byTop = found.sorted { $0.box.midY > $1.box.midY }
        var rows: [[Found]] = []
        var rowRef: [CGRect] = []
        for item in byTop {
            if let ref = rowRef.last,
               abs(item.box.midY - ref.midY) <= 0.5 * min(item.box.height, ref.height) {
                rows[rows.count - 1].append(item)
            } else {
                rows.append([item])
                rowRef.append(item.box)
            }
        }
        var seen = Set<ScannedCode>()
        var out: [ScannedCode] = []
        for row in rows {
            for item in row.sorted(by: { $0.box.minX < $1.box.minX }) where seen.insert(item.code).inserted {
                out.append(item.code)
            }
        }
        return out
    }
}

extension CGImagePropertyOrientation {
    init(_ o: UIImage.Orientation) {
        switch o {
        case .up: self = .up
        case .upMirrored: self = .upMirrored
        case .down: self = .down
        case .downMirrored: self = .downMirrored
        case .left: self = .left
        case .leftMirrored: self = .leftMirrored
        case .right: self = .right
        case .rightMirrored: self = .rightMirrored
        @unknown default: self = .up
        }
    }
}
