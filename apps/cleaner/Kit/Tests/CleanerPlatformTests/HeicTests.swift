#if canImport(ImageIO) && canImport(CoreVideo)
import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import Testing
import UniformTypeIdentifiers
@testable import CleanerPlatform
import CleanerCore

/// HEIC copies keep metadata and every auxiliary image, or are refused. The fixture is written here
/// with ImageIO: a Display P3 photo with orientation, capture metadata, Apple's HDR headroom maker
/// tags and auxiliary images (disparity, portrait and skin mattes, an HDR gain map).
private let heicWritable = (CGImageDestinationCopyTypeIdentifiers() as? [String] ?? []).contains(UTType.heic.identifier)

private struct HeicFixture {
    let url: URL
    let auxiliary: [AuxiliaryImage]
    let properties: [CFString: Any]
}

private func auxiliaryInfo(width: Int, height: Int, format: OSType, bytesPerPixel: Int, fill: (Int, Int) -> [UInt8]) -> [CFString: Any] {
    var data = Data(capacity: width * height * bytesPerPixel)
    for y in 0..<height { for x in 0..<width { data.append(contentsOf: fill(x, y)) } }
    let description: [CFString: Any] = [kCGImagePropertyWidth: width, kCGImagePropertyHeight: height,
                                        kCGImagePropertyBytesPerRow: width * bytesPerPixel, kCGImagePropertyPixelFormat: format]
    return [kCGImageAuxiliaryDataInfoData: data, kCGImageAuxiliaryDataInfoDataDescription: description]
}

private func writeFixture(in folder: URL, width: Int = 1280, height: Int = 960, auxiliary: Bool = true, type: UTType = .heic) throws -> HeicFixture {
    let url = folder.appendingPathComponent("source").appendingPathExtension(type.preferredFilenameExtension ?? "img")
    let space = CGColorSpace(name: CGColorSpace.displayP3)!
    let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
    var seed: UInt64 = 11
    for y in 0..<height { for x in 0..<width {
        seed = seed &* 6364136223846793005 &+ 1
        let grain = Int((seed >> 33) % 9) - 4
        let i = (y * width + x) * 4
        pixels[i] = UInt8(clamping: x * 255 / width + grain)
        pixels[i + 1] = UInt8(clamping: 90 + y * 120 / height + grain)
        pixels[i + 2] = UInt8(clamping: 200 - x * 80 / width + grain)
        pixels[i + 3] = 255
    } }
    let properties: [CFString: Any] = [
        kCGImageDestinationLossyCompressionQuality: 1.0,
        kCGImagePropertyOrientation: 6,
        kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Sevens", kCGImagePropertyTIFFModel: "Fixture"],
        kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:11:22 09:10:11", kCGImagePropertyExifLensModel: "Fixture lens",
                                         kCGImagePropertyExifPixelXDimension: width, kCGImagePropertyExifPixelYDimension: height],
        kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 9.74, kCGImagePropertyGPSLatitudeRef: "N",
                                        kCGImagePropertyGPSLongitude: 118.73, kCGImagePropertyGPSLongitudeRef: "E"],
        kCGImagePropertyMakerAppleDictionary: ["33": 0.85, "48": 0.0125],
    ]
    let destination = try #require(CGImageDestinationCreateWithURL(url as CFURL, type.identifier as CFString, 1, nil))
    CGImageDestinationAddImage(destination, context.makeImage()!, properties as CFDictionary)
    var refused: [String] = []
    if auxiliary {
        let w = width / 4, h = height / 4
        let disparity = auxiliaryInfo(width: w, height: h, format: kCVPixelFormatType_DisparityFloat16, bytesPerPixel: 2) { x, _ in
            // Half-float 0.25, 0.5 or 1.0 by column (Float16 itself is unavailable on Intel simulators).
            let half: UInt16 = [0x3400, 0x3800, 0x3C00][x * 3 / w]; return [UInt8(half & 0xFF), UInt8(half >> 8)]
        }
        let matte = auxiliaryInfo(width: w, height: h, format: kCVPixelFormatType_OneComponent8, bytesPerPixel: 1) { x, y in [UInt8((x + y) % 256)] }
        let candidates: [(CFString, [CFString: Any])] = [
            (kCGImageAuxiliaryDataTypeDisparity, disparity), (kCGImageAuxiliaryDataTypePortraitEffectsMatte, matte),
            (kCGImageAuxiliaryDataTypeSemanticSegmentationSkinMatte, matte), (kCGImageAuxiliaryDataTypeHDRGainMap, matte),
        ]
        // Some ImageIO builds (the simulator's among them) refuse to write a kind; the fixture
        // carries every kind this one can write, and the test reports the rest.
        for (kind, info) in candidates {
            let probe = folder.appendingPathComponent(UUID().uuidString).appendingPathExtension(type.preferredFilenameExtension ?? "img")
            defer { try? FileManager.default.removeItem(at: probe) }
            guard let test = CGImageDestinationCreateWithURL(probe as CFURL, type.identifier as CFString, 1, nil) else { continue }
            CGImageDestinationAddImage(test, context.makeImage()!, nil)
            CGImageDestinationAddAuxiliaryDataInfo(test, kind, info as CFDictionary)
            if CGImageDestinationFinalize(test) { CGImageDestinationAddAuxiliaryDataInfo(destination, kind, info as CFDictionary) }
            else { refused.append(kind as String) }
        }
    }
    print("CLEANER_HEIC_FIXTURE type=\(type.identifier) refusedByImageIO=\(refused)")
    #expect(CGImageDestinationFinalize(destination))
    let source = try #require(CGImageSourceCreateWithURL(url as CFURL, nil))
    let found = ImageCompression.auxiliaryImages(source).map { $0.kind }
    let saved = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] ?? [:]
    return HeicFixture(url: url, auxiliary: found, properties: saved)
}

