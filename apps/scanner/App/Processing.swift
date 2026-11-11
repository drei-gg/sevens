import Foundation
import UIKit
import Vision
import CoreGraphics
import CoreText
import CoreImage
import ImageIO
import UniformTypeIdentifiers
import ScannerCore

/// Every operation owns its renderer. Images are decoded one page at a time.
enum DocumentProcessing {
    nonisolated static func recognize(_ images: [Data]) throws -> [String] {
        try images.map { data in try autoreleasepool { try observations(data).map(\.text).joined(separator: "\n") } }
    }
    nonisolated static func recognize(urls: [URL], pages: [ScanPage]) throws -> [UUID: String] {
        guard urls.count == pages.count else { throw ScanError.invalidDocument }
        var result: [UUID: String] = [:]
        for (url, page) in zip(urls, pages) {
            try Task.checkCancellation()
            result[page.id] = try autoreleasepool {
                let bytes = try rendered(Data(contentsOf: url), settings: page.settings)
                return try observations(bytes).map(\.text).joined(separator: "\n")
            }
        }
        return result
    }
    private struct TextLine { var text: String; var bounds: CGRect }
    nonisolated private static func observations(_ data: Data) throws -> [TextLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate; request.usesLanguageCorrection = true
        try VNImageRequestHandler(data: data).perform([request])
        return (request.results ?? []).compactMap { observation in
            guard let text = observation.topCandidates(1).first?.string else { return nil }
            return TextLine(text: text, bounds: observation.boundingBox)
        }
    }
    nonisolated static func rendered(_ data: Data, settings: PageSettings = PageSettings(), maxPixels: Int = 2400, quality: Double = 0.85) throws -> Data {
        try settings.validate()
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixels,
                kCGImageSourceCreateThumbnailWithTransform: true
              ] as CFDictionary) else { throw ScanError.missingPage }
        if settings == PageSettings() { return try jpeg(image, quality: quality) }
        var ci = CIImage(cgImage: image)
        for _ in 0..<settings.quarterTurns { ci = ci.oriented(.right) }
        ci = ci.applyingFilter("CIColorControls", parameters: [kCIInputBrightnessKey: settings.brightness,
            kCIInputContrastKey: settings.contrast, kCIInputSaturationKey: settings.grayscale ? 0 : 1])
        let context = CIContext(options: [.useSoftwareRenderer: true])
        guard let result = context.createCGImage(ci, from: ci.extent) else { throw ScanError.missingPage }
        return try jpeg(result, quality: quality)
    }
    nonisolated private static func jpeg(_ image: CGImage, quality: Double) throws -> Data {
        let bytes = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(bytes, UTType.jpeg.identifier as CFString, 1, nil) else { throw ScanError.invalidDocument }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw ScanError.invalidDocument }
        return bytes as Data
    }
    /// PDF streams to disk. Invisible, positioned Core Text glyphs preserve searchable/selectable text.
    nonisolated static func pdf(urls: [URL], pages: [ScanPage], to output: URL, quality: PDFQuality = .standard, paper: PaperSize = .a4, searchable: Bool = true) throws {
        guard !pages.isEmpty, urls.count == pages.count, pages.count <= 50 else { throw ScanError.invalidDocument }
        guard let consumer = CGDataConsumer(url: output as CFURL) else { throw ScanError.invalidDocument }
        var box = paper == .a4 ? CGRect(x: 0, y: 0, width: 595, height: 842) : CGRect(x: 0, y: 0, width: 612, height: 792)
        guard let context = CGContext(consumer: consumer, mediaBox: &box, nil) else { throw ScanError.invalidDocument }
        var success = false
        defer { context.closePDF(); if !success { try? FileManager.default.removeItem(at: output) } }
        for (url, page) in zip(urls, pages) {
            try Task.checkCancellation()
            try autoreleasepool {
                let bytes = try rendered(Data(contentsOf: url), settings: page.settings, maxPixels: quality == .compact ? 1200 : 2400, quality: quality == .compact ? 0.55 : 0.85)
                guard let source = CGImageSourceCreateWithData(bytes as CFData, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw ScanError.missingPage }
                let scale = min((box.width - 36) / CGFloat(image.width), (box.height - 36) / CGFloat(image.height))
                let size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
                let frame = CGRect(x: (box.width-size.width)/2, y: (box.height-size.height)/2, width: size.width, height: size.height)
                context.beginPDFPage(nil); context.draw(image, in: frame)
                if searchable {
                    for line in try observations(bytes) {
                        let rect = CGRect(x: frame.minX + line.bounds.minX * frame.width, y: frame.minY + line.bounds.minY * frame.height,
                                          width: line.bounds.width * frame.width, height: line.bounds.height * frame.height)
                        let font = CTFontCreateWithName("Helvetica" as CFString, 12, nil)
                        let attributed = NSAttributedString(string: line.text, attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font])
                        let ct = CTLineCreateWithAttributedString(attributed)
                        var ascent: CGFloat = 0; var descent: CGFloat = 0
                        let width = CTLineGetTypographicBounds(ct, &ascent, &descent, nil)
                        guard width > 0, ascent + descent > 0 else { continue }
                        context.saveGState()
                        context.translateBy(x: rect.minX, y: rect.minY)
                        context.scaleBy(x: rect.width / width, y: rect.height / (ascent + descent))
                        context.textMatrix = .identity; context.textPosition = CGPoint(x: 0, y: descent)
                        context.setTextDrawingMode(.invisible); CTLineDraw(ct, context)
                        context.restoreGState()
                    }
                }
                context.endPDFPage()
            }
        }
        success = true
    }
    /// Files may be images or PDFs. Flatten imported PDFs to page images; originals outside the app are untouched.
    nonisolated static func importFiles(_ urls: [URL]) throws -> [Data] {
        guard !urls.isEmpty, urls.count <= 50 else { throw ScanError.tooLarge }
        var pages: [Data] = []; var total = 0
        for url in urls {
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= 100_000_000 else { throw ScanError.tooLarge }
            let isPDF = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .pdf)) == true || url.pathExtension.lowercased() == "pdf"
            if isPDF {
                guard let pdf = CGPDFDocument(url as CFURL), !pdf.isEncrypted, pdf.numberOfPages > 0,
                      pages.count + pdf.numberOfPages <= 50 else { throw ScanError.invalidDocument }
                for index in 1...pdf.numberOfPages {
                    let bytes = try autoreleasepool {
                        guard let page = pdf.page(at: index) else { throw ScanError.missingPage }
                        let bounds = page.getBoxRect(.mediaBox)
                        guard bounds.width > 0, bounds.height > 0, bounds.width.isFinite, bounds.height.isFinite else { throw ScanError.invalidDocument }
                        let rotated = abs(page.rotationAngle) % 180 == 90
                        let w = rotated ? bounds.height : bounds.width; let h = rotated ? bounds.width : bounds.height
                        let scale = min(2400 / max(w, h), 3)
                        let rect = CGRect(x: 0, y: 0, width: max(1, (w * scale).rounded()), height: max(1, (h * scale).rounded()))
                        guard let context = CGContext(data: nil, width: Int(rect.width), height: Int(rect.height), bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { throw ScanError.invalidDocument }
                        context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fill(rect)
                        context.concatenate(page.getDrawingTransform(.mediaBox, rect: rect, rotate: 0, preserveAspectRatio: true)); context.drawPDFPage(page)
                        guard let image = context.makeImage() else { throw ScanError.invalidDocument }
                        return try jpeg(image, quality: 0.85)
                    }
                    total += bytes.count; guard total <= 100_000_000 else { throw ScanError.tooLarge }; pages.append(bytes)
                }
            } else {
                guard pages.count < 50 else { throw ScanError.tooLarge }
                let bytes = try autoreleasepool { try rendered(Data(contentsOf: url)) }
                total += bytes.count; guard total <= 100_000_000 else { throw ScanError.tooLarge }; pages.append(bytes)
            }
        }
        return pages
    }
    @MainActor static func exportURL(filename: String) throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("ScannerExports").appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        return folder.appendingPathComponent(filename)
    }
    @MainActor static func cleanOldExports(now: Date = Date()) {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("ScannerExports", isDirectory: true)
        guard let urls = try? FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: [.creationDateKey]) else { return }
        for url in urls where UUID(uuidString: url.lastPathComponent) != nil {
            if let date = try? url.resourceValues(forKeys: [.creationDateKey]).creationDate, now.timeIntervalSince(date) > 86_400 { try? FileManager.default.removeItem(at: url) }
        }
    }
    @MainActor static func normalized(_ image: UIImage) throws -> Data {
        let scale = min(1, 2400 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        guard size.width > 0, size.height > 0 else { throw ScanError.invalidDocument }
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let normalized = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size)); image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let bytes = normalized.jpegData(compressionQuality: 0.85) else { throw ScanError.invalidDocument }; return bytes
    }
}
