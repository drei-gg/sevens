#if DEBUG
import Foundation
import UIKit
import PDFKit
import ScannerCore

extension ScannerModel {
    /// Uses only the isolated scanner.ui fixture directory. Checks saved bytes and actual PDF pixels.
    func annotationAudit(folder: URL, original: Data) async throws {
        let store = DocumentStore(root: folder.appendingPathComponent("annotation-library"))
        let document = try await store.create(title: "Annotations", images: [original], text: ["Preserved text"])
        let mark = PageAnnotation(bounds: PageCrop(left: 0.55, top: 0.45, right: 0.85, bottom: 0.55), style: .highlight)
        let ink = try inkFromDisplay(points: [InkPoint(x: 0.2, y: 0.65), InkPoint(x: 0.8, y: 0.65)], width: 12, displayWidth: 900, displayHeight: 1200, settings: PageSettings(), color: .blue)
        let saved = try await store.updateAnnotations(documentID: document.id, expected: document.pages[0], annotations: [mark], ink: [ink])
        let reopened = try await DocumentStore(root: folder.appendingPathComponent("annotation-library")).load(saved.id)
        guard reopened.version == 4, reopened.pages[0].annotations == [mark], reopened.pages[0].ink == [ink], reopened.pages[0].text == "Preserved text",
              try await store.images(reopened) == [original] else { throw ScanError.InvalidDocument }
        let urls = try await store.pageURLs(reopened)
        try await Task.detached {
            for turn in 0..<4 {
                var settings = PageSettings(); settings.quarterTurns = Int64(turn); settings.grayscale = true
                settings.crop = PageCrop(left: 0.1, top: 0.1, right: 0.9, bottom: 0.9)
                let data = try DocumentProcessing.rendered(original, settings: settings, annotations: [mark], ink: [ink])
                guard let image = UIImage(data: data)?.cgImage else { throw ScanError.InvalidDocument }
                let r = try annotationDisplayBounds(bounds: mark.bounds, settings: settings)
                guard Self.yellow(image, x: r.x + r.width/2, y: r.y + r.height/2) else { throw ScanError.InvalidDocument }
                let drawing = try inkDisplay(ink: ink, settings: settings, displayWidth: Double(image.width), displayHeight: Double(image.height))
                let first = drawing.points.first!, last = drawing.points.last!
                guard Self.blue(image, x: (first.x+last.x)/2/Double(image.width), y: (first.y+last.y)/2/Double(image.height)) else { throw ScanError.InvalidDocument }
            }
            var creation = PageSettings(); creation.crop = PageCrop(left: 0.25, top: 0.25, right: 0.75, bottom: 0.75)
            let clippedInk = try inkFromDisplay(points: [InkPoint(x: -0.4, y: 0.5), InkPoint(x: 1.4, y: 0.5)], width: 8, displayWidth: 450, displayHeight: 600, settings: creation, color: .blue)
            let resetBytes = try DocumentProcessing.rendered(original, ink: [clippedInk])
            guard let reset = UIImage(data: resetBytes)?.cgImage,
                  Self.blue(reset, x: 0.5, y: 0.5), !Self.blue(reset, x: 0.1, y: 0.5) else { throw ScanError.InvalidDocument }
            for searchable in [false, true] {
                let paper: PaperSize = searchable ? .a4 : .letter
                let output = folder.appendingPathComponent("annotations-\(searchable).pdf")
                try DocumentProcessing.pdf(urls: urls, pages: reopened.pages, to: output, quality: searchable ? .standard : .compact, paper: paper, searchable: searchable)
                guard let pdf = PDFDocument(url: output), let page = pdf.page(at: 0) else { throw ScanError.InvalidDocument }
                if searchable { guard pdf.string?.contains("Coffee") == true else { throw ScanError.InvalidDocument } }
                else { guard (pdf.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw ScanError.InvalidDocument } }
                let sheet = paperBox(size: paper)
                guard let frame = pdfImageFrame(size: paper, imageWidth: 900, imageHeight: 1200) else { throw ScanError.InvalidDocument }
                let rendered = page.thumbnail(of: CGSize(width: sheet.width * 2, height: sheet.height * 2), for: .mediaBox)
                guard let image = rendered.cgImage,
                    Self.yellow(image, x: (frame.x + frame.width * 0.7)/sheet.width,
                                y: (sheet.height - frame.y - frame.height + frame.height * 0.5)/sheet.height) else { throw ScanError.InvalidDocument }
                guard Self.blue(image, x: (frame.x + frame.width * 0.5)/sheet.width, y: (sheet.height-frame.y-frame.height+frame.height*0.65)/sheet.height) else { throw ScanError.InvalidDocument }
                try rendered.pngData()?.write(to: folder.appendingPathComponent("annotations-\(searchable).png"))
            }
        }.value
        guard try await store.images(reopened) == [original] else { throw ScanError.InvalidDocument }
    }
    nonisolated private static func yellow(_ image: CGImage, x: Double, y: Double) -> Bool {
        guard let p = sample(image, x: x, y: y) else { return false }; return p[0] > 220 && p[1] > 180 && p[2] < 210
    }
    nonisolated private static func blue(_ image: CGImage, x: Double, y: Double) -> Bool {
        guard let p = sample(image, x: x, y: y) else { return false }; return p[2] > 150 && p[0] < 80
    }
    nonisolated private static func sample(_ image: CGImage, x: Double, y: Double) -> [UInt8]? {
        guard let sample = image.cropping(to: CGRect(x: Int(x * Double(image.width)), y: Int(y * Double(image.height)), width: 1, height: 1)) else { return nil }
        var pixel = [UInt8](repeating: 0, count: 4)
        let drew = pixel.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(sample, in: CGRect(x: 0, y: 0, width: 1, height: 1)); return true
        }
        return drew ? pixel : nil
    }
}
#endif
