#if canImport(ImageIO)
import Foundation
import ImageIO
import UniformTypeIdentifiers
import CleanerCore

public struct CompressionCandidate: Sendable {
    public var sourceURL: URL
    public var outputURL: URL
    public var assessment: CompressionAssessment
    public var quality: Double
}
public enum ImageCompression {
    /// Initial measured profile: ordinary 8-bit sRGB stills. No implicit HDR/RAW/Live conversion.
    public static func makeCandidate(sourceURL: URL, directory: URL, quality: Double = 0.96) throws -> CompressionCandidate {
        try Task.checkCancellation()
        // ImageIO reads the facts; the core's profile gates decide. Anything unreadable reads as a
        // fact the gate refuses (no frames, no type, no pixels).
        let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)
        let properties = source.flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [CFString: Any] } ?? [:]
        try checkCompressionSource(quality: quality, frameCount: UInt32(clamping: source.map(CGImageSourceGetCount) ?? 0),
                                   typeIdentifier: source.flatMap(CGImageSourceGetType).map { $0 as String } ?? "",
                                   width: Int64(properties[kCGImagePropertyPixelWidth] as? Int ?? 0),
                                   height: Int64(properties[kCGImagePropertyPixelHeight] as? Int ?? 0))
        guard let source else { throw CleanerError.Unreadable }   // unreachable: no source has no frames
        for kind in [kCGImageAuxiliaryDataTypeDisparity, kCGImageAuxiliaryDataTypeDepth, kCGImageAuxiliaryDataTypePortraitEffectsMatte, kCGImageAuxiliaryDataTypeHDRGainMap] {
            if CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0, kind) != nil { throw auxiliaryDataRefusal() }
        }
        let decoded = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        try checkCompressionPixels(bitsPerComponent: Int32(decoded?.bitsPerComponent ?? 0), isSrgb: decoded?.colorSpace?.name == CGColorSpace.sRGB,
                                   hasAlpha: decoded.map(hasAlpha) ?? false)
        guard let original = decoded else { throw CleanerError.Unreadable }   // unreachable: no image has no bits
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let outputURL = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension("jpg")
        var committed = false
        defer { if !committed { try? FileManager.default.removeItem(at: outputURL) } }
        guard let destination = CGImageDestinationCreateWithURL(outputURL as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { throw CleanerError.blocked("Could not create the candidate file.") }
        var outputProperties = properties
        outputProperties[kCGImageDestinationLossyCompressionQuality] = quality
        CGImageDestinationAddImage(destination, original, outputProperties as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CleanerError.blocked("The encoder could not finish. The original is unchanged.") }
        try Task.checkCancellation()
        guard let reopened = CGImageSourceCreateWithURL(outputURL as CFURL, nil),
              let output = CGImageSourceCreateImageAtIndex(reopened, 0, nil),
              let savedProperties = CGImageSourceCopyPropertiesAtIndex(reopened, 0, nil) as? [CFString: Any] else { throw CleanerError.blocked("The saved candidate could not be reopened.") }
        let before = try facts(original, properties: properties, url: sourceURL)
        let after = try facts(output, properties: savedProperties, url: outputURL)
        guard before.width == after.width, before.height == after.height else { throw CleanerError.blocked("The output dimensions changed.") }
        let scores = try compare(original, output)
        var assessment = CompressionAssessment(source: before, output: after, fullResolutionPSNR: scores.fullResolution, worstTilePSNR: scores.worstTile)
        // Check exact values of capture metadata we claim to preserve, not file-layout/encoder tags.
        for key in [kCGImagePropertyGPSDictionary, kCGImagePropertyExifDictionary, kCGImagePropertyTIFFDictionary] {
            if let expected = properties[key] as? NSDictionary {
                let actual = savedProperties[key] as? NSDictionary ?? [:]
                for (field, value) in expected {
                    guard let field = field as? String else { continue }
                    if !NSDictionary(dictionary: [field: value]).isEqual(to: [field: actual[field] as Any]) {
                        assessment.issues.append("Capture metadata changed: \(field).")
                    }
                }
            }
        }
        committed = true
        return CompressionCandidate(sourceURL: sourceURL, outputURL: outputURL, assessment: assessment, quality: quality)
    }
    private static func facts(_ image: CGImage, properties: [CFString: Any], url: URL) throws -> ImageFacts {
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        return ImageFacts(width: image.width, height: image.height, orientation: properties[kCGImagePropertyOrientation] as? Int ?? 1,
            colorSpace: image.colorSpace?.name as String? ?? "unknown", hasAlpha: hasAlpha(image), bits: image.bitsPerComponent, bytes: Int64(bytes))
    }
    private static func hasAlpha(_ image: CGImage) -> Bool { ![.none, .noneSkipFirst, .noneSkipLast].contains(image.alphaInfo) }
    /// Every pixel at native resolution, bounded strip buffers: each 128-pixel tile is drawn into
    /// RGBX here and scored by the core. Worst tile catches localized damage which a global
    /// average hides. PSNR remains a diagnostic, not perceptual approval.
    private static func compare(_ original: CGImage, _ output: CGImage) throws -> PsnrScores {
        var tiles: [TileError] = []
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        for y in stride(from: 0, to: original.height, by: 128) {
            try Task.checkCancellation()
            for x in stride(from: 0, to: original.width, by: 128) {
                let w = min(128, original.width - x), h = min(128, original.height - y)
                let rect = CGRect(x: x, y: y, width: w, height: h)
                func rgbx(_ image: CGImage) throws -> Data {
                    guard let crop = image.cropping(to: rect), let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue), let data = context.data else { throw CleanerError.blocked("Could not compare full-resolution detail.") }
                    context.draw(crop, in: CGRect(x: 0, y: 0, width: w, height: h))
                    return Data(bytes: data, count: w * h * 4)
                }
                tiles.append(try tileError(originalRgbx: rgbx(original), outputRgbx: rgbx(output)))
            }
        }
        return psnrScores(tiles: tiles)
    }
}
#endif
