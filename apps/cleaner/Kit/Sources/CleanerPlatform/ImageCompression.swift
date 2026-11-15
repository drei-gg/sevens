#if canImport(ImageIO) && canImport(Accelerate)
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Accelerate
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
        guard (0.9...1).contains(quality), let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil),
              CGImageSourceGetCount(source) == 1,
              let type = CGImageSourceGetType(source), [UTType.jpeg.identifier, UTType.png.identifier].contains(type as String),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width <= 16_000_000 / height else {
            throw CleanerError.blocked("This profile accepts JPEG or PNG stills up to 16 megapixels. Other profiles still need validation.")
        }
        for kind in [kCGImageAuxiliaryDataTypeDisparity, kCGImageAuxiliaryDataTypeDepth, kCGImageAuxiliaryDataTypePortraitEffectsMatte, kCGImageAuxiliaryDataTypeHDRGainMap] {
            if CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0, kind) != nil { throw CleanerError.blocked("This image contains auxiliary or HDR data. Keep the original; this profile cannot preserve it yet.") }
        }
        guard let original = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
              original.bitsPerComponent == 8, original.colorSpace?.name == CGColorSpace.sRGB,
              [.none, .noneSkipFirst, .noneSkipLast].contains(original.alphaInfo) else {
            throw CleanerError.blocked("This profile preserves 8-bit sRGB images without transparency. This image needs a different profile.")
        }
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
        var assessment = CompressionAssessment(source: before, output: after, fullResolutionPSNR: scores.0, worstTilePSNR: scores.1)
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
            colorSpace: image.colorSpace?.name as String? ?? "unknown", hasAlpha: ![.none, .noneSkipFirst, .noneSkipLast].contains(image.alphaInfo), bits: image.bitsPerComponent, bytes: Int64(bytes))
    }
    /// Every pixel at native resolution, bounded strip buffers. Worst tile catches localized damage
    /// which a global average hides. PSNR remains a diagnostic, not perceptual approval.
    private static func compare(_ original: CGImage, _ output: CGImage) throws -> (Double, Double) {
        var total: Double = 0; var count = 0; var worst: Double = .infinity
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        for y in stride(from: 0, to: original.height, by: 128) {
            try Task.checkCancellation()
            for x in stride(from: 0, to: original.width, by: 128) {
                let w = min(128, original.width - x), h = min(128, original.height - y)
                let rect = CGRect(x: x, y: y, width: w, height: h)
                func rgb(_ image: CGImage) throws -> [Double] {
                    guard let crop = image.cropping(to: rect), let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue), let data = context.data else { throw CleanerError.blocked("Could not compare full-resolution detail.") }
                    context.draw(crop, in: CGRect(x: 0, y: 0, width: w, height: h))
                    let bytes = data.assumingMemoryBound(to: UInt8.self)
                    return (0..<(w*h*4)).compactMap { $0 % 4 == 3 ? nil : Double(bytes[$0]) }
                }
                let a = try rgb(original), b = try rgb(output)
                var diff = [Double](repeating: 0, count: a.count); var mse: Double = 0
                vDSP_vsubD(a, 1, b, 1, &diff, 1, vDSP_Length(a.count)); vDSP_measqvD(diff, 1, &mse, vDSP_Length(a.count))
                total += mse * Double(a.count); count += a.count
                worst = min(worst, mse == 0 ? 120 : 10 * log10(65025 / mse))
            }
        }
        let mse = total / Double(count)
        return (mse == 0 ? 120 : 10 * log10(65025 / mse), worst)
    }
}
#endif
