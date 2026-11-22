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
    public var size: ImageSizeProfile = .original
}
public enum ImageCompression {
    /// The auxiliary images ImageIO names, as the core knows them. A HEIC copy carries every one of
    /// them; a JPEG copy carries none.
    static var auxiliaryTypes: [(kind: AuxiliaryImage, type: CFString)] { [
        (.depth, kCGImageAuxiliaryDataTypeDepth), (.disparity, kCGImageAuxiliaryDataTypeDisparity),
        (.portraitMatte, kCGImageAuxiliaryDataTypePortraitEffectsMatte),
        (.skinMatte, kCGImageAuxiliaryDataTypeSemanticSegmentationSkinMatte),
        (.hairMatte, kCGImageAuxiliaryDataTypeSemanticSegmentationHairMatte),
        (.teethMatte, kCGImageAuxiliaryDataTypeSemanticSegmentationTeethMatte),
        (.glassesMatte, kCGImageAuxiliaryDataTypeSemanticSegmentationGlassesMatte),
        (.skyMatte, kCGImageAuxiliaryDataTypeSemanticSegmentationSkyMatte),
        (.hdrGainMap, kCGImageAuxiliaryDataTypeHDRGainMap), (.isoGainMap, kCGImageAuxiliaryDataTypeISOGainMap),
    ] }
    /// Apple's HDR headroom maker-note tags, which say how strongly the HDR gain map applies.
    static let hdrMakerTags = ["33", "48"]

