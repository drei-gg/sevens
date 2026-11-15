#if DEBUG
import Foundation
import UIKit
import PDFKit
import ScannerCore

extension ScannerModel {
    /// Explicit synthetic QA only. No access to the real library or real documents.
    func processingAudit() async -> String {
        guard ProcessInfo.processInfo.arguments.contains("-scanner.ui") else { return "Unavailable" }
        do {
            let root = await repository.root
            let folder = root.appendingPathComponent("processing-audit")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
            let image = UIGraphicsImageRenderer(size: CGSize(width: 900, height: 1200), format: format).image { ctx in
                UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 900, height: 1200))
                UIColor.red.setFill(); ctx.fill(CGRect(x: 30, y: 1000, width: 100, height: 100))
                ("SCANNER AUDIT\nCoffee receipt\nTotal 120" as NSString).draw(in: CGRect(x: 70, y: 80, width: 760, height: 400), withAttributes: [.font: UIFont.systemFont(ofSize: 42), .foregroundColor: UIColor.black])
            }
            let original = try DocumentProcessing.normalized(image)
            let originalURL = folder.appendingPathComponent("original.jpg"); try original.write(to: originalURL)
            let report = try await Task.detached {
                let pages = (0..<50).map { _ in ScanPage() }
                let large = folder.appendingPathComponent("50-pages.pdf")
                let started = Date()
                try DocumentProcessing.pdf(urls: Array(repeating: originalURL, count: 50), pages: pages, to: large, searchable: false)
                guard PDFDocument(url: large)?.pageCount == 50 else { throw ScanError.InvalidDocument }
                let imported = try DocumentProcessing.importFiles([large])
                guard imported.count == 50 else { throw ScanError.InvalidDocument }
                let importedURL = folder.appendingPathComponent("imported.jpg"); try imported[0].write(to: importedURL)
                guard try DocumentProcessing.recognize([imported[0]])[0].contains("Coffee") else { throw ScanError.InvalidDocument }
                let searchable = folder.appendingPathComponent("searchable-letter.pdf")
                try DocumentProcessing.pdf(urls: [originalURL], pages: [ScanPage()], to: searchable, paper: .letter)
                guard let pdf = PDFDocument(url: searchable), pdf.pageCount == 1, pdf.string?.contains("Coffee") == true,
                      pdf.page(at: 0)?.bounds(for: .mediaBox).width == 612 else { throw ScanError.InvalidDocument }
                var settings = PageSettings(); settings.quarterTurns = 1; settings.grayscale = true; settings.brightness = 0.1; settings.contrast = 1.2
                let edited = try DocumentProcessing.rendered(original, settings: settings)
                try edited.write(to: folder.appendingPathComponent("edited.jpg"))
                let adjusted = UIImage(data: edited)
                guard let adjusted, adjusted.size.width > adjusted.size.height else { throw ScanError.InvalidDocument }
                let reset = try DocumentProcessing.rendered(original)
                guard UIImage(data: reset)?.size.width == 900 else { throw ScanError.InvalidDocument }
                let bytes = try large.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                return "PASS: 50-page export/import; searchable Letter PDF; rotation, adjustments and original reset. PDF bytes: \(bytes). Elapsed: \(Int(Date().timeIntervalSince(started)))s."
            }.value
            try Data(report.utf8).write(to: folder.appendingPathComponent("report.txt"), options: .atomic)
            return report
        } catch { return "FAIL: " + String(describing: error) }
    }
}
#endif
