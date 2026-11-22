import Foundation
import Testing
@testable import CleanerPlatform

@Test func phoneRegionAndExtensions() {
    #expect(PhoneKeys.normalized("020 7946 0018", region: nil) == nil)
    #expect(PhoneKeys.normalized("020 7946 0018", region: "GB") == "+442079460018")
    #expect(PhoneKeys.normalized("+44 20 7946 0018 ext 12", region: nil) == "+442079460018;ext=12")
    #expect(PhoneKeys.normalized("+44 20 7946 0018 ext 13", region: nil) != PhoneKeys.normalized("+44 20 7946 0018 ext 12", region: nil))
}
#if canImport(Contacts)
import Contacts
@Test func contactArchivePreservesRichFields() throws {
    let contact = CNMutableContact()
    contact.givenName = "Jean"; contact.middleName = "Example"; contact.familyName = "Test"; contact.nickname = "J"
    contact.previousFamilyName = "Earlier"; contact.phoneticGivenName = "Jeen"; contact.organizationName = "Company"; contact.departmentName = "Studio"; contact.jobTitle = "Director"
    contact.phoneNumbers = [CNLabeledValue(label: CNLabelHome, value: CNPhoneNumber(stringValue: "+44 20 7946 0018 ext 12")), CNLabeledValue(label: "Private", value: CNPhoneNumber(stringValue: "+44 20 7946 0018"))]
    contact.emailAddresses = [CNLabeledValue(label: "Work", value: "jean@example.com")]
    let address = CNMutablePostalAddress(); address.street = "12 Test Road"; address.city = "Test City"; address.isoCountryCode = "GB"
    contact.postalAddresses = [CNLabeledValue(label: "Studio", value: address)]
    contact.birthday = DateComponents(year: 1990, month: 2, day: 3)
    contact.dates = [CNLabeledValue(label: "Anniversary", value: NSDateComponents())]
    contact.contactRelations = [CNLabeledValue(label: CNLabelContactRelationSpouse, value: CNContactRelation(name: "Drei"))]
    contact.socialProfiles = [CNLabeledValue(label: "Profile", value: CNSocialProfile(urlString: "https://example.com/jean", username: "jean", userIdentifier: "test", service: "Example"))]
    contact.instantMessageAddresses = [CNLabeledValue(label: "Chat", value: CNInstantMessageAddress(username: "jean", service: "Test"))]
    contact.urlAddresses = [CNLabeledValue(label: "Site", value: "https://example.com")]
    contact.imageData = Data([1,2,3,4])
    // In-memory contact permits notes; production fetch deliberately cannot request them without entitlement.
    contact.note = "Important note must survive an archive when available"
    let data = try ContactArchive.verified(contact), roundtrip = try ContactArchive.decode(data)
    #expect(roundtrip.note == contact.note); #expect(roundtrip.imageData == contact.imageData)
    #expect(roundtrip.socialProfiles == contact.socialProfiles); #expect(roundtrip.contactRelations == contact.contactRelations)
    #expect(roundtrip.nonGregorianBirthday == contact.nonGregorianBirthday)
}
#endif
#if canImport(ImageIO) && canImport(Accelerate)
import ImageIO
import UniformTypeIdentifiers
import CoreGraphics
@Test func actualEncodedOutputIsInspected() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("source.png")
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let context = CGContext(data: nil, width: 512, height: 512, bitsPerComponent: 8, bytesPerRow: 512*4, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    context.setFillColor(CGColor(gray: 0.8, alpha: 1)); context.fill(CGRect(x: 0,y: 0,width: 512,height: 512))
    for y in stride(from: 0, to: 512, by: 3) { context.setFillColor(CGColor(gray: Double(y)/512, alpha: 1)); context.fill(CGRect(x: y/2,y: y,width: 300,height: 1)) }
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, [kCGImagePropertyOrientation: 6] as CFDictionary)
    #expect(CGImageDestinationFinalize(destination))
    let candidate = try ImageCompression.makeCandidate(sourceURL: url, directory: directory)
    #expect(candidate.assessment.output.width == 512)
    #expect(candidate.assessment.output.height == 512)
    #expect(candidate.assessment.output.orientation == 6)
    #expect(candidate.assessment.fullResolutionPSNR > 0)
    #expect(!candidate.assessment.authorizesSourceDeletion)
    #expect(FileManager.default.fileExists(atPath: url.path))
    let originalBytes = try Data(contentsOf: url)
    for quality in [0.9, 1.0] {
        let revised = try ImageCompression.makeCandidate(sourceURL: url, directory: directory, quality: quality)
        #expect(revised.quality == quality)
        #expect(revised.sourceURL == url)
        #expect(revised.assessment.output.width == 512)
        #expect(revised.assessment.output.orientation == 6)
        #expect(!revised.assessment.authorizesSourceDeletion)
        #expect(try Data(contentsOf: url) == originalBytes)
    }
    print("CLEANER_QUALITY fixture=lines orientation=6 globalPSNR=\(candidate.assessment.fullResolutionPSNR) worstTilePSNR=\(candidate.assessment.worstTilePSNR) issues=\(candidate.assessment.issues)")
}
@Test func explicitResizeChecksSavedDimensionsMetadataAndOriginal() throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("source.png")
    let context = CGContext(data: nil, width: 1600, height: 1200, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    for y in 0..<1200 { context.setFillColor(CGColor(gray: Double(y) / 1200, alpha: 1)); context.fill(CGRect(x: 0, y: y, width: 1600, height: 1)) }
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, [kCGImagePropertyOrientation: 6,
        kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:11:17 10:12:13", kCGImagePropertyExifPixelXDimension: 1600, kCGImagePropertyExifPixelYDimension: 1200]] as CFDictionary)
    #expect(CGImageDestinationFinalize(destination))
    let bytes = try Data(contentsOf: url)
    let candidate = try ImageCompression.makeCandidate(sourceURL: url, directory: folder, size: .small)
    #expect(candidate.size == .small)
    #expect(candidate.assessment.source.width == 1600)
    #expect(candidate.assessment.output.width == 800)
    #expect(candidate.assessment.output.height == 600)
    #expect(candidate.assessment.output.orientation == 6)
    #expect(!candidate.assessment.issues.contains { $0.contains("metadata") || $0.contains("Dimensions") })
    #expect(!candidate.assessment.authorizesSourceDeletion)
    #expect(try Data(contentsOf: url) == bytes)
    let saved = CGImageSourceCreateWithURL(candidate.outputURL as CFURL, nil)!
    let properties = CGImageSourceCopyPropertiesAtIndex(saved, 0, nil)! as NSDictionary
    let exif = properties[kCGImagePropertyExifDictionary] as! NSDictionary
    #expect(exif[kCGImagePropertyExifDateTimeOriginal] as? String == "2026:11:17 10:12:13")
    #expect(exif[kCGImagePropertyExifPixelXDimension] as? Int == 800)
}
@Test func alphaCannotBeSilentlyFlattened() throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("alpha.png")
    let context = CGContext(data: nil,width: 32,height: 32,bitsPerComponent: 8,bytesPerRow: 128,space: CGColorSpace(name: CGColorSpace.sRGB)!,bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString,1,nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, nil); #expect(CGImageDestinationFinalize(destination))
    #expect(throws: (any Error).self) { try ImageCompression.makeCandidate(sourceURL: url,directory: folder) }
    #expect(FileManager.default.fileExists(atPath: url.path))
}
#endif
#if canImport(ImageIO) && canImport(Accelerate)
import CoreText
@Test(arguments: ["gradient", "noise", "text", "saturated"])
func compressionCorpus(kind: String) throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("source.png")
    let w = 640, h = 480
    let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w*4, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
    var seed: UInt64 = 7
    for y in 0..<h { for x in 0..<w {
        let i = (y*w+x)*4
        seed = seed &* 6364136223846793005 &+ 1
        let noise = Int((seed >> 32) % 31) - 15
        let base = x * 255 / (w-1)
        let n = kind == "noise" ? noise : 0
        pixels[i] = UInt8(clamping: base + n); pixels[i+1] = UInt8(clamping: y*255/(h-1) + n); pixels[i+2] = UInt8(clamping: 160 + n); pixels[i+3] = 255
    } }
    if kind == "text" {
        context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fill(CGRect(x: 0,y: 0,width: w,height: h))
        for i in 0..<20 {
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: "Receipt 0123456789 · Small text and fine strokes", attributes: [NSAttributedString.Key(kCTFontAttributeName as String): CTFontCreateWithName("Helvetica" as CFString, CGFloat(8+i%5), nil)]) as CFAttributedString)
            context.textPosition = CGPoint(x: 15,y: 20+i*21); CTLineDraw(line, context)
        }
    }
    if kind == "saturated" {
        for x in stride(from: 0,to: w,by: 3) {
            context.setFillColor(CGColor(red: x % 2 == 0 ? 1 : 0, green: 0,blue: x % 2 == 0 ? 0 : 1,alpha: 1)); context.fill(CGRect(x: x,y: 0,width: 1,height: h))
        }
    }
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString,1,nil)!
    CGImageDestinationAddImage(destination, context.makeImage()!, [kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2024:03:04 12:13:14"]] as CFDictionary)
    #expect(CGImageDestinationFinalize(destination))
    let originalBytes = try Data(contentsOf: url)
    let candidate = try ImageCompression.makeCandidate(sourceURL: url, directory: folder)
    #expect(try Data(contentsOf: url) == originalBytes)
    #expect(!candidate.assessment.authorizesSourceDeletion)
    #expect(candidate.assessment.output.width == w && candidate.assessment.output.height == h)
    if kind == "saturated" { #expect(!candidate.assessment.canOfferCopy) }
    print("CLEANER_QUALITY fixture=\(kind) before=\(candidate.assessment.source.bytes) after=\(candidate.assessment.output.bytes) global=\(candidate.assessment.fullResolutionPSNR) worst=\(candidate.assessment.worstTilePSNR) issues=\(candidate.assessment.issues)")
}
@Test func wideGamutRequiresItsOwnProfile() throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: folder) }
    let url = folder.appendingPathComponent("p3.png")
    let context = CGContext(data: nil,width: 32,height: 32,bitsPerComponent: 8,bytesPerRow: 128,space: CGColorSpace(name: CGColorSpace.displayP3)!,bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
    let destination = CGImageDestinationCreateWithURL(url as CFURL,UTType.png.identifier as CFString,1,nil)!
    CGImageDestinationAddImage(destination,context.makeImage()!,nil); #expect(CGImageDestinationFinalize(destination))
    #expect(throws: (any Error).self) { try ImageCompression.makeCandidate(sourceURL: url,directory: folder) }
}
#endif
#if canImport(CryptoKit)
@Test func fileHashStreamsAndDistinguishesContents() throws {
    let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true); defer { try? FileManager.default.removeItem(at: folder) }
    let a = folder.appendingPathComponent("a"), b = folder.appendingPathComponent("b")
    try Data(repeating: 17,count: 2_100_000).write(to: a); try Data(contentsOf: a).write(to: b)
    #expect(try FileDigest.sha256(a) == FileDigest.sha256(b))
    try Data(repeating: 18,count: 2_100_000).write(to: b)
    #expect(try FileDigest.sha256(a) != FileDigest.sha256(b))
}
#endif