    /// Measured still profiles: ordinary 8-bit sRGB JPEG/PNG become JPEG; 8-bit sRGB or Display P3
    /// HEIC stays HEIC with its metadata and auxiliary images (depth, mattes, gain maps). No
    /// implicit RAW/Live conversion.
    public static func makeCandidate(sourceURL: URL, directory: URL, quality: Double = 0.96, size: ImageSizeProfile = .original) throws -> CompressionCandidate {
        try Task.checkCancellation()
        // ImageIO reads the facts; the core's profile gates decide. Anything unreadable reads as a
        // fact the gate refuses (no frames, no type, no pixels).
        let source = CGImageSourceCreateWithURL(sourceURL as CFURL, nil)
        let properties = source.flatMap { CGImageSourceCopyPropertiesAtIndex($0, 0, nil) as? [CFString: Any] } ?? [:]
        let type = source.flatMap(CGImageSourceGetType).map { $0 as String } ?? ""
        try checkCompressionSource(quality: quality, frameCount: UInt32(clamping: source.map(CGImageSourceGetCount) ?? 0),
                                   typeIdentifier: type,
                                   width: Int64(properties[kCGImagePropertyPixelWidth] as? Int ?? 0),
                                   height: Int64(properties[kCGImagePropertyPixelHeight] as? Int ?? 0))
        guard let source else { throw CleanerError.Unreadable }   // unreachable: no source has no frames
        let outputType = stillOutputType(typeIdentifier: type)
        let heic = outputType == UTType.heic.identifier
        let auxiliary = auxiliaryImages(source)
        if heic { try checkAuxiliaryImages(present: auxiliary.map { $0.kind }, carried: auxiliaryTypes.map { $0.kind }) }
        else if !auxiliary.isEmpty { throw auxiliaryDataRefusal() }
        let decoded = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        if heic {
            try checkHeicPixels(bitsPerComponent: Int32(decoded?.bitsPerComponent ?? 0), colorSpace: stillColorSpace(decoded?.colorSpace),
                                hasAlpha: decoded.map(hasAlpha) ?? false, keepsDisplayP3: true)
        } else {
            try checkCompressionPixels(bitsPerComponent: Int32(decoded?.bitsPerComponent ?? 0), isSrgb: decoded?.colorSpace?.name == CGColorSpace.sRGB,
                                       hasAlpha: decoded.map(hasAlpha) ?? false)
        }
        guard let original = decoded else { throw CleanerError.Unreadable }   // unreachable: no image has no bits
        let plan = try imageResizePlan(width: UInt32(original.width), height: UInt32(original.height), profile: size)
        let reference: CGImage
        if plan.resized {
            // Resized in the source's own colour space (sRGB or Display P3), so the profile is kept.
            guard let context = CGContext(data: nil, width: Int(plan.width), height: Int(plan.height), bitsPerComponent: 8,
                bytesPerRow: 0, space: original.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { throw CleanerError.Unreadable }
            context.interpolationQuality = .high
            context.draw(original, in: CGRect(x: 0, y: 0, width: Int(plan.width), height: Int(plan.height)))
            guard let resized = context.makeImage() else { throw CleanerError.Unreadable }
            reference = resized
        } else { reference = original }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Room for the candidate and its saved read-back before encoding, as video checks; unknown
        // free space reads as zero and refuses.
        let free = (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity) ?? 0
        try checkStillCopySpace(outputWidth: plan.width, outputHeight: plan.height, availableBytes: UInt64(max(0, free)))
        let outputURL = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension(heic ? "heic" : "jpg")
        var committed = false
        defer { if !committed { try? FileManager.default.removeItem(at: outputURL) } }
        guard let destination = CGImageDestinationCreateWithURL(outputURL as CFURL, outputType as CFString, 1, nil) else {
            throw CleanerError.blocked(heic ? "This device can’t write HEIC copies. The original is unchanged." : "Could not create the candidate file.")
        }
        var outputProperties = properties
        outputProperties[kCGImageDestinationLossyCompressionQuality] = quality
        if plan.resized {
            outputProperties[kCGImagePropertyPixelWidth] = Int(plan.width)
            outputProperties[kCGImagePropertyPixelHeight] = Int(plan.height)
            if var exif = outputProperties[kCGImagePropertyExifDictionary] as? [CFString: Any] {
                exif[kCGImagePropertyExifPixelXDimension] = Int(plan.width)
                exif[kCGImagePropertyExifPixelYDimension] = Int(plan.height)
                outputProperties[kCGImagePropertyExifDictionary] = exif
            }

        }
        CGImageDestinationAddImage(destination, reference, outputProperties as CFDictionary)
        // Each auxiliary image goes across as ImageIO read it: data, description and its metadata.
        // They are defined relative to the primary image, so a resized copy keeps them valid.
        for image in auxiliary { CGImageDestinationAddAuxiliaryDataInfo(destination, image.type, image.info as CFDictionary) }
        guard CGImageDestinationFinalize(destination) else { throw CleanerError.blocked("The encoder could not finish. The original is unchanged.") }
        try Task.checkCancellation()
        guard let reopened = CGImageSourceCreateWithURL(outputURL as CFURL, nil),
              let output = CGImageSourceCreateImageAtIndex(reopened, 0, nil),
              let savedProperties = CGImageSourceCopyPropertiesAtIndex(reopened, 0, nil) as? [CFString: Any] else { throw CleanerError.blocked("The saved candidate could not be reopened.") }
        let before = try facts(original, properties: properties, url: sourceURL)
        let after = try facts(output, properties: savedProperties, url: outputURL)
        guard reference.width == after.width, reference.height == after.height else { throw CleanerError.blocked("The output dimensions changed.") }
        let scores = try compare(reference, output)
        var assessment = CompressionAssessment(source: before, output: after, fullResolutionPSNR: scores.fullResolution, worstTilePSNR: scores.worstTile, allowResize: plan.resized)
        // Every auxiliary image must read back with its original geometry and format, or no copy.
        let kept = auxiliary.filter { image in
            guard let saved = CGImageSourceCopyAuxiliaryDataInfoAtIndex(reopened, 0, image.type) as? [CFString: Any],
                  saved[kCGImageAuxiliaryDataInfoData] is Data else { return false }
            return sameGeometry(image.info[kCGImageAuxiliaryDataInfoDataDescription], saved[kCGImageAuxiliaryDataInfoDataDescription])
        }
        assessment.issues += auxiliaryImageIssues(source: auxiliary.map { $0.kind }, kept: kept.map { $0.kind })
        // A gain map is applied by the headroom in Apple's maker note; other maker-note fields are
        // copied where ImageIO carries them, not promised.
        if auxiliary.contains(where: { $0.kind == .hdrGainMap }), let maker = properties[kCGImagePropertyMakerAppleDictionary] as? NSDictionary {
            let saved = savedProperties[kCGImagePropertyMakerAppleDictionary] as? NSDictionary ?? [:]
            for tag in hdrMakerTags where maker[tag] != nil && !NSDictionary(dictionary: [tag: maker[tag]!]).isEqual(to: [tag: saved[tag] as Any]) {
                assessment.issues.append("Capture metadata changed: Apple maker note \(tag).")
            }
        }
        // Check exact values of capture metadata we claim to preserve, not file-layout/encoder tags.
        for key in [kCGImagePropertyGPSDictionary, kCGImagePropertyExifDictionary, kCGImagePropertyTIFFDictionary] {
            if let expected = outputProperties[key] as? NSDictionary {
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
        return CompressionCandidate(sourceURL: sourceURL, outputURL: outputURL, assessment: assessment, quality: quality, size: size)
    }
    private static func facts(_ image: CGImage, properties: [CFString: Any], url: URL) throws -> ImageFacts {
        let bytes = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        return ImageFacts(width: image.width, height: image.height, orientation: properties[kCGImagePropertyOrientation] as? Int ?? 1,
            colorSpace: image.colorSpace?.name as String? ?? "unknown", hasAlpha: hasAlpha(image), bits: image.bitsPerComponent, bytes: Int64(bytes))
    }
    /// The auxiliary images ImageIO finds beside the primary image, each with its full info.
    static func auxiliaryImages(_ source: CGImageSource) -> [(kind: AuxiliaryImage, type: CFString, info: [CFString: Any])] {
        auxiliaryTypes.compactMap { entry in
            (CGImageSourceCopyAuxiliaryDataInfoAtIndex(source, 0, entry.type) as? [CFString: Any]).map { (entry.kind, entry.type, $0) }
        }
    }
    private static func sameGeometry(_ expected: Any?, _ actual: Any?) -> Bool {
        guard let expected = expected as? [CFString: Any] else { return true }
        guard let actual = actual as? [CFString: Any] else { return false }
        return [kCGImagePropertyWidth, kCGImagePropertyHeight, kCGImagePropertyPixelFormat].allSatisfy { key in
            (expected[key] as? NSNumber) == (actual[key] as? NSNumber)
        }
    }
    static func stillColorSpace(_ space: CGColorSpace?) -> StillColorSpace {
        guard let name = space?.name else { return .other }
        if name == CGColorSpace.sRGB { return .srgb }
        if name == CGColorSpace.displayP3 { return .displayP3 }
        return .other
    }
    private static func hasAlpha(_ image: CGImage) -> Bool { ![.none, .noneSkipFirst, .noneSkipLast].contains(image.alphaInfo) }
    /// Every pixel at native resolution, bounded strip buffers: each 128-pixel tile is drawn into
    /// RGBX here and scored by the core. Worst tile catches localized damage which a global
    /// average hides. PSNR remains a diagnostic, not perceptual approval.
    private static func compare(_ original: CGImage, _ output: CGImage) throws -> PsnrScores {
        var tiles: [TileError] = []
        // Compared in the source's colour space, so Display P3 detail is not clipped to sRGB first.
        let space = original.colorSpace ?? CGColorSpace(name: CGColorSpace.sRGB)!
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