private func folder() throws -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

@Test(.enabled(if: heicWritable))
func heicCopyKeepsMetadataColourAndAuxiliaryImages() throws {
    let directory = try folder(); defer { try? FileManager.default.removeItem(at: directory) }
    let fixture = try writeFixture(in: directory)
    // Whatever auxiliary images ImageIO wrote into the fixture, the copy must keep.
    #expect(!fixture.auxiliary.isEmpty)
    let original = try Data(contentsOf: fixture.url)
    for size in [ImageSizeProfile.original, .small] {
        let candidate = try ImageCompression.makeCandidate(sourceURL: fixture.url, directory: directory, quality: 0.92, size: size)
        #expect(candidate.outputURL.pathExtension == "heic")
        let output = try #require(CGImageSourceCreateWithURL(candidate.outputURL as CFURL, nil))
        #expect(CGImageSourceGetType(output) as String? == UTType.heic.identifier)
        #expect(Set(ImageCompression.auxiliaryImages(output).map { $0.kind }) == Set(fixture.auxiliary))
        #expect(!candidate.assessment.issues.contains { $0.contains("Auxiliary") || $0.contains("metadata") || $0.contains("Color") || $0.contains("Orientation") })
        #expect(candidate.assessment.output.colorSpace == candidate.assessment.source.colorSpace)
        #expect(candidate.assessment.output.colorSpace == CGColorSpace.displayP3 as String)
        #expect(candidate.assessment.output.orientation == 6)
        #expect(candidate.assessment.output.width == (size == .small ? 800 : 1280))
        let saved = CGImageSourceCopyPropertiesAtIndex(output, 0, nil) as? [CFString: Any] ?? [:]
        let gps = saved[kCGImagePropertyGPSDictionary] as? NSDictionary
        #expect(gps?[kCGImagePropertyGPSLatitude] as? Double == 9.74)
        #expect((saved[kCGImagePropertyExifDictionary] as? NSDictionary)?[kCGImagePropertyExifLensModel] as? String == "Fixture lens")
        if fixture.properties[kCGImagePropertyMakerAppleDictionary] != nil {
            // When ImageIO carries Apple's maker note, the gain map's headroom tags survive the copy.
            let maker = saved[kCGImagePropertyMakerAppleDictionary] as? NSDictionary
            #expect(maker?["33"] != nil && maker?["48"] != nil)
        }
        #expect(!candidate.assessment.authorizesSourceDeletion)
        #expect(try Data(contentsOf: fixture.url) == original)
        print("CLEANER_HEIC size=\(size) aux=\(fixture.auxiliary) maker=\(fixture.properties[kCGImagePropertyMakerAppleDictionary] != nil) before=\(candidate.assessment.source.bytes) after=\(candidate.assessment.output.bytes) global=\(candidate.assessment.fullResolutionPSNR) worst=\(candidate.assessment.worstTilePSNR) issues=\(candidate.assessment.issues)")
    }
}

@Test(.enabled(if: heicWritable))
func plainHeicCopyHasNoAuxiliaryToKeep() throws {
    let directory = try folder(); defer { try? FileManager.default.removeItem(at: directory) }
    let fixture = try writeFixture(in: directory, width: 640, height: 480, auxiliary: false)
    #expect(fixture.auxiliary.isEmpty)
    let candidate = try ImageCompression.makeCandidate(sourceURL: fixture.url, directory: directory)
    #expect(candidate.outputURL.pathExtension == "heic")
    #expect(!candidate.assessment.issues.contains { $0.contains("Auxiliary") || $0.contains("metadata") })
}

@Test(.enabled(if: heicWritable))
func jpegWithAuxiliaryImagesIsStillRefused() throws {
    let directory = try folder(); defer { try? FileManager.default.removeItem(at: directory) }
    let fixture = try writeFixture(in: directory, width: 640, height: 480, type: .jpeg)
    if fixture.auxiliary.isEmpty { return }   // ImageIO kept none in the JPEG: nothing to refuse
    #expect(throws: CleanerError.self) { try ImageCompression.makeCandidate(sourceURL: fixture.url, directory: directory) }
}
#endif
